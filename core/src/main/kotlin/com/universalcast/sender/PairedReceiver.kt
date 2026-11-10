package com.universalcast.sender

import com.universalcast.security.Fingerprint
import com.universalcast.storage.Aead
import com.universalcast.storage.LoadOutcome
import com.universalcast.ucp.Fields
import com.universalcast.ucp.StrictJson
import java.io.File
import java.io.FileOutputStream

/**
 * A TV this phone has paired with. [fingerprint] is the trust root: every connection is pinned to it, so the
 * addresses are only hints and may go stale (DHCP) without any security impact.
 */
data class PairedReceiver(
    val fingerprint: String,
    val deviceId: String,
    val name: String,
    val addresses: List<String>,
    val port: Int,
    val path: String,
    val role: String,
    val pairedAtMs: Long,
    val lastConnectedAtMs: Long? = null,
)

object PairedReceiverJson {
    fun encode(r: PairedReceiver): String = StrictJson.stringify(
        linkedMapOf(
            "fingerprint" to r.fingerprint, "deviceId" to r.deviceId, "name" to r.name, "addresses" to r.addresses,
            "port" to r.port.toLong(), "path" to r.path, "role" to r.role, "pairedAtMs" to r.pairedAtMs, "lastConnectedAtMs" to r.lastConnectedAtMs,
        )
    )

    /** Returns null for any corrupted or inconsistent record. */
    fun decode(text: String): PairedReceiver? = try {
        @Suppress("UNCHECKED_CAST")
        val f = Fields(StrictJson.parse(text) as Map<String, Any?>)
        val fp = f.string("fingerprint", 64, 64)
        if (!Fingerprint.isWellFormed(fp)) {
            null
        } else {
            PairedReceiver(
                fingerprint = fp,
                deviceId = f.string("deviceId", 64),
                name = f.string("name", 128),
                addresses = f.stringList("addresses", 8, 64),
                port = f.long("port", 1, 65535).toInt(),
                path = f.string("path", 64),
                role = f.string("role", 16),
                pairedAtMs = f.long("pairedAtMs", 0, Long.MAX_VALUE),
                lastConnectedAtMs = f.optLong("lastConnectedAtMs", 0, Long.MAX_VALUE),
            )
        }
    } catch (e: Exception) {
        null
    }
}

interface ReceiverStore {
    fun all(): List<PairedReceiver>
    fun find(fingerprint: String): PairedReceiver?
    /** Inserts or replaces (same fingerprint = same TV, e.g. re-pairing with another role). */
    fun save(receiver: PairedReceiver)
    fun remove(fingerprint: String): Boolean
}

class InMemoryReceiverStore : ReceiverStore {
    private val map = LinkedHashMap<String, PairedReceiver>()
    @Synchronized override fun all() = map.values.toList()
    @Synchronized override fun find(fingerprint: String) = map[fingerprint]
    @Synchronized override fun save(receiver: PairedReceiver) { map[receiver.fingerprint] = receiver }
    @Synchronized override fun remove(fingerprint: String) = map.remove(fingerprint) != null
}

/**
 * AEAD-encrypted, atomically written store of paired TVs (the fingerprints are the trust roots, so their
 * integrity matters even though they are not secret). Same hardening as the receiver's registry: authenticated
 * header, quarantine of unreadable files, newer schemas never overwritten.
 */
class EncryptedFileReceiverStore(private val file: File, private val aead: Aead) : ReceiverStore {
    private val lock = Any()
    private val items = LinkedHashMap<String, PairedReceiver>()

    @Volatile
    var lastLoadOutcome: LoadOutcome = LoadOutcome.FRESH
        private set

    init {
        synchronized(lock) { load() }
    }

    override fun all(): List<PairedReceiver> = synchronized(lock) { items.values.toList() }
    override fun find(fingerprint: String): PairedReceiver? = synchronized(lock) { items[fingerprint] }

    override fun save(receiver: PairedReceiver) = synchronized(lock) {
        val prev = items.put(receiver.fingerprint, receiver)
        try {
            persist()
        } catch (e: Exception) {
            if (prev == null) items.remove(receiver.fingerprint) else items[receiver.fingerprint] = prev
            throw e
        }
    }

    override fun remove(fingerprint: String): Boolean = synchronized(lock) {
        val prev = items.remove(fingerprint) ?: return false
        try {
            persist()
        } catch (e: Exception) {
            items[fingerprint] = prev
            throw e
        }
        true
    }

    private fun header(version: Int) = MAGIC + byteArrayOf(version.toByte())

    private fun persist() {
        val doc = StrictJson.stringify(
            linkedMapOf("schema" to SCHEMA.toLong(), "receivers" to items.values.map { PairedReceiverJson.encode(it) })
        ).toByteArray(Charsets.UTF_8)
        val bytes = header(SCHEMA) + aead.seal(doc, header(SCHEMA))
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not replace receiver store" }
        }
    }

    private fun quarantine(suffix: String) {
        val t = File(file.absolutePath + suffix)
        t.delete()
        if (!file.renameTo(t)) file.delete()
    }

    private fun load() {
        if (!file.exists()) return
        val bytes = try { file.readBytes() } catch (e: Exception) { quarantine(".corrupt"); lastLoadOutcome = LoadOutcome.CORRUPT_RESET; return }
        if (bytes.size < MAGIC.size + 1 || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            quarantine(".corrupt"); lastLoadOutcome = LoadOutcome.CORRUPT_RESET; return
        }
        val version = bytes[MAGIC.size].toInt() and 0xff
        if (version > SCHEMA) { quarantine(".unsupported"); lastLoadOutcome = LoadOutcome.UNSUPPORTED_VERSION_RESET; return }
        val plain = try {
            aead.open(bytes.copyOfRange(MAGIC.size + 1, bytes.size), header(version))
        } catch (e: Exception) {
            quarantine(".corrupt"); lastLoadOutcome = LoadOutcome.CORRUPT_RESET; return
        }
        try {
            @Suppress("UNCHECKED_CAST")
            val doc = StrictJson.parse(String(plain, Charsets.UTF_8)) as Map<String, Any?>
            check(version == SCHEMA) { "No migration from schema $version" }
            for (item in doc["receivers"] as List<*>) {
                val r = (item as? String)?.let { PairedReceiverJson.decode(it) } ?: continue
                items[r.fingerprint] = r
            }
            lastLoadOutcome = LoadOutcome.LOADED
        } catch (e: Exception) {
            items.clear(); quarantine(".corrupt"); lastLoadOutcome = LoadOutcome.CORRUPT_RESET
        }
    }

    companion object {
        const val SCHEMA = 1
        val MAGIC = byteArrayOf('U'.code.toByte(), 'C'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())
    }
}
