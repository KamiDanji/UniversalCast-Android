package com.universalcast

import com.universalcast.auth.AuthTranscripts
import com.universalcast.config.ReceiverTimeouts
import com.universalcast.identity.Role
import com.universalcast.pairing.PairingBootstrap
import com.universalcast.pairing.PairingState
import com.universalcast.playback.PlaybackStatus
import com.universalcast.security.Bytes
import com.universalcast.security.SecureRandoms
import com.universalcast.security.SoftwareSenderIdentity
import com.universalcast.sender.PairingClient
import com.universalcast.sender.UcpClient
import com.universalcast.sender.UcpClientException
import com.universalcast.ucp.ErrorCode
import com.universalcast.ucp.Fields
import com.universalcast.ucp.Method
import org.junit.Test
import kotlin.test.*

/** End-to-end tests over a real TLS 1.2/1.3 WebSocket on loopback, with the real pairing and auth code on both sides. */
class IntegrationTest {
    private inline fun withEnv(timeouts: ReceiverTimeouts = ReceiverTimeouts(), block: (Env) -> Unit) {
        val env = Env(timeouts)
        try {
            block(env)
        } finally {
            env.close()
        }
    }

    private fun UcpClient.code(method: String, payload: Map<String, Any?> = emptyMap(), session: String? = null) =
        request(method, payload, session).error?.code

    private fun snapshotStatus(r: com.universalcast.ucp.Envelope) =
        Fields(Fields(r.payload).obj("snapshot").raw).string("status")

    // ---- pairing -------------------------------------------------------------------------------------

    @Test fun pairingEndToEndPersistsSenderAndBothSidesSeeSameSas() = withEnv { env ->
        val sender = SoftwareSenderIdentity.generate()
        val result = env.pair(sender, Role.CONTROLLER, "Alice's Phone")
        assertEquals("approved", result.status)
        assertEquals("CONTROLLER", result.role)
        assertTrue(result.macValid, "sender can verify the receiver's result MAC")
        val saved = env.registry.find(sender.senderId)!!
        assertEquals("Alice's Phone", saved.name)
        assertEquals(Role.CONTROLLER, saved.role)
        assertEquals(PairingState.APPROVED, env.core.pairing.view()!!.state)
    }

    @Test fun rejectedPairingLeavesNoTrace() = withEnv { env ->
        val sender = SoftwareSenderIdentity.generate()
        val pc = PairingClient(sender, env.core.startPairing(), "Phone")
        pc.begin(); pc.confirm()
        assertTrue(env.core.pairing.userConfirmSas(true))
        assertTrue(env.core.pairing.reject())
        assertEquals("rejected", pc.awaitResult()!!.status)
        assertNull(env.registry.find(sender.senderId))
        val c = env.client(sender); assertTrue(c.connect())
        assertEquals(ErrorCode.AUTH_FAILED, assertFailsWith<UcpClientException> { c.authenticate() }.code)
    }

    @Test fun sasMismatchOnReceiverAbortsPairing() = withEnv { env ->
        val sender = SoftwareSenderIdentity.generate()
        val pc = PairingClient(sender, env.core.startPairing(), "Phone")
        pc.begin(); pc.confirm()
        assertTrue(env.core.pairing.userConfirmSas(false))
        assertEquals("failed", pc.awaitResult()!!.status)
        assertNull(env.registry.find(sender.senderId))
    }

    @Test fun pairingExpiresAfterTheWindow() = withEnv(ReceiverTimeouts(pairingWindowMs = 400)) { env ->
        val sender = SoftwareSenderIdentity.generate()
        val bootstrap = env.core.startPairing()
        Thread.sleep(700)
        val pc = PairingClient(sender, bootstrap, "Phone")
        assertEquals(ErrorCode.PAIRING_EXPIRED, assertFailsWith<UcpClientException> { pc.begin() }.code)
        assertNull(env.registry.find(sender.senderId))
    }

    @Test fun waitingSenderIsToldWhenTheWindowExpires() = withEnv(ReceiverTimeouts(pairingWindowMs = 1500)) { env ->
        val sender = SoftwareSenderIdentity.generate()
        val pc = PairingClient(sender, env.core.startPairing(), "Phone")
        pc.begin()
        assertEquals("expired", pc.awaitResult(6_000)!!.status)
    }

    @Test fun pairingWithWrongQrSecretIsRejected() = withEnv { env ->
        val good = env.core.startPairing()
        val forged = PairingBootstrap(good.deviceId, good.deviceName, good.fingerprint, good.pairingId, SecureRandoms.bytes(32), good.expiresAtMs, good.addresses, good.port)
        val sender = SoftwareSenderIdentity.generate()
        assertEquals(ErrorCode.PAIRING_INVALID_PROOF, assertFailsWith<UcpClientException> { PairingClient(sender, forged, "Phone").begin() }.code)
        assertNull(env.registry.find(sender.senderId))
    }

    @Test fun pairingWithWrongFingerprintFailsAtTlsPin() = withEnv { env ->
        val good = env.core.startPairing()
        val forged = PairingBootstrap(good.deviceId, good.deviceName, "0".repeat(64), good.pairingId, good.secret, good.expiresAtMs, good.addresses, good.port)
        assertFailsWith<UcpClientException> { PairingClient(SoftwareSenderIdentity.generate(), forged, "Phone").begin() }
    }

    // ---- TLS ----------------------------------------------------------------------------------------

    @Test fun incorrectCertificateFingerprintIsRefusedByThePin() = withEnv { env ->
        val c = env.client(SoftwareSenderIdentity.generate(), fingerprint = "ab".repeat(32))
        assertFalse(c.connect(3_000), "must not connect to a receiver whose key differs from the pinned fingerprint")
        assertNull(c.peerSpki)
    }

    @Test fun correctFingerprintConnectsAndExposesReceiverKey() = withEnv { env ->
        val c = env.client(SoftwareSenderIdentity.generate())
        assertTrue(c.connect())
        assertContentEquals(env.identity.publicKeySpki, c.peerSpki)
    }

    @Test fun plaintextClientsAreNotServed() = withEnv { env ->
        java.net.Socket("127.0.0.1", env.port).use { s ->
            s.soTimeout = 3_000
            s.getOutputStream().write(
                "GET /ucp HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n".toByteArray()
            )
            val buf = ByteArray(512)
            val n = try { s.getInputStream().read(buf) } catch (e: Exception) { -1 }
            val reply = if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1) else ""
            assertFalse(reply.contains("101 Switching"), "no plaintext WebSocket upgrade may ever succeed")
            assertFalse(reply.startsWith("HTTP/"))
        }
    }

    @Test fun wrongPathIsRejected() = withEnv { env ->
        val sender = SoftwareSenderIdentity.generate()
        val c = env.client(sender)
        if (c.connect(3_000, path = "/other")) {
            waitFor(what = "close for wrong path") { c.closeCode != null }
            assertEquals(1008, c.closeCode)
        }
    }

    // ---- authentication ---------------------------------------------------------------------------

    @Test fun authenticationEndToEndVerifiesBothSides() = withEnv { env ->
        val (_, c) = env.pairedLogin(Role.CONTROLLER)
        assertEquals("CONTROLLER", Fields(c.call(Method.CAPABILITIES_GET).payload).string("role"))
        waitFor { env.core.connectedSenders().size == 1 }
        assertNotNull(env.registry.all().single().lastConnectedAtMs)
    }

    @Test fun pairedIdentityAloneIsNotEnoughToUseTheConnection() = withEnv { env ->
        val sender = SoftwareSenderIdentity.generate()
        env.pair(sender, Role.CONTROLLER)
        val c = env.client(sender); assertTrue(c.connect())
        assertEquals(ErrorCode.UNAUTHENTICATED, c.code(Method.PLAYBACK_SNAPSHOT))
        waitFor { c.closeCode == 1008 }
        assertTrue(env.engine.calls.isEmpty())
    }

    @Test fun unpairedSenderIsRejected() = withEnv { env ->
        val c = env.client(SoftwareSenderIdentity.generate()); assertTrue(c.connect())
        assertEquals(ErrorCode.AUTH_FAILED, assertFailsWith<UcpClientException> { c.authenticate() }.code)
        waitFor { c.closeCode == 4001 }
    }

    private fun manualHello(c: UcpClient, sender: SoftwareSenderIdentity): Triple<String, ByteArray, ByteArray> {
        val nonce = SecureRandoms.bytes(32)
        val f = Fields(c.call(Method.AUTH_HELLO, mapOf("versions" to listOf(1L), "senderId" to sender.senderId, "clientNonce" to Bytes.b64(nonce))).payload)
        return Triple(f.string("challengeId"), f.bytes("challenge"), nonce)
    }

    @Test fun invalidAuthenticationResponseIsRejected() = withEnv { env ->
        val sender = SoftwareSenderIdentity.generate(); env.pair(sender, Role.CONTROLLER)
        val c = env.client(sender); assertTrue(c.connect())
        val (cid, _, _) = manualHello(c, sender)
        assertEquals(ErrorCode.AUTH_FAILED, c.code(Method.AUTH_RESPONSE, mapOf("challengeId" to cid, "signature" to Bytes.b64(ByteArray(64) { 7 }))))
        waitFor { c.closeCode == 4001 }
    }

    @Test fun responseSignedByAnotherKeyIsRejected() = withEnv { env ->
        val sender = SoftwareSenderIdentity.generate(); env.pair(sender, Role.CONTROLLER)
        val thief = SoftwareSenderIdentity.generate()
        val c = env.client(sender); assertTrue(c.connect())
        val (cid, ch, nonce) = manualHello(c, sender)
        val sig = thief.sign(AuthTranscripts.clientData(env.identity.fingerprint, sender.senderId, 1, cid, ch, nonce))
        assertEquals(ErrorCode.AUTH_FAILED, c.code(Method.AUTH_RESPONSE, mapOf("challengeId" to cid, "signature" to Bytes.b64(sig))))
    }

    @Test fun replayedChallengeResponseOnANewConnectionIsRejected() = withEnv { env ->
        val sender = SoftwareSenderIdentity.generate(); env.pair(sender, Role.CONTROLLER)
        val c1 = env.client(sender); assertTrue(c1.connect())
        val (cid1, ch1, n1) = manualHello(c1, sender)
        val sig1 = Bytes.b64(sender.sign(AuthTranscripts.clientData(env.identity.fingerprint, sender.senderId, 1, cid1, ch1, n1)))
        assertEquals(true, c1.request(Method.AUTH_RESPONSE, mapOf("challengeId" to cid1, "signature" to sig1)).ok)
        val c2 = env.client(sender); assertTrue(c2.connect())
        manualHello(c2, sender)
        assertEquals(ErrorCode.REPLAY_DETECTED, c2.code(Method.AUTH_RESPONSE, mapOf("challengeId" to cid1, "signature" to sig1)))
        waitFor { c2.closeCode == 4001 }
        val c3 = env.client(sender); assertTrue(c3.connect())
        val (cid3, _, _) = manualHello(c3, sender)
        assertEquals(ErrorCode.AUTH_FAILED, c3.code(Method.AUTH_RESPONSE, mapOf("challengeId" to cid3, "signature" to sig1)))
    }

    @Test fun reusedChallengeOnTheSameConnectionIsRejected() = withEnv { env ->
        val sender = SoftwareSenderIdentity.generate(); env.pair(sender, Role.CONTROLLER)
        val c = env.client(sender); assertTrue(c.connect())
        val (cid, ch, n) = manualHello(c, sender)
        val sig = Bytes.b64(sender.sign(AuthTranscripts.clientData(env.identity.fingerprint, sender.senderId, 1, cid, ch, n)))
        assertEquals(true, c.request(Method.AUTH_RESPONSE, mapOf("challengeId" to cid, "signature" to sig)).ok)
        assertEquals(ErrorCode.REPLAY_DETECTED, c.code(Method.AUTH_RESPONSE, mapOf("challengeId" to cid, "signature" to sig)))
        waitFor { c.closeCode == 4001 }
    }

    @Test fun unauthenticatedConnectionsAreClosedAfterTheTimeout() = withEnv(ReceiverTimeouts(authTimeoutMs = 500)) { env ->
        val c = env.client(SoftwareSenderIdentity.generate()); assertTrue(c.connect())
        waitFor(6_000, "auth timeout close") { c.closeCode != null }
        assertEquals(4002, c.closeCode)
    }

    // ---- permissions -------------------------------------------------------------------------------

    @Test fun controllerFullPlaybackFlowWithStateSynchronisation() = withEnv { env ->
        val (_, c) = env.pairedLogin(Role.CONTROLLER)
        val (_, observer) = env.pairedLogin(Role.OBSERVER)
        val sid = Fields(c.call(Method.SESSION_CREATE).payload).string("sessionId")
        val loaded = c.call(Method.MEDIA_LOAD, mapOf("uri" to "https://example.org/video.mp4", "title" to "Clip"), sid)
        assertEquals("PLAYING", snapshotStatus(loaded))
        val ev = c.awaitEvent("playback.stateChanged")!!
        assertEquals("PLAYING", Fields(Fields(ev.payload).obj("snapshot").raw).string("status"))
        assertNotNull(observer.awaitEvent("playback.stateChanged"))
        assertEquals("PAUSED", snapshotStatus(c.call(Method.PLAYBACK_PAUSE, session = sid)))
        assertEquals("PLAYING", snapshotStatus(c.call(Method.PLAYBACK_RESUME, session = sid)))
        val seeked = c.call(Method.PLAYBACK_SEEK, mapOf("positionMs" to 12_345L), sid)
        assertEquals(12_345L, Fields(Fields(seeked.payload).obj("snapshot").raw).long("positionMs", 0, Long.MAX_VALUE))
        c.call(Method.PLAYBACK_SET_SPEED, mapOf("speed" to 2.0), sid)
        c.call(Method.PLAYBACK_SET_VOLUME, mapOf("volume" to 0.5), sid)
        val snap = observer.snapshot()
        assertEquals(PlaybackStatus.PLAYING, snap.status)
        assertEquals(12_345L, snap.positionMs); assertEquals(2f, snap.speed); assertEquals(0.5f, snap.volume)
        assertEquals("https://example.org/video.mp4", snap.mediaUri)
        assertEquals(env.engine.snapshot.revision, snap.revision)
        assertEquals("IDLE", snapshotStatus(c.call(Method.PLAYBACK_STOP, session = sid)))
        assertEquals(true, c.request(Method.SESSION_TERMINATE, session = sid).ok)
        assertEquals(ErrorCode.SESSION_INVALID, c.code(Method.PLAYBACK_PLAY, session = sid))
        assertNotNull(c.call(Method.PING, mapOf("nonce" to "n1")).payload["serverTimeMs"])
    }

    @Test fun observerCannotControlPlaybackEvenWithAStolenSessionId() = withEnv { env ->
        val (_, controller) = env.pairedLogin(Role.CONTROLLER)
        val (_, observer) = env.pairedLogin(Role.OBSERVER)
        val sid = Fields(controller.call(Method.SESSION_CREATE).payload).string("sessionId")
        controller.call(Method.MEDIA_LOAD, mapOf("uri" to "https://example.org/a.mp4"), sid)
        val before = env.engine.calls.toList()
        assertEquals(ErrorCode.FORBIDDEN, observer.code(Method.SESSION_CREATE))
        assertEquals(ErrorCode.FORBIDDEN, observer.code(Method.MEDIA_LOAD, mapOf("uri" to "https://evil.example/x.mp4"), sid))
        assertEquals(ErrorCode.FORBIDDEN, observer.code(Method.PLAYBACK_PAUSE, session = sid))
        assertEquals(ErrorCode.FORBIDDEN, observer.code(Method.PLAYBACK_STOP, session = sid))
        assertEquals(ErrorCode.FORBIDDEN, observer.code(Method.PLAYBACK_SEEK, mapOf("positionMs" to 0L), sid))
        assertEquals(before, env.engine.calls.toList())
        assertEquals(true, observer.request(Method.PING).ok)
    }

    @Test fun sessionOwnershipAndTakeover() = withEnv { env ->
        val (_, a) = env.pairedLogin(Role.CONTROLLER)
        val (_, b) = env.pairedLogin(Role.CONTROLLER)
        val sa = Fields(a.call(Method.SESSION_CREATE).payload).string("sessionId")
        a.call(Method.MEDIA_LOAD, mapOf("uri" to "https://example.org/a.mp4"), sa)
        assertEquals(ErrorCode.SESSION_INVALID, b.code(Method.PLAYBACK_PAUSE, session = sa))
        val sb = Fields(b.call(Method.SESSION_CREATE).payload).string("sessionId")
        val ev = a.awaitEvent("session.terminated")!!
        assertEquals("REPLACED", ev.payload["reason"])
        assertEquals(ErrorCode.SESSION_INVALID, a.code(Method.PLAYBACK_PAUSE, session = sa))
        assertEquals(true, b.request(Method.PLAYBACK_PAUSE, session = sb).ok)
    }

    @Test fun droppingTheConnectionReleasesThePlayer() = withEnv { env ->
        val (_, c) = env.pairedLogin(Role.CONTROLLER)
        val sid = Fields(c.call(Method.SESSION_CREATE).payload).string("sessionId")
        c.call(Method.MEDIA_LOAD, mapOf("uri" to "https://example.org/a.mp4"), sid)
        c.close()
        waitFor(what = "engine stop after disconnect") { env.engine.calls.contains("stop") }
        assertNull(env.core.sessions.active())
        waitFor { env.core.connectedSenders().isEmpty() }
    }

    // ---- revocation -----------------------------------------------------------------------------------

    @Test fun revocationClosesActiveConnectionsAndBlocksReconnection() = withEnv { env ->
        val (sender, c) = env.pairedLogin(Role.CONTROLLER)
        val sid = Fields(c.call(Method.SESSION_CREATE).payload).string("sessionId")
        c.call(Method.MEDIA_LOAD, mapOf("uri" to "https://example.org/a.mp4"), sid)
        assertTrue(env.core.revokeSender(sender.senderId))
        waitFor(what = "connection closed by revocation") { c.closeCode != null }
        assertEquals(4003, c.closeCode)
        assertTrue(env.registry.find(sender.senderId)!!.isRevoked, "revocation is persisted in the registry")
        assertTrue(env.engine.calls.contains("stop"))
        waitFor { env.core.connectedSenders().isEmpty() }
        val again = env.client(sender); assertTrue(again.connect())
        assertEquals(ErrorCode.AUTH_FAILED, assertFailsWith<UcpClientException> { again.authenticate() }.code)
        env.core.stop(); env.core.start()
        val third = env.client(sender); assertTrue(third.connect())
        assertEquals(ErrorCode.AUTH_FAILED, assertFailsWith<UcpClientException> { third.authenticate() }.code)
    }

    @Test fun revokingOneSenderDoesNotAffectAnother() = withEnv { env ->
        val (s1, c1) = env.pairedLogin(Role.CONTROLLER)
        val (_, c2) = env.pairedLogin(Role.CONTROLLER)
        env.core.revokeSender(s1.senderId)
        waitFor { c1.closeCode != null }
        assertEquals(true, c2.request(Method.PING).ok)
    }

    @Test fun revokedSenderCanOnlyReturnThroughANewExplicitPairing() = withEnv { env ->
        val sender = SoftwareSenderIdentity.generate()
        env.pair(sender, Role.CONTROLLER)
        env.core.revokeSender(sender.senderId)
        val c = env.client(sender); assertTrue(c.connect())
        assertFailsWith<UcpClientException> { c.authenticate() }
        env.pair(sender, Role.OBSERVER)
        val again = env.login(sender)
        assertEquals("OBSERVER", Fields(again.call(Method.CAPABILITIES_GET).payload).string("role"))
    }

    // ---- malformed input / abuse ------------------------------------------------------------------------

    @Test fun malformedMessagesNeverCrashTheReceiver() = withEnv { env ->
        val (_, c) = env.pairedLogin(Role.CONTROLLER)
        for (bad in listOf("{not json", "[]", "null", "{\"version\":1}", "\"str\"", "{\"version\":1,\"type\":\"request\",\"id\":\"x\",\"seq\":1}")) c.sendRaw(bad)
        waitFor(what = "error responses") { c.rawMessages.count { it.contains("MALFORMED_MESSAGE") } >= 4 }
        waitFor(6_000, "closed after repeated abuse") { c.closeCode != null }
        val (_, other) = env.pairedLogin(Role.CONTROLLER)
        assertEquals(true, other.request(Method.PING).ok)
    }

    @Test fun oversizedMessageIsRefusedAndConnectionClosed() = withEnv { env ->
        val (_, c) = env.pairedLogin(Role.CONTROLLER)
        c.sendRaw("{\"version\":1,\"type\":\"request\",\"id\":\"big\",\"seq\":99,\"method\":\"ping\",\"payload\":{\"nonce\":\"" + "x".repeat(120_000) + "\"}}")
        waitFor(6_000, "close after oversized message") { c.closeCode != null }
        assertEquals(1009, c.closeCode)
        assertEquals(true, env.login(SoftwareSenderIdentity.generate().also { env.pair(it, Role.OBSERVER) }).request(Method.PING).ok)
    }

    @Test fun binaryFramesAreRefused() = withEnv { env ->
        val (_, c) = env.pairedLogin(Role.CONTROLLER)
        c.sendRawBinary(ByteArray(10) { it.toByte() })
        waitFor { c.closeCode == 1003 }
    }

    @Test fun sequenceAndDuplicateProtectionOverTheWire() = withEnv { env ->
        val (_, c) = env.pairedLogin(Role.CONTROLLER)
        val sid = Fields(c.call(Method.SESSION_CREATE).payload).string("sessionId")
        assertEquals(ErrorCode.SEQUENCE_ERROR, c.request(Method.PING, seqOverride = 1).error?.code)
        val first = c.request(Method.MEDIA_LOAD, mapOf("uri" to "https://example.org/a.mp4"), sid, id = "once")
        val second = c.request(Method.MEDIA_LOAD, mapOf("uri" to "https://example.org/a.mp4"), sid, id = "once")
        assertEquals(first.ok, second.ok); assertEquals(first.payload, second.payload)
        assertEquals(1, env.engine.calls.count { it.startsWith("load:") }, "duplicate request id must not execute twice")
    }

    @Test fun unsupportedMediaUrisAreRefusedOverTheWire() = withEnv { env ->
        val (_, c) = env.pairedLogin(Role.CONTROLLER)
        val sid = Fields(c.call(Method.SESSION_CREATE).payload).string("sessionId")
        assertEquals(ErrorCode.UNSUPPORTED_MEDIA, c.code(Method.MEDIA_LOAD, mapOf("uri" to "file:///data/secret.mp4"), sid))
        assertTrue(env.engine.calls.isEmpty())
    }

    // ---- service lifecycle ------------------------------------------------------------------------------

    @Test fun serviceStartupShutdownRestartAndAdvertisement() = withEnv { env ->
        assertTrue(env.core.isRunning)
        assertEquals(1, env.advertiser.starts)
        val ad = env.advertiser.last!!
        assertEquals(env.port, ad.port); assertEquals("1", ad.txt["tls"]); assertEquals("development", ad.txt["mode"]); assertEquals(env.identity.deviceId, ad.txt["id"])
        env.core.advertise(); env.core.advertise()
        assertEquals(1, env.advertiser.starts, "repeat advertise must not create duplicates")
        val sender = SoftwareSenderIdentity.generate(); env.pair(sender, Role.CONTROLLER)
        env.core.stop()
        assertFalse(env.core.isRunning); assertEquals(1, env.advertiser.stops); assertFalse(env.advertiser.isAdvertising)
        val dead = env.client(sender, env.identity.fingerprint)
        assertFalse(runCatching { dead.connect(1_000) }.getOrDefault(false), "nothing listens after stop")
        env.core.stop()
        env.core.start()
        assertTrue(env.core.isRunning); assertEquals(2, env.advertiser.starts)
        env.login(sender)
    }

    @Test fun startingTwiceDoesNotRebind() = withEnv { env ->
        val port = env.port
        env.core.start()
        assertEquals(port, env.port)
    }
}
