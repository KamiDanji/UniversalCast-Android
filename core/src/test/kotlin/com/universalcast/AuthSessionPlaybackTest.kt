package com.universalcast

import com.universalcast.auth.*
import com.universalcast.config.*
import com.universalcast.discovery.*
import com.universalcast.identity.*
import com.universalcast.playback.*
import com.universalcast.security.*
import com.universalcast.session.*
import com.universalcast.ucp.*
import org.junit.Test
import kotlin.test.*

class AuthenticatorTest {
    private val time = FakeTimeSource()
    private val rfp = "a".repeat(64)
    private val auth = Authenticator(rfp, time)
    private val id = SoftwareSenderIdentity.generate()
    private val sender = PairedSender(id.senderId, id.publicKeySpki, "P", Role.CONTROLLER, 1)
    private val nonce = SecureRandoms.bytes(32)

    private fun answer(c: Challenge, signer: SenderIdentity = id) =
        signer.sign(AuthTranscripts.clientData(rfp, c.senderId, c.version, c.id, c.bytes, c.clientNonce))

    @Test fun challengesAreFreshAndRandom() {
        val a = auth.issue("c1", id.senderId, nonce, 1)
        val b = auth.issue("c1", id.senderId, nonce, 1)
        assertNotEquals(a.id, b.id)
        assertFalse(a.bytes.contentEquals(b.bytes))
        assertEquals(32, a.bytes.size)
        assertEquals(time.now + Ucp.CHALLENGE_TTL_MS, a.expiresAtMs)
    }

    @Test fun validResponseSucceeds() {
        val c = auth.issue("c1", id.senderId, nonce, 1)
        assertTrue(auth.verify("c1", c.id, answer(c), sender).success)
    }

    @Test fun challengeIsSingleUseAndReuseIsReportedAsReplay() {
        val c = auth.issue("c1", id.senderId, nonce, 1)
        val sig = answer(c)
        assertTrue(auth.verify("c1", c.id, sig, sender).success)
        assertEquals(AuthFailure.REPLAYED, auth.verify("c1", c.id, sig, sender).failure)
        assertTrue(auth.wasConsumed(c.id))
    }

    @Test fun failedAttemptBurnsTheChallenge() {
        val c = auth.issue("c1", id.senderId, nonce, 1)
        assertEquals(AuthFailure.BAD_SIGNATURE, auth.verify("c1", c.id, ByteArray(70), sender).failure)
        assertEquals(AuthFailure.REPLAYED, auth.verify("c1", c.id, answer(c), sender).failure, "no second chance after a failure")
    }

    @Test fun unknownChallengeRejected() {
        assertEquals(AuthFailure.UNKNOWN_CHALLENGE, auth.verify("c1", "nope", ByteArray(8), sender).failure)
    }

    @Test fun challengeCannotBeAnsweredOnAnotherConnection() {
        val c = auth.issue("c1", id.senderId, nonce, 1)
        assertEquals(AuthFailure.WRONG_CONNECTION, auth.verify("c2", c.id, answer(c), sender).failure)
    }

    @Test fun expiredChallengeRejected() {
        val c = auth.issue("c1", id.senderId, nonce, 1)
        time.advance(Ucp.CHALLENGE_TTL_MS)
        assertEquals(AuthFailure.EXPIRED, auth.verify("c1", c.id, answer(c), sender).failure)
    }

    @Test fun signatureFromWrongKeyRejected() {
        val c = auth.issue("c1", id.senderId, nonce, 1)
        assertEquals(AuthFailure.BAD_SIGNATURE, auth.verify("c1", c.id, answer(c, SoftwareSenderIdentity.generate()), sender).failure)
    }

    @Test fun signatureOverDifferentReceiverFingerprintRejected() {
        val c = auth.issue("c1", id.senderId, nonce, 1)
        val sig = id.sign(AuthTranscripts.clientData("b".repeat(64), c.senderId, c.version, c.id, c.bytes, c.clientNonce))
        assertEquals(AuthFailure.BAD_SIGNATURE, auth.verify("c1", c.id, sig, sender).failure)
    }

    @Test fun revokedOrMismatchedSenderRejected() {
        val c = auth.issue("c1", id.senderId, nonce, 1)
        assertEquals(AuthFailure.REVOKED, auth.verify("c1", c.id, answer(c), sender.copy(revokedAtMs = 5)).failure)
        val c2 = auth.issue("c1", id.senderId, nonce, 1)
        val other = SoftwareSenderIdentity.generate()
        assertEquals(AuthFailure.SENDER_MISMATCH, auth.verify("c1", c2.id, answer(c2), PairedSender(other.senderId, other.publicKeySpki, "x", Role.CONTROLLER, 1)).failure)
        val c3 = auth.issue("c1", id.senderId, nonce, 1)
        assertEquals(AuthFailure.SENDER_MISMATCH, auth.verify("c1", c3.id, answer(c3), null).failure)
    }

    @Test fun forgetConnectionDropsItsChallenges() {
        val c = auth.issue("c1", id.senderId, nonce, 1)
        auth.forgetConnection("c1")
        assertEquals(AuthFailure.UNKNOWN_CHALLENGE, auth.verify("c1", c.id, answer(c), sender).failure)
    }
}

class SessionManagerTest {
    private val mgr = SessionManager(FakeTimeSource())
    private val ended = ArrayList<Pair<String, SessionEndReason>>()

    init {
        mgr.addListener { s, r -> ended.add(s.id to r) }
    }

    @Test fun createValidateTerminate() {
        val r = mgr.create("c1", "s1")
        assertNull(r.replaced)
        assertEquals(r.session.id, mgr.validate(r.session.id, "c1").id)
        assertEquals(ErrorCode.SESSION_REQUIRED, assertFailsWith<UcpException> { mgr.validate(null, "c1") }.code)
        assertEquals(ErrorCode.SESSION_INVALID, assertFailsWith<UcpException> { mgr.validate("other", "c1") }.code)
        assertEquals(ErrorCode.SESSION_INVALID, assertFailsWith<UcpException> { mgr.validate(r.session.id, "c2") }.code, "foreign connection")
        assertFalse(mgr.terminate(r.session.id, "c2"))
        assertTrue(mgr.terminate(r.session.id, "c1"))
        assertNull(mgr.active())
        assertEquals(listOf(r.session.id to SessionEndReason.TERMINATED), ended)
    }

    @Test fun newSessionReplacesOld() {
        val a = mgr.create("c1", "s1").session
        val b = mgr.create("c2", "s2")
        assertEquals(a.id, b.replaced!!.id)
        assertEquals(b.session.id, mgr.active()!!.id)
        assertEquals(listOf(a.id to SessionEndReason.REPLACED), ended)
        assertFailsWith<UcpException> { mgr.validate(a.id, "c1") }
    }

    @Test fun connectionCloseEndsOnlyItsOwnSession() {
        val a = mgr.create("c1", "s1").session
        assertNull(mgr.endForConnection("c2", SessionEndReason.CONNECTION_CLOSED))
        assertEquals(a.id, mgr.endForConnection("c1", SessionEndReason.CONNECTION_CLOSED)!!.id)
        assertNull(mgr.active())
    }

    @Test fun sessionIdsAreUnique() {
        assertEquals(50, (1..50).map { mgr.create("c", "s").session.id }.toSet().size)
    }
}

class PlaybackModelTest {
    @Test fun statusDerivationTable() {
        fun d(s: EngineState, play: Boolean = false, media: Boolean = true, err: Boolean = false, ready: Boolean = false, started: Boolean = false) =
            PlaybackStatusDeriver.derive(s, play, media, err, ready, started)
        assertEquals(PlaybackStatus.IDLE, d(EngineState.IDLE, media = false))
        assertEquals(PlaybackStatus.IDLE, d(EngineState.READY, media = false))
        assertEquals(PlaybackStatus.LOADING, d(EngineState.IDLE))
        assertEquals(PlaybackStatus.LOADING, d(EngineState.BUFFERING))
        assertEquals(PlaybackStatus.BUFFERING, d(EngineState.BUFFERING, ready = true))
        assertEquals(PlaybackStatus.READY, d(EngineState.READY))
        assertEquals(PlaybackStatus.PLAYING, d(EngineState.READY, play = true))
        assertEquals(PlaybackStatus.PAUSED, d(EngineState.READY, started = true))
        assertEquals(PlaybackStatus.ENDED, d(EngineState.ENDED, play = true))
        assertEquals(PlaybackStatus.ERROR, d(EngineState.READY, play = true, err = true))
        assertEquals(PlaybackStatus.ERROR, d(EngineState.IDLE, media = false, err = true))
    }

    @Test fun snapshotJsonRoundTrip() {
        val s = PlaybackSnapshot(PlaybackStatus.PAUSED, "https://x/y.mp4", "T", 1234, 99999, 5000, false, 1.5f, 0.5f, 42,
            PlaybackErrorInfo("E", "msg", true), 777)
        val back = PlaybackSnapshot.fromJson(Fields(StrictJson.parse(StrictJson.stringify(s.toJson())) as Map<String, Any?>))
        assertEquals(s, back)
        val noDuration = PlaybackSnapshot()
        assertEquals(noDuration, PlaybackSnapshot.fromJson(Fields(StrictJson.parse(StrictJson.stringify(noDuration.toJson())) as Map<String, Any?>)))
    }

    @Test fun mediaUriValidation() {
        for (ok in listOf("http://a.b/c.mp4", "https://host:8080/x.m3u8?a=b", "HTTPS://h/x", "https://[::1]/x.mpd")) MediaUriValidator.validateUri(ok)
        for (bad in listOf("file:///etc/passwd", "content://x/y", "javascript:alert(1)", "ftp://h/x", "rtsp://h/x", "http://", "https:///x", "not a uri",
            "https://h/with space", "https://h/\u0000", "x".repeat(3000), "//h/x", "")) {
            assertFailsWith<IllegalArgumentException>("should reject ${bad.take(20)}") { MediaUriValidator.validateUri(bad) }
        }
        MediaUriValidator.validateMime("video/mp4"); MediaUriValidator.validateMime("application/vnd.apple.mpegurl")
        for (bad in listOf("video", "video/", "/mp4", "video/mp4; x=y", "a b/c")) assertFailsWith<IllegalArgumentException> { MediaUriValidator.validateMime(bad) }
    }
}

class ConfigAndDiscoveryTest {
    private val identity = TestPki.receiverIdentity()

    @Test fun productionRejectsInsecureConfiguration() {
        ConfigValidator.requireValid(NetworkConfig())
        assertFailsWith<IllegalStateException> { ConfigValidator.requireValid(NetworkConfig(tlsEnabled = false)) }
        assertFailsWith<IllegalStateException> { ConfigValidator.requireValid(NetworkConfig(requireAuthentication = false)) }
        assertFailsWith<IllegalStateException> { ConfigValidator.requireValid(NetworkConfig(requirePairedSenders = false)) }
        assertFailsWith<IllegalStateException> { ConfigValidator.requireValid(NetworkConfig(port = 0)) }
        assertFailsWith<IllegalStateException> { ConfigValidator.requireValid(NetworkConfig(port = 70000)) }
    }

    @Test fun developmentModeStillRefusesPlaintext() {
        ConfigValidator.requireValid(NetworkConfig(mode = OperatingMode.DEVELOPMENT, port = 0))
        assertFailsWith<IllegalStateException> { ConfigValidator.requireValid(NetworkConfig(mode = OperatingMode.DEVELOPMENT, tlsEnabled = false)) }
        assertFailsWith<IllegalStateException> { ConfigValidator.requireValid(NetworkConfig(mode = OperatingMode.DEVELOPMENT, requireAuthentication = false)) }
    }

    @Test fun txtRecordsAdvertiseTheSecureEndpoint() {
        val ad = DiscoveryRecords.forReceiver(identity, NetworkConfig(), "Living Room", 8443)
        assertEquals("_universalcast._tcp", ad.serviceType)
        assertEquals(8443, ad.port)
        assertEquals("1", ad.txt["ucp"]); assertEquals(identity.deviceId, ad.txt["id"]); assertEquals("/ucp", ad.txt["path"])
        assertEquals("1", ad.txt["tls"]); assertEquals("production", ad.txt["mode"])
        assertEquals("development", DiscoveryRecords.forReceiver(identity, NetworkConfig(mode = OperatingMode.DEVELOPMENT), "x", 1).txt["mode"])
        assertFailsWith<IllegalStateException> { DiscoveryRecords.forReceiver(identity, NetworkConfig(tlsEnabled = false), "x", 8443) }
        assertTrue(DiscoveryRecords.forReceiver(identity, NetworkConfig(), "é".repeat(100), 1).serviceName.toByteArray().size <= 63)
        assertEquals("UniversalCast Receiver", DiscoveryRecords.forReceiver(identity, NetworkConfig(), "  ", 1).serviceName)
    }

    @Test fun controllerNeverCreatesDuplicateAdvertisements() {
        val fake = FakeAdvertiser()
        val ctl = AdvertisementController(fake)
        val ad = DiscoveryRecords.forReceiver(identity, NetworkConfig(), "TV", 8443)
        ctl.ensure(ad); ctl.ensure(ad); ctl.ensure(ad)
        assertEquals(1, fake.starts); assertEquals(0, fake.stops)
        ctl.ensure(ad.copy(port = 9000))
        assertEquals(2, fake.starts); assertEquals(1, fake.stops)
        assertTrue(fake.isAdvertising)
        ctl.stop(); ctl.stop()
        assertEquals(2, fake.stops); assertFalse(fake.isAdvertising)
    }
}
