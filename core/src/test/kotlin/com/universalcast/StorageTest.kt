package com.universalcast

import com.universalcast.identity.PairedSender
import com.universalcast.identity.Role
import com.universalcast.security.SoftwareSenderIdentity
import com.universalcast.storage.*
import org.junit.Test
import java.io.File
import javax.crypto.KeyGenerator
import kotlin.test.*

class StorageTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val aead = AesGcmAead { key }
    private val dir = File.createTempFile("ucr", "").let { it.delete(); it.mkdirs(); it }
    private val file = File(dir, "senders.bin")
    private fun reg(fp: String = "f".repeat(64)) = EncryptedFileSenderRegistry(file, aead, fp)
    private fun sender(name: String = "Phone"): PairedSender {
        val id = SoftwareSenderIdentity.generate()
        return PairedSender(id.senderId, id.publicKeySpki, name, Role.CONTROLLER, 100)
    }

    @Test fun roundTripPersistsEverythingIncludingRevocation() {
        val a = sender("A"); val b = sender("B")
        val r = reg(); r.save(a); r.save(b); r.revoke(b.senderId, 500); r.markConnected(a.senderId, 300)
        val r2 = reg()
        assertEquals(LoadOutcome.LOADED, r2.lastLoadOutcome)
        assertEquals(2, r2.all().size)
        assertEquals(300L, r2.find(a.senderId)!!.lastConnectedAtMs)
        assertTrue(r2.find(b.senderId)!!.isRevoked)
        assertEquals(500L, r2.find(b.senderId)!!.revokedAtMs)
    }

    @Test fun fileContainsNoPlaintext() {
        val s = sender("VeryDistinctiveName"); reg().save(s)
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("VeryDistinctiveName"))
        assertFalse(raw.contains(s.senderId))
    }

    @Test fun tamperedCiphertextIsQuarantinedAndRegistryStartsEmpty() {
        reg().save(sender())
        val bytes = file.readBytes(); bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 1).toByte(); file.writeBytes(bytes)
        val r = reg()
        assertEquals(LoadOutcome.CORRUPT_RESET, r.lastLoadOutcome)
        assertTrue(r.all().isEmpty())
        assertTrue(File(file.path + ".corrupt").exists())
        r.save(sender()); assertEquals(1, reg().all().size, "usable after recovery")
    }

    @Test fun tamperedHeaderIsDetected() {
        reg().save(sender())
        val bytes = file.readBytes(); bytes[4] = 0; file.writeBytes(bytes)
        assertEquals(LoadOutcome.CORRUPT_RESET, reg().lastLoadOutcome)
    }

    @Test fun garbageAndTruncatedFilesAreHandled() {
        file.writeBytes(ByteArray(3)); assertEquals(LoadOutcome.CORRUPT_RESET, reg().lastLoadOutcome)
        file.writeBytes("UCR1\u0001".toByteArray() + ByteArray(5)); assertEquals(LoadOutcome.CORRUPT_RESET, reg().lastLoadOutcome)
        file.writeBytes(ByteArray(200) { it.toByte() }); assertEquals(LoadOutcome.CORRUPT_RESET, reg().lastLoadOutcome)
    }

    @Test fun wrongKeyIsTreatedAsCorrupt() {
        reg().save(sender())
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val r = EncryptedFileSenderRegistry(file, AesGcmAead { other }, "f".repeat(64))
        assertEquals(LoadOutcome.CORRUPT_RESET, r.lastLoadOutcome); assertTrue(r.all().isEmpty())
    }

    @Test fun newerSchemaIsNeverOverwrittenSilently() {
        reg().save(sender())
        val bytes = file.readBytes(); bytes[4] = 9; file.writeBytes(bytes)
        val r = reg()
        assertEquals(LoadOutcome.UNSUPPORTED_VERSION_RESET, r.lastLoadOutcome)
        assertTrue(File(file.path + ".unsupported").exists())
    }

    @Test fun changedReceiverIdentityDropsStalePairings() {
        reg("a".repeat(64)).save(sender())
        val r = reg("b".repeat(64))
        assertEquals(LoadOutcome.IDENTITY_CHANGED_RESET, r.lastLoadOutcome)
        assertTrue(r.all().isEmpty())
    }

    @Test fun nonexistentFileIsFresh() {
        assertEquals(LoadOutcome.FRESH, reg().lastLoadOutcome)
    }

    @Test fun saveReplacesRevokedRecordOnRepairing() {
        val s = sender(); val r = reg(); r.save(s); r.revoke(s.senderId, 5)
        r.save(PairedSender(s.senderId, s.publicKeySpki, "Again", Role.OBSERVER, 10))
        val x = reg().find(s.senderId)!!
        assertFalse(x.isRevoked); assertEquals(Role.OBSERVER, x.role)
    }

    @Test fun revokeUnknownReturnsFalse() { assertFalse(reg().revoke("0".repeat(64), 1)) }

    @Test fun concurrentWritesDoNotCorruptTheFile() {
        val r = reg()
        val ts = (1..8).map { i -> Thread { repeat(10) { r.save(sender("T$i")) } } }
        ts.forEach { it.start() }; ts.forEach { it.join() }
        val r2 = reg()
        assertEquals(LoadOutcome.LOADED, r2.lastLoadOutcome); assertEquals(80, r2.all().size)
    }
}
