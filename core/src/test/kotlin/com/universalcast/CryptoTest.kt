package com.universalcast

import com.universalcast.identity.*
import com.universalcast.pairing.PairingCrypto
import com.universalcast.pairing.TranscriptInput
import com.universalcast.security.*
import com.universalcast.ucp.Method
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.test.*

class CryptoTest {
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun hkdfMatchesRfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = unhex("000102030405060708090a0b0c")
        val info = unhex("f0f1f2f3f4f5f6f7f8f9")
        val prk = Hkdf.extract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", Bytes.hex(prk))
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Bytes.hex(Hkdf.expand(prk, info, 42))
        )
    }

    @Test fun fingerprintIsSha256OfSpkiAndStable() {
        val id = SoftwareSenderIdentity.generate()
        val fp = Fingerprint.ofSpki(id.publicKeySpki)
        assertEquals(64, fp.length)
        assertTrue(Fingerprint.isWellFormed(fp))
        assertEquals(fp, id.senderId)
        assertEquals(Bytes.hex(java.security.MessageDigest.getInstance("SHA-256").digest(id.publicKeySpki)), fp)
        assertNotEquals(fp, SoftwareSenderIdentity.generate().senderId)
        assertEquals(95, Fingerprint.display(fp).length)
        assertFalse(Fingerprint.isWellFormed(fp.uppercase()))
        assertFalse(Fingerprint.isWellFormed("abc"))
    }

    @Test fun identityGenerationRestoreKeepsFingerprint() {
        val a = SoftwareSenderIdentity.generate()
        val restored = SoftwareSenderIdentity.restore(a.exportPrivateKeyPkcs8(), a.publicKeySpki)
        assertEquals(a.senderId, restored.senderId)
        assertTrue(EcKeys.verify(a.publicKeySpki, "x".toByteArray(), restored.sign("x".toByteArray())))
    }

    @Test fun receiverIdentityStableFingerprintAndDeviceId() {
        val r = TestPki.receiverIdentity()
        assertEquals(r.fingerprint, Fingerprint.ofSpki(r.publicKeySpki))
        assertEquals(16, r.deviceId.length)
        assertTrue(r.fingerprint.startsWith(r.deviceId))
        assertTrue(EcKeys.verify(r.publicKeySpki, "d".toByteArray(), r.sign("d".toByteArray())))
    }

    @Test fun signaturesVerifyOnlyForExactDataAndKey() {
        val a = SoftwareSenderIdentity.generate()
        val b = SoftwareSenderIdentity.generate()
        val data = "hello".toByteArray()
        val sig = a.sign(data)
        assertTrue(EcKeys.verify(a.publicKeySpki, data, sig))
        assertFalse(EcKeys.verify(a.publicKeySpki, "hellp".toByteArray(), sig))
        assertFalse(EcKeys.verify(b.publicKeySpki, data, sig))
        val tampered = sig.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFalse(EcKeys.verify(a.publicKeySpki, data, tampered))
        assertFalse(EcKeys.verify(a.publicKeySpki, data, ByteArray(0)))
        assertFalse(EcKeys.verify(a.publicKeySpki, data, ByteArray(500)))
        assertFalse(EcKeys.verify(ByteArray(10), data, sig))
    }

    @Test fun publicKeyValidationRejectsBadKeys() {
        assertFailsWith<IllegalArgumentException> { EcKeys.decodePublicKey(ByteArray(5)) }
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }
        assertFailsWith<IllegalArgumentException> { EcKeys.decodePublicKey(g.generateKeyPair().public.encoded) }
        val spki = EcKeys.generateKeyPair().public.encoded.copyOf()
        spki[spki.size - 1] = (spki[spki.size - 1].toInt() xor 0x55).toByte()
        assertFailsWith<IllegalArgumentException> { EcKeys.decodePublicKey(spki) }
    }

    @Test fun ecdhAgrees() {
        val a = EcKeys.generateKeyPair()
        val b = EcKeys.generateKeyPair()
        assertContentEquals(EcKeys.agree(a.private, b.public.encoded), EcKeys.agree(b.private, a.public.encoded))
    }

    @Test fun transcriptIsDomainSeparatedAndUnambiguous() {
        assertFalse(Transcript("A").add("x").toBytes().contentEquals(Transcript("B").add("x").toBytes()))
        assertFalse(Transcript("L").add("ab").add("c").toBytes().contentEquals(Transcript("L").add("a").add("bc").toBytes()))
    }

    private fun input(nR: ByteArray, eR: ByteArray = ByteArray(65) { 2 }) =
        TranscriptInput("f".repeat(64), "pid", ByteArray(91) { 1 }, ByteArray(91) { 3 }, ByteArray(32) { 4 }, eR, nR)

    @Test fun sasDerivationIsDeterministicSixDigitsAndTranscriptBound() {
        val z = ByteArray(32) { 9 }
        val d1 = PairingCrypto.derive(z, input(ByteArray(32) { 5 }))
        val d2 = PairingCrypto.derive(z, input(ByteArray(32) { 5 }))
        assertEquals(d1.sas, d2.sas)
        assertTrue(Regex("^[0-9]{6}$").matches(d1.sas))
        assertContentEquals(d1.confirmKey, d2.confirmKey)
        val d3 = PairingCrypto.derive(z, input(ByteArray(32) { 6 }))
        assertFalse(d1.confirmKey.contentEquals(d3.confirmKey))
        val d4 = PairingCrypto.derive(ByteArray(32) { 8 }, input(ByteArray(32) { 5 }))
        assertFalse(d1.confirmKey.contentEquals(d4.confirmKey))
        val codes = (0 until 300).map { PairingCrypto.derive(z, input(ByteArray(32) { b -> (it + b).toByte() })).sas }
        assertTrue(codes.toSet().size > 250)
        assertTrue(codes.all { it.length == 6 })
    }

    @Test fun pinnedTrustManagerAcceptsOnlyThePinnedKey() {
        val r = TestPki.receiverIdentity()
        val other = TestPki.receiverIdentity()
        val rc = r.certificateChain
        val oc = other.certificateChain
        PinnedTrustManager(r.fingerprint).checkServerTrusted(rc, "ECDHE_ECDSA")
        assertFailsWith<java.security.cert.CertificateException> { PinnedTrustManager(r.fingerprint).checkServerTrusted(oc, "ECDHE_ECDSA") }
        assertFailsWith<java.security.cert.CertificateException> { PinnedTrustManager(r.fingerprint).checkServerTrusted(emptyArray(), "x") }
        assertFailsWith<java.security.cert.CertificateException> { PinnedTrustManager(r.fingerprint).checkClientTrusted(rc, "x") }
    }
}

class PermissionsAndRegistryTest {
    @Test fun observerCanOnlyPingAndReadSnapshots() {
        val allowed = Role.OBSERVER.permissions
        assertEquals(setOf(Permission.PING, Permission.READ_SNAPSHOT), allowed)
        for (m in listOf(Method.SESSION_CREATE, Method.MEDIA_LOAD, Method.PLAYBACK_PLAY, Method.PLAYBACK_PAUSE, Method.PLAYBACK_RESUME,
            Method.PLAYBACK_STOP, Method.PLAYBACK_SEEK, Method.PLAYBACK_SET_SPEED, Method.PLAYBACK_SET_VOLUME, Method.SESSION_TERMINATE)) {
            assertFalse(Role.OBSERVER.allows(Permissions.requiredFor(m)!!), "observer must not call $m")
            assertTrue(Role.CONTROLLER.allows(Permissions.requiredFor(m)!!), "controller must call $m")
        }
        for (m in listOf(Method.PING, Method.PLAYBACK_SNAPSHOT, Method.CAPABILITIES_GET)) {
            assertTrue(Role.OBSERVER.allows(Permissions.requiredFor(m)!!))
        }
        assertNull(Permissions.requiredFor("nope"))
    }

    private fun sender(id: SoftwareSenderIdentity = SoftwareSenderIdentity.generate(), role: Role = Role.CONTROLLER) =
        PairedSender(id.senderId, id.publicKeySpki, "Phone", role, 100L)

    @Test fun registryRevocationPersistsAndRepairingClearsIt() {
        val reg = InMemorySenderRegistry()
        val s = sender()
        reg.save(s)
        assertFalse(reg.find(s.senderId)!!.isRevoked)
        assertTrue(reg.revoke(s.senderId, 500L))
        assertTrue(reg.find(s.senderId)!!.isRevoked)
        assertEquals(500L, reg.find(s.senderId)!!.revokedAtMs)
        assertFalse(reg.revoke("unknown", 1))
        reg.markConnected(s.senderId, 700L)
        assertEquals(700L, reg.find(s.senderId)!!.lastConnectedAtMs)
        assertTrue(reg.find(s.senderId)!!.isRevoked, "markConnected must not clear revocation")
        reg.save(s)
        assertFalse(reg.find(s.senderId)!!.isRevoked)
    }

    @Test fun recordCodecRoundTripsAndRejectsCorruption() {
        val s = sender().let { PairedSender(it.senderId, it.publicKeySpki, "Living room phone", Role.OBSERVER, 5, 6, 7) }
        val decoded = PairedSenderJson.decode(PairedSenderJson.encode(s))!!
        assertEquals(s.senderId, decoded.senderId)
        assertContentEquals(s.publicKeySpki, decoded.publicKeySpki)
        assertEquals("Living room phone", decoded.name)
        assertEquals(Role.OBSERVER, decoded.role)
        assertEquals(5L, decoded.pairedAtMs); assertEquals(6L, decoded.lastConnectedAtMs); assertEquals(7L, decoded.revokedAtMs)
        val text = PairedSenderJson.encode(s)
        assertNull(PairedSenderJson.decode(text.dropLast(5)))
        assertNull(PairedSenderJson.decode("garbage"))
        assertNull(PairedSenderJson.decode("{}"))
        val other = SoftwareSenderIdentity.generate()
        val forged = text.replace(Bytes.b64(s.publicKeySpki), Bytes.b64(other.publicKeySpki))
        assertNull(PairedSenderJson.decode(forged))
        assertNull(PairedSenderJson.decode(text.replace("OBSERVER", "ROOT")))
    }
}
