package com.universalcast.storage

import com.universalcast.identity.PairedSender
import com.universalcast.identity.PairedSenderJson
import com.universalcast.identity.SenderRegistry
import com.universalcast.security.SecureRandoms
import com.universalcast.ucp.StrictJson
import java.io.File
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption of a blob. On Android the key lives in the Android Keystore; tests use a software key. */
interface Aead {
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray
    /** Throws on any authentication failure (tampering, wrong key, truncation). */
    fun open(blob: ByteArray, aad: ByteArray): ByteArray
}

/** AES-256-GCM. Blob layout: 12-byte random IV || ciphertext+tag. The key may be non-extractable (Keystore). */
class AesGcmAead(private val key: () -> SecretKey) : Aead {
    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val iv = SecureRandoms.bytes(IV_BYTES)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(aad)
        return iv + c.doFinal(plain)
    }

    override fun open(blob: ByteArray, aad: ByteArray): ByteArray {
        require(blob.size > IV_BYTES + TAG_BITS / 8) { "Blob too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_BYTES)))
        c.updateAAD(aad)
        return c.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private companion object {
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

enum class LoadOutcome { FRESH, LOADED, MIGRATED, CORRUPT_RESET, UNSUPPORTED_VERSION_RESET, IDENTITY_CHANGED_RESET }

/**
 * Persistent [SenderRegistry]: one AEAD-encrypted file, written atomically (temp file + fsync + rename).
 *
 * File: magic "UCR1" || version byte || AEAD blob over the JSON document; the header is bound as AAD, so
 * neither the plaintext nor the header can be altered. Unreadable files are quarantined (renamed `.corrupt`)
 * and the registry starts empty rather than crashing or guessing. A file written by a *newer* schema is
 * quarantined as `.unsupported` (never overwritten silently). The document also pins the receiver identity
 * fingerprint it belongs to: if the identity key was regenerated every old pairing is useless (senders pin the
 * old fingerprint) and is dropped.
 */
class EncryptedFileSenderRegistry(
    private val file: File,
    private val aead: Aead,
    private val identityFingerprint: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : SenderRegistry {
    private val lock = Any()
    private val senders = LinkedHashMap<String, PairedSender>()

    @Volatile
    var lastLoadOutcome: LoadOutcome = LoadOutcome.FRESH
        private set

    init {
        synchronized(lock) { load() }
    }

    override fun find(senderId: String): PairedSender? = synchronized(lock) { senders[senderId] }

    override fun all(): List<PairedSender> = synchronized(lock) { senders.values.toList() }

    override fun save(sender: PairedSender) = synchronized(lock) {
        val previous = senders.put(sender.senderId, sender)
        try {
            persist()
        } catch (e: Exception) {
            if (previous == null) senders.remove(sender.senderId) else senders[sender.senderId] = previous
            throw e
        }
    }

    override fun revoke(senderId: String, atMs: Long): Boolean = synchronized(lock) {
        val s = senders[senderId] ?: return false
        if (s.isRevoked) return true
        senders[senderId] = s.copy(revokedAtMs = atMs)
        try {
            persist() // revocation must be durable before we report success
        } catch (e: Exception) {
            senders[senderId] = s
            throw e
        }
        true
    }

    override fun markConnected(senderId: String, atMs: Long) {
        synchronized(lock) {
            val s = senders[senderId] ?: return
            senders[senderId] = s.copy(lastConnectedAtMs = atMs)
            try {
                persist()
            } catch (_: Exception) {
                // Best effort: last-connected is informational only.
            }
        }
    }

    private fun header(version: Int) = MAGIC + byteArrayOf(version.toByte())

    private fun persist() {
        val doc = StrictJson.stringify(
            linkedMapOf(
                "schema" to SCHEMA.toLong(),
                "identity" to identityFingerprint,
                "senders" to senders.values.map { PairedSenderJson.encode(it) },
            )
        ).toByteArray(Charsets.UTF_8)
        val bytes = header(SCHEMA) + aead.seal(doc, header(SCHEMA))
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not replace registry file" }
        }
    }

    private fun quarantine(suffix: String) {
        val target = File(file.absolutePath + suffix)
        target.delete()
        if (!file.renameTo(target)) file.delete()
    }

    private fun load() {
        if (!file.exists()) {
            lastLoadOutcome = LoadOutcome.FRESH
            return
        }
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            quarantine(".corrupt"); lastLoadOutcome = LoadOutcome.CORRUPT_RESET; return
        }
        if (bytes.size < MAGIC.size + 1 || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            quarantine(".corrupt"); lastLoadOutcome = LoadOutcome.CORRUPT_RESET; return
        }
        val version = bytes[MAGIC.size].toInt() and 0xff
        if (version > SCHEMA) {
            quarantine(".unsupported"); lastLoadOutcome = LoadOutcome.UNSUPPORTED_VERSION_RESET; return
        }
        val plain = try {
            aead.open(bytes.copyOfRange(MAGIC.size + 1, bytes.size), header(version))
        } catch (e: Exception) {
            quarantine(".corrupt"); lastLoadOutcome = LoadOutcome.CORRUPT_RESET; return
        }
        try {
            @Suppress("UNCHECKED_CAST")
            val doc = StrictJson.parse(String(plain, Charsets.UTF_8)) as Map<String, Any?>
            val migrated = migrate(version, doc)
            if (migrated["identity"] != identityFingerprint) {
                quarantine(".stale"); lastLoadOutcome = LoadOutcome.IDENTITY_CHANGED_RESET; return
            }
            val list = migrated["senders"] as List<*>
            for (item in list) {
                // A single tampered/invalid record is dropped; valid records survive.
                val s = (item as? String)?.let { PairedSenderJson.decode(it) } ?: continue
                senders[s.senderId] = s
            }
            lastLoadOutcome = if (version < SCHEMA) LoadOutcome.MIGRATED else LoadOutcome.LOADED
            if (version < SCHEMA) persist()
        } catch (e: Exception) {
            senders.clear()
            quarantine(".corrupt"); lastLoadOutcome = LoadOutcome.CORRUPT_RESET
        }
    }

    /** Schema upgrade chain. Version 1 is current; the chain exists so later versions add steps here. */
    private fun migrate(from: Int, doc: Map<String, Any?>): Map<String, Any?> {
        // Add `if (from < 2) { ... }` steps here when SCHEMA grows. Version 1 is current.
        check(from == SCHEMA) { "No migration from schema $from" }
        return doc
    }

    companion object {
        const val SCHEMA = 1
        val MAGIC = byteArrayOf('U'.code.toByte(), 'C'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte())
    }
}
