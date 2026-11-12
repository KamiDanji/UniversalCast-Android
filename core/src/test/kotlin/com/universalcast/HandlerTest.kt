package com.universalcast

import com.universalcast.auth.AuthTranscripts
import com.universalcast.auth.Authenticator
import com.universalcast.config.ReceiverTimeouts
import com.universalcast.core.DiagnosticsLog
import com.universalcast.identity.*
import com.universalcast.network.ConnectedSender
import com.universalcast.network.UcpProtocolHandler
import com.universalcast.pairing.PairingManager
import com.universalcast.security.*
import com.universalcast.session.SessionManager
import com.universalcast.ucp.*
import org.junit.Test
import kotlin.test.*

/** Exercises the protocol state machine directly (no sockets), with a controllable clock. */
class HandlerTest {
    private val time = FakeTimeSource()
    private val identity = TestPki.receiverIdentity()
    private val registry = InMemorySenderRegistry()
    private val engine = FakePlaybackEngine()
    private val sessions = SessionManager(time)
    private val pairing = PairingManager(identity, registry, time, { emptyList() }, { 1 }, { "TV" })
    private val handler = UcpProtocolHandler(
        identity, registry, engine, sessions, pairing, Authenticator(identity.fingerprint, time), time, ReceiverTimeouts(), DiagnosticsLog()
    ) { "TV" }
    private val connected = ArrayList<List<ConnectedSender>>()

    init {
        handler.attach()
        handler.addConnectionsListener { connected.add(it) }
    }

    private inner class Peer(val sender: SoftwareSenderIdentity?) {
        val conn = FakeConnection()
        var seq = 0L
        var session: String? = null

        init {
            handler.onOpen(conn)
        }

        fun last(): Envelope = EnvelopeCodec.decode(conn.sent.last())

        fun call(method: String, payload: JsonObject = emptyMap(), id: String? = null, sessionId: String? = session, seqNo: Long = ++seq): Envelope {
            handler.onText(conn, EnvelopeCodec.encode(Envelope.request(id ?: "r$seqNo-$method", seqNo, method, payload, sessionId)))
            return last()
        }

        fun login(): Peer {
            val s = sender!!
            val nonce = SecureRandoms.bytes(32)
            val h = call(Method.AUTH_HELLO, mapOf("versions" to listOf(1L), "senderId" to s.senderId, "clientNonce" to Bytes.b64(nonce)))
            assertEquals(true, h.ok, h.error?.message)
            val f = Fields(h.payload)
            val cid = f.string("challengeId"); val ch = f.bytes("challenge")
            val sig = s.sign(AuthTranscripts.clientData(identity.fingerprint, s.senderId, 1, cid, ch, nonce))
            val r = call(Method.AUTH_RESPONSE, mapOf("challengeId" to cid, "signature" to Bytes.b64(sig)))
            assertEquals(true, r.ok, r.error?.message)
            return this
        }

        fun startSession(): Peer {
            session = Fields(call(Method.SESSION_CREATE).payload).string("sessionId")
            return this
        }
    }

    private fun paired(role: Role = Role.CONTROLLER): Peer {
        val id = SoftwareSenderIdentity.generate()
        registry.save(PairedSender(id.senderId, id.publicKeySpki, "Phone", role, 1))
        return Peer(id).login()
    }

    private fun Envelope.code() = error!!.code

    @Test fun oversizedMessageClosesConnection() {
        val p = Peer(null)
        handler.onText(p.conn, "x".repeat(Ucp.MAX_MESSAGE_BYTES + 1))
        assertEquals(1009, p.conn.closeCode)
        assertEquals(ErrorCode.MESSAGE_TOO_LARGE, p.last().code())
    }

    @Test fun exactlyAtLimitIsAcceptedAsMessage() {
        val p = paired()
        val pad = "a".repeat(Ucp.MAX_MESSAGE_BYTES - 200)
        val r = p.call(Method.PING, mapOf("nonce" to pad))
        assertEquals(ErrorCode.INVALID_PAYLOAD, r.code())
        assertNull(p.conn.closeCode)
    }

    @Test fun malformedBeforeAuthenticationClosesImmediately() {
        val p = Peer(null)
        handler.onText(p.conn, "{{{")
        assertEquals(ErrorCode.MALFORMED_MESSAGE, p.last().code())
        assertEquals(Ucp.CLOSE_PROTOCOL_ERROR, p.conn.closeCode)
    }

    @Test fun malformedAfterAuthenticationIsToleratedFiveTimes() {
        val p = paired()
        repeat(4) { handler.onText(p.conn, "garbage") }
        assertNull(p.conn.closeCode)
        assertEquals(true, p.call(Method.PING).ok, "connection still usable")
        handler.onText(p.conn, "garbage")
        assertEquals(Ucp.CLOSE_PROTOCOL_ERROR, p.conn.closeCode)
    }

    @Test fun responseToUnsupportedVersionIsExplicit() {
        val p = Peer(null)
        handler.onText(p.conn, """{"version":9,"type":"request","id":"a","seq":1,"method":"ping"}""")
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, p.last().code())
    }

    @Test fun protectedMethodBeforeAuthenticationIsRefusedAndClosed() {
        val p = Peer(null)
        val r = p.call(Method.PLAYBACK_SNAPSHOT)
        assertEquals(ErrorCode.UNAUTHENTICATED, r.code())
        assertEquals(Ucp.CLOSE_POLICY, p.conn.closeCode)
        assertTrue(engine.calls.isEmpty())
    }

    @Test fun clientsCannotSendResponsesOrEvents() {
        val p = paired()
        handler.onText(p.conn, EnvelopeCodec.encode(Envelope.event(5, "x")))
        assertEquals(ErrorCode.MALFORMED_MESSAGE, p.last().code())
    }

    @Test fun unknownSenderCannotStartAuthentication() {
        val p = Peer(SoftwareSenderIdentity.generate())
        val r = p.call(Method.AUTH_HELLO, mapOf("versions" to listOf(1L), "senderId" to p.sender!!.senderId, "clientNonce" to Bytes.b64(ByteArray(32))))
        assertEquals(ErrorCode.AUTH_FAILED, r.code())
        assertEquals(Ucp.CLOSE_AUTH_FAILED, p.conn.closeCode)
    }

    @Test fun versionNegotiationPicksHighestCommon() {
        val id = SoftwareSenderIdentity.generate(); registry.save(PairedSender(id.senderId, id.publicKeySpki, "P", Role.CONTROLLER, 1))
        val ok = Peer(id).call(Method.AUTH_HELLO, mapOf("versions" to listOf(1L, 2L, 7L), "senderId" to id.senderId, "clientNonce" to Bytes.b64(ByteArray(32))))
        assertEquals(1L, ok.payload["version"])
        val p2 = Peer(id)
        val bad = p2.call(Method.AUTH_HELLO, mapOf("versions" to listOf(2L, 3L), "senderId" to id.senderId, "clientNonce" to Bytes.b64(ByteArray(32))))
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, bad.code())
    }

    @Test fun authenticationTimesOut() {
        val p = Peer(null)
        time.advance(Ucp.AUTH_TIMEOUT_MS - 1); handler.sweep()
        assertNull(p.conn.closeCode)
        time.advance(1); handler.sweep()
        assertEquals(Ucp.CLOSE_AUTH_TIMEOUT, p.conn.closeCode)
        assertEquals(ErrorCode.AUTH_TIMEOUT, p.last().code())
    }

    @Test fun authenticatedConnectionsAreNotTimedOut() {
        val p = paired()
        time.advance(10 * Ucp.AUTH_TIMEOUT_MS); handler.sweep()
        assertNull(p.conn.closeCode)
    }

    @Test fun expiredChallengeFailsAuthentication() {
        val id = SoftwareSenderIdentity.generate(); registry.save(PairedSender(id.senderId, id.publicKeySpki, "P", Role.CONTROLLER, 1))
        val p = Peer(id)
        val nonce = SecureRandoms.bytes(32)
        val f = Fields(p.call(Method.AUTH_HELLO, mapOf("versions" to listOf(1L), "senderId" to id.senderId, "clientNonce" to Bytes.b64(nonce))).payload)
        val sig = id.sign(AuthTranscripts.clientData(identity.fingerprint, id.senderId, 1, f.string("challengeId"), f.bytes("challenge"), nonce))
        time.advance(Ucp.CHALLENGE_TTL_MS + 1)
        val r = p.call(Method.AUTH_RESPONSE, mapOf("challengeId" to f.string("challengeId"), "signature" to Bytes.b64(sig)))
        assertEquals(ErrorCode.AUTH_FAILED, r.code())
    }

    @Test fun responseWithoutHelloIsRefused() {
        val p = Peer(SoftwareSenderIdentity.generate())
        val r = p.call(Method.AUTH_RESPONSE, mapOf("challengeId" to "abc", "signature" to Bytes.b64(ByteArray(70))))
        assertEquals(ErrorCode.AUTH_FAILED, r.code())
        assertEquals(Ucp.CLOSE_AUTH_FAILED, p.conn.closeCode)
    }

    @Test fun duplicateRequestIdReturnsCachedResponseWithoutReExecuting() {
        val p = paired().startSession()
        val first = p.call(Method.MEDIA_LOAD, mapOf("uri" to "https://h/a.mp4"), id = "dup-1")
        assertEquals(true, first.ok)
        val raw1 = p.conn.sent.last()
        val again = p.call(Method.MEDIA_LOAD, mapOf("uri" to "https://h/a.mp4"), id = "dup-1")
        assertEquals(raw1, p.conn.sent.last(), "identical bytes")
        assertEquals(first, again)
        assertEquals(1, engine.calls.count { it.startsWith("load:") })
    }

    @Test fun sequenceNumbersMustIncrease() {
        val p = paired()
        val start = p.seq
        assertEquals(true, p.call(Method.PING).ok)
        assertEquals(ErrorCode.SEQUENCE_ERROR, p.call(Method.PING, id = "eq", seqNo = start + 1).code())
        assertEquals(ErrorCode.SEQUENCE_ERROR, p.call(Method.PING, id = "old", seqNo = 1).code())
        assertEquals(true, p.call(Method.PING, id = "gap", seqNo = start + 50).ok)
        assertEquals(ErrorCode.SEQUENCE_ERROR, p.call(Method.PING, id = "back", seqNo = start + 49).code())
    }

    @Test fun serverSequenceNumbersIncreaseAcrossResponsesAndEvents() {
        val p = paired().startSession()
        p.call(Method.MEDIA_LOAD, mapOf("uri" to "https://h/a.mp4"))
        val seqs = p.conn.sent.map { EnvelopeCodec.decode(it).seq }
        assertEquals(seqs.sorted(), seqs)
        assertEquals(seqs.size, seqs.toSet().size)
    }

    @Test fun observerCannotControlPlayback() {
        val p = paired(Role.OBSERVER)
        assertEquals(true, p.call(Method.PING).ok)
        assertEquals(true, p.call(Method.PLAYBACK_SNAPSHOT).ok)
        assertEquals(true, p.call(Method.CAPABILITIES_GET).ok)
        for (m in listOf(Method.SESSION_CREATE, Method.MEDIA_LOAD, Method.PLAYBACK_PLAY, Method.PLAYBACK_PAUSE, Method.PLAYBACK_RESUME,
            Method.PLAYBACK_STOP, Method.PLAYBACK_SEEK, Method.PLAYBACK_SET_SPEED, Method.PLAYBACK_SET_VOLUME, Method.SESSION_TERMINATE)) {
            assertEquals(ErrorCode.FORBIDDEN, p.call(m, mapOf("uri" to "https://h/x.mp4", "positionMs" to 1L), sessionId = "guess").code(), m)
        }
        assertTrue(engine.calls.isEmpty(), "engine must never be touched by an observer")
    }

    @Test fun controllerPermissionsAreEnforcedOnTheReceiverUsingTheCurrentRole() {
        val p = paired(Role.CONTROLLER)
        assertEquals(true, p.call(Method.SESSION_CREATE).ok)
        val id = p.sender!!
        registry.save(PairedSender(id.senderId, id.publicKeySpki, "Phone", Role.OBSERVER, 1))
        assertEquals(ErrorCode.FORBIDDEN, p.call(Method.SESSION_CREATE).code())
    }

    @Test fun revocationEndsAnOpenConnectionOnTheNextRequest() {
        val p = paired()
        registry.revoke(p.sender!!.senderId, 5)
        val r = p.call(Method.PING)
        assertEquals(ErrorCode.AUTH_FAILED, r.code())
        assertEquals(Ucp.CLOSE_REVOKED, p.conn.closeCode)
    }

    @Test fun revokeSenderClosesLiveConnectionsAndStopsPlayback() {
        val p = paired().startSession()
        p.call(Method.MEDIA_LOAD, mapOf("uri" to "https://h/a.mp4"))
        assertTrue(handler.revokeSender(p.sender!!.senderId))
        assertEquals(Ucp.CLOSE_REVOKED, p.conn.closeCode)
        assertTrue(registry.find(p.sender.senderId)!!.isRevoked)
        assertTrue(engine.calls.contains("stop"))
        assertTrue(handler.connectedSenders().isEmpty())
        val again = Peer(p.sender)
        val r = again.call(Method.AUTH_HELLO, mapOf("versions" to listOf(1L), "senderId" to p.sender.senderId, "clientNonce" to Bytes.b64(ByteArray(32))))
        assertEquals(ErrorCode.AUTH_FAILED, r.code())
        assertFalse(handler.revokeSender("0".repeat(64)))
    }

    @Test fun sessionRulesAreEnforced() {
        val p = paired()
        assertEquals(ErrorCode.SESSION_REQUIRED, p.call(Method.PLAYBACK_PLAY, sessionId = null).code())
        assertEquals(ErrorCode.SESSION_INVALID, p.call(Method.PLAYBACK_PLAY, sessionId = "bogus").code())
        p.startSession()
        assertEquals(ErrorCode.INVALID_STATE, p.call(Method.PLAYBACK_PLAY).code(), "nothing loaded yet")
        val other = paired()
        assertEquals(ErrorCode.SESSION_INVALID, other.call(Method.MEDIA_LOAD, mapOf("uri" to "https://h/a.mp4"), sessionId = p.session).code(), "foreign session id")
        assertTrue(engine.calls.isEmpty())
    }

    @Test fun payloadValidationOnControlMethods() {
        val p = paired().startSession()
        for (bad in listOf("file:///sdcard/a.mp4", "content://media/1", "ftp://h/x", "http://", "javascript:1")) {
            assertEquals(ErrorCode.UNSUPPORTED_MEDIA, p.call(Method.MEDIA_LOAD, mapOf("uri" to bad)).code(), bad)
        }
        assertEquals(ErrorCode.INVALID_PAYLOAD, p.call(Method.MEDIA_LOAD, emptyMap()).code())
        assertEquals(ErrorCode.INVALID_PAYLOAD, p.call(Method.MEDIA_LOAD, mapOf("uri" to "https://h/a", "mimeType" to "bad mime")).code())
        assertEquals(ErrorCode.INVALID_PAYLOAD, p.call(Method.MEDIA_LOAD, mapOf("uri" to "https://h/a", "autoplay" to "yes")).code())
        assertTrue(engine.calls.isEmpty())
        assertEquals(true, p.call(Method.MEDIA_LOAD, mapOf("uri" to "https://h/a.m3u8", "mimeType" to "application/x-mpegURL", "title" to "T", "startPositionMs" to 500L)).ok)
        assertEquals("load:https://h/a.m3u8:true:500", engine.calls.last())
        assertEquals(ErrorCode.INVALID_PAYLOAD, p.call(Method.PLAYBACK_SEEK, mapOf("positionMs" to -1L)).code())
        assertEquals(ErrorCode.INVALID_PAYLOAD, p.call(Method.PLAYBACK_SEEK, mapOf("positionMs" to "5")).code())
        assertEquals(ErrorCode.INVALID_PAYLOAD, p.call(Method.PLAYBACK_SET_SPEED, mapOf("speed" to 9.0)).code())
        assertEquals(ErrorCode.INVALID_PAYLOAD, p.call(Method.PLAYBACK_SET_VOLUME, mapOf("volume" to 1.5)).code())
        assertEquals(1, engine.calls.size)
    }

    @Test fun mediaHeadersAreAllowlistedValidatedAndNeverLeakIntoSnapshots() {
        val p = paired().startSession()
        fun load(h: Map<String, Any?>) = p.call(Method.MEDIA_LOAD, mapOf("uri" to "https://cdn/x/master", "headers" to h))
        for (bad in listOf<Map<String, Any?>>(
            mapOf("Host" to "evil"), mapOf("Authorization" to "Bearer x"), mapOf("Referer" to "javascript:alert(1)"),
            mapOf("Referer" to "https://a/\r\nX-Injected: 1"), mapOf("User-Agent" to ""),
            mapOf("Cookie" to 5L), mapOf("Cookie" to "a".repeat(9000)), mapOf("referer" to "https://a/", "Referer" to "https://b/"),
        )) assertEquals(ErrorCode.INVALID_PAYLOAD, load(bad).code(), bad.toString())
        assertTrue(engine.calls.isEmpty())

        val ok = load(mapOf("referer" to "https://onepace.example/watch/1", "ORIGIN" to "https://onepace.example", "User-Agent" to "UA/1", "Cookie" to "sid=secret123"))
        assertEquals(true, ok.ok)
        assertEquals(
            mapOf("Referer" to "https://onepace.example/watch/1", "Origin" to "https://onepace.example", "User-Agent" to "UA/1", "Cookie" to "sid=secret123"),
            engine.lastItem!!.headers,
        )
        assertFalse(engine.lastItem.toString().contains("secret123"), "toString must not reveal header values")
        // Neither the load response, the snapshot, nor any event sent to anyone carries the cookie.
        assertFalse(ok.payload.toString().contains("secret123"))
        assertFalse(p.call(Method.PLAYBACK_SNAPSHOT).payload.toString().contains("secret123"))
        assertFalse(p.conn.sent.any { it.contains("secret123") })
    }

    @Test fun fullControllerFlowMapsToEngineCalls() {
        val p = paired().startSession()
        p.call(Method.MEDIA_LOAD, mapOf("uri" to "https://h/a.mp4", "autoplay" to false))
        assertEquals("READY", Fields(Fields(p.call(Method.PLAYBACK_SNAPSHOT).payload).obj("snapshot").raw).string("status"))
        p.call(Method.PLAYBACK_PLAY); p.call(Method.PLAYBACK_PAUSE); p.call(Method.PLAYBACK_RESUME)
        p.call(Method.PLAYBACK_SEEK, mapOf("positionMs" to 30_000L)); p.call(Method.PLAYBACK_SET_SPEED, mapOf("speed" to 1.5)); p.call(Method.PLAYBACK_SET_VOLUME, mapOf("volume" to 0.25))
        p.call(Method.PLAYBACK_STOP)
        assertEquals(listOf("load:https://h/a.mp4:false:0", "play", "pause", "resume", "seek:30000", "speed:1.5", "volume:0.25", "stop"), engine.calls.toList())
        assertEquals(true, p.call(Method.SESSION_TERMINATE).ok)
        assertEquals(ErrorCode.SESSION_INVALID, p.call(Method.PLAYBACK_PLAY).code())
    }

    @Test fun connectionLossReleasesSessionAndScreen() {
        val p = paired().startSession()
        p.call(Method.MEDIA_LOAD, mapOf("uri" to "https://h/a.mp4"))
        handler.onClose(p.conn)
        assertNull(sessions.active())
        assertTrue(engine.calls.contains("stop"))
    }

    @Test fun replacingSessionDoesNotStopPlaybackButNotifiesPreviousOwner() {
        val a = paired().startSession()
        a.call(Method.MEDIA_LOAD, mapOf("uri" to "https://h/a.mp4"))
        val b = paired().startSession()
        assertFalse(engine.calls.contains("stop"))
        assertTrue(a.conn.sent.any { it.contains("session.terminated") && it.contains("REPLACED") })
        assertEquals(ErrorCode.SESSION_INVALID, a.call(Method.PLAYBACK_PAUSE).code())
        assertEquals(true, b.call(Method.PLAYBACK_PAUSE).ok)
    }

    @Test fun everyAuthenticatedPeerReceivesStateEvents() {
        val ctl = paired().startSession()
        val obs = paired(Role.OBSERVER)
        ctl.call(Method.MEDIA_LOAD, mapOf("uri" to "https://h/a.mp4"))
        assertTrue(obs.conn.sent.any { it.contains("playback.stateChanged") && it.contains("PLAYING") })
        val unauth = Peer(null)
        engine.pause()
        assertTrue(unauth.conn.sent.isEmpty(), "unauthenticated peers get nothing")
    }

    @Test fun connectedSenderListIsTracked() {
        val p = paired()
        assertEquals(1, handler.connectedSenders().size)
        assertEquals("Phone", handler.connectedSenders().single().name)
        handler.onClose(p.conn)
        assertTrue(handler.connectedSenders().isEmpty())
        assertEquals(listOf(1, 0), connected.map { it.size })
    }

    @Test fun unknownAuthenticatedMethodAndBinaryFramesAreRejected() {
        val p = paired()
        assertEquals(ErrorCode.UNKNOWN_METHOD, p.call("does.not.exist").code())
        handler.onBinary(p.conn)
        assertEquals(1003, p.conn.closeCode)
    }

    @Test fun capabilitiesDescribeRoleAndExtensionPoints() {
        val r = paired(Role.OBSERVER).call(Method.CAPABILITIES_GET)
        val f = Fields(r.payload)
        assertEquals("OBSERVER", f.string("role"))
        assertEquals(identity.fingerprint, f.string("receiverFingerprint"))
        assertEquals(listOf("PING", "READ_SNAPSHOT"), r.payload["permissions"])
        assertEquals(listOf("direct-url"), r.payload["playbackMethods"])
    }

    @Test fun tooManyUnauthenticatedConnectionsAreRefused() {
        val peers = (1..Ucp.MAX_UNAUTHENTICATED_CONNECTIONS).map { Peer(null) }
        assertTrue(peers.all { it.conn.closeCode == null })
        val extra = Peer(null)
        assertEquals(Ucp.CLOSE_TRY_LATER, extra.conn.closeCode)
    }

    // ---- media.load extensions: headers + subtitles (UCP 1.0, backward compatible) ---------------------------

    private fun loadWith(p: Peer, extra: Map<String, Any?>) =
        p.call(Method.MEDIA_LOAD, linkedMapOf<String, Any?>("uri" to "https://cdn.example/ep1/master.m3u8").apply { putAll(extra) })

    @Test fun headerValidationTable() {
        val p = paired().startSession()
        val rejected = listOf<Pair<String, Map<String, Any?>>>(
            "not allowlisted" to mapOf("X-Forwarded-For" to "1.2.3.4"),
            "Host" to mapOf("Host" to "evil.example"),
            "Authorization" to mapOf("Authorization" to "Bearer x"),
            "Range" to mapOf("Range" to "bytes=0-"),
            "empty name" to mapOf("" to "x"),
            "too many entries" to mapOf("Referer" to "https://a/", "Origin" to "https://a", "User-Agent" to "UA", "Cookie" to "a=b", "Accept-Language" to "en", "X" to "y"),
            "duplicate (case-insensitive)" to mapOf("cookie" to "a=1", "COOKIE" to "a=2"),
            "duplicate referer" to mapOf("referer" to "https://a/", "Referer" to "https://b/"),
            "value too long" to mapOf("User-Agent" to "u".repeat(4097)),
            "empty value" to mapOf("Accept-Language" to ""),
            "CR/LF injection" to mapOf("Referer" to "https://a/\r\nX-Injected: 1"),
            "LF only" to mapOf("Cookie" to "a=b\nX: y"),
            "control char" to mapOf("User-Agent" to "UA\u0007"),
            "tab" to mapOf("User-Agent" to "UA\tx"),
            "DEL" to mapOf("Cookie" to "a=\u007f"),
            "non-ASCII" to mapOf("User-Agent" to "Ünïcode"),
            "non-string value" to mapOf("Cookie" to 5L),
            "null-ish object value" to mapOf("Cookie" to mapOf("a" to "b")),
            "javascript referer" to mapOf("Referer" to "javascript:alert(1)"),
            "file origin" to mapOf("Origin" to "file:///etc"),
            "total over 8192 bytes" to mapOf("Cookie" to "c".repeat(4096), "User-Agent" to "u".repeat(4096), "Referer" to "https://a/" + "r".repeat(100)),
        )
        for ((why, h) in rejected) assertEquals(ErrorCode.INVALID_PAYLOAD, loadWith(p, mapOf("headers" to h)).code(), why)
        assertEquals(ErrorCode.INVALID_PAYLOAD, loadWith(p, mapOf("headers" to "Referer: x")).code(), "headers must be an object")
        assertTrue(engine.calls.isEmpty(), "nothing invalid may reach the player")

        // Boundaries that are accepted.
        val max = mapOf("Cookie" to "c".repeat(4096), "User-Agent" to "u".repeat(4096 - 30))
        assertEquals(true, loadWith(p, mapOf("headers" to max)).ok, "exactly within 8192 bytes")
        val five = mapOf("referer" to "https://site.example/watch", "origin" to "https://site.example", "user-agent" to "UA/1 (x)",
            "cookie" to "sid=abc; theme=dark", "accept-language" to "en-US,en;q=0.9")
        assertEquals(true, loadWith(p, mapOf("headers" to five)).ok)
        assertEquals(
            mapOf("Referer" to "https://site.example/watch", "Origin" to "https://site.example", "User-Agent" to "UA/1 (x)",
                "Cookie" to "sid=abc; theme=dark", "Accept-Language" to "en-US,en;q=0.9"),
            engine.lastItem!!.headers, "names are normalised to their canonical spelling",
        )
        assertEquals(true, loadWith(p, mapOf("headers" to emptyMap<String, Any?>())).ok, "empty object is fine")
    }

    @Test fun subtitleValidationTable() {
        val p = paired().startSession()
        fun sub(vararg kv: Pair<String, Any?>) = linkedMapOf<String, Any?>("uri" to "https://cdn.example/ep1_en.vtt", "mimeType" to "text/vtt").apply { kv.forEach { put(it.first, it.second) } }
        val rejected = listOf<Pair<String, Any?>>(
            "not an array" to sub(),
            "string item" to listOf("https://cdn.example/a.vtt"),
            "too many" to List(9) { sub() },
            "missing uri" to listOf(sub().apply { remove("uri") }),
            "missing mimeType" to listOf(sub().apply { remove("mimeType") }),
            "file uri" to listOf(sub("uri" to "file:///sdcard/a.vtt")),
            "javascript uri" to listOf(sub("uri" to "javascript:alert(1)")),
            "uri too long" to listOf(sub("uri" to "https://cdn.example/" + "a".repeat(2048))),
            "unsupported mime" to listOf(sub("mimeType" to "text/html")),
            "mime case trick" to listOf(sub("mimeType" to "TEXT/VTT ")),
            "language too long" to listOf(sub("language" to "e".repeat(17))),
            "language with spaces" to listOf(sub("language" to "en US")),
            "language not a string" to listOf(sub("language" to 1L)),
            "label too long" to listOf(sub("label" to "l".repeat(65))),
            "label control char" to listOf(sub("label" to "English\n")),
            "unknown field" to listOf(sub("headers" to mapOf("Cookie" to "x"))),
        )
        for ((why, s) in rejected) assertEquals(ErrorCode.INVALID_PAYLOAD, loadWith(p, mapOf("subtitles" to s)).code(), why)
        assertTrue(engine.calls.isEmpty())

        val ok = listOf(
            sub("language" to "en", "label" to "English"),
            sub("uri" to "http://cdn.example/ep1.pt-BR.srt", "mimeType" to "application/x-subrip", "language" to "pt-BR"),
            sub("uri" to "https://cdn.example/a.ass", "mimeType" to "text/x-ssa"),
            sub("uri" to "https://cdn.example/a.ttml", "mimeType" to "application/ttml+xml", "label" to "l".repeat(64)),
        ) + List(4) { i -> sub("uri" to "https://cdn.example/x$i.vtt") }
        assertEquals(true, loadWith(p, mapOf("subtitles" to ok)).ok, "8 tracks, all formats")
        val got = engine.lastItem!!.subtitles
        assertEquals(8, got.size)
        assertEquals(com.universalcast.playback.SubtitleInfo("https://cdn.example/ep1_en.vtt", "text/vtt", "en", "English"), got[0])
        assertEquals("application/x-subrip", got[1].mimeType)
        assertEquals(true, loadWith(p, mapOf("subtitles" to emptyList<Any>())).ok)
        assertTrue(engine.lastItem!!.subtitles.isEmpty())
    }

    @Test fun headersAndSubtitlesReachTheEngineAndOldLoadsStillWork() {
        val p = paired().startSession()
        assertEquals(true, p.call(Method.MEDIA_LOAD, mapOf("uri" to "https://h/a.mp4")).ok, "a UCP 1.0 sender without the new fields")
        assertEquals(com.universalcast.playback.MediaItemInfo("https://h/a.mp4"), engine.lastItem)

        val r = loadWith(p, mapOf(
            "mimeType" to "application/x-mpegURL", "title" to "Ep 1",
            "headers" to mapOf("Referer" to "https://site.example/ep1", "Cookie" to "cf=secretcookie"),
            "subtitles" to listOf(mapOf("uri" to "https://cdn.example/ep1_en.vtt", "mimeType" to "text/vtt", "language" to "en")),
        ))
        assertEquals(true, r.ok)
        val item = engine.lastItem!!
        assertEquals("application/x-mpegURL", item.mimeType)
        assertEquals(mapOf("Referer" to "https://site.example/ep1", "Cookie" to "cf=secretcookie"), item.headers)
        assertEquals(listOf("https://cdn.example/ep1_en.vtt"), item.subtitles.map { it.uri })
        assertFalse(item.toString().contains("secretcookie"))
        assertFalse(p.conn.sent.any { it.contains("secretcookie") }, "header values are never echoed to anyone")
    }

    @Test fun observerCannotLoadEvenWithHeaders() {
        val ctl = paired().startSession()
        val obs = paired(Role.OBSERVER)
        val r = obs.call(Method.MEDIA_LOAD, mapOf("uri" to "https://h/a.m3u8", "headers" to mapOf("Cookie" to "x=1"),
            "subtitles" to listOf(mapOf("uri" to "https://h/a.vtt", "mimeType" to "text/vtt"))), sessionId = ctl.session)
        assertEquals(ErrorCode.FORBIDDEN, r.code())
        assertTrue(engine.calls.isEmpty())
    }

    @Test fun capabilitiesAdvertiseTheNewFeaturesButStillOnlyDirectUrls() {
        val p = paired()
        val f = Fields(p.call(Method.CAPABILITIES_GET).payload)
        val features = f.stringList("features", 32, 64)
        assertTrue("media-headers" in features && "subtitles" in features, features.toString())
        assertEquals(listOf("direct-url"), f.stringList("playbackMethods", 8, 64))
    }

    @Test fun oversizedLoadIsRejectedBeforeValidation() {
        val p = paired().startSession()
        val subs = List(8) { mapOf("uri" to "https://cdn.example/" + "a".repeat(2000) + "$it.vtt", "mimeType" to "text/vtt") }
        val headers = mapOf("Cookie" to "c".repeat(4096), "User-Agent" to "u".repeat(4000))
        // ~24 KB: still a valid message; a 64 KiB+ message is refused by the size limit (tested elsewhere).
        assertEquals(true, loadWith(p, mapOf("subtitles" to subs, "headers" to headers)).ok)
        handler.onText(p.conn, EnvelopeCodec.encode(Envelope.request("big", ++p.seq, Method.MEDIA_LOAD,
            mapOf("uri" to "https://h/a.mp4", "title" to "t".repeat(Ucp.MAX_MESSAGE_BYTES)), p.session)))
        assertEquals(ErrorCode.MESSAGE_TOO_LARGE, p.last().code())
    }
}
