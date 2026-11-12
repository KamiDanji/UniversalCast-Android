package com.universalcast.network

import com.universalcast.auth.AuthFailure
import com.universalcast.auth.AuthTranscripts
import com.universalcast.auth.Authenticator
import com.universalcast.config.ReceiverTimeouts
import com.universalcast.core.DiagnosticsLog
import com.universalcast.identity.Permissions
import com.universalcast.identity.Role
import com.universalcast.identity.SenderRegistry
import com.universalcast.pairing.PairingListener
import com.universalcast.pairing.PairingManager
import com.universalcast.pairing.PairingOutcome
import com.universalcast.pairing.PairingView
import com.universalcast.playback.MediaHeaders
import com.universalcast.playback.MediaItemInfo
import com.universalcast.playback.MediaUriValidator
import com.universalcast.playback.PlaybackEngine
import com.universalcast.playback.PlaybackListener
import com.universalcast.playback.PlaybackSnapshot
import com.universalcast.playback.PlaybackStatus
import com.universalcast.playback.SubtitleValidator
import com.universalcast.security.Bytes
import com.universalcast.security.Fingerprint
import com.universalcast.security.ReceiverIdentity
import com.universalcast.security.TimeSource
import com.universalcast.session.Session
import com.universalcast.session.SessionEndReason
import com.universalcast.session.SessionListener
import com.universalcast.session.SessionManager
import com.universalcast.ucp.Envelope
import com.universalcast.ucp.EnvelopeCodec
import com.universalcast.ucp.ErrorCode
import com.universalcast.ucp.EventName
import com.universalcast.ucp.Fields
import com.universalcast.ucp.JsonObject
import com.universalcast.ucp.Method
import com.universalcast.ucp.MessageType
import com.universalcast.ucp.Ucp
import com.universalcast.ucp.UcpError
import com.universalcast.ucp.UcpException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Transport-independent view of one client connection. */
interface Connection {
    val id: String
    val remoteAddress: String
    fun send(text: String)
    fun close(code: Int, reason: String)
}

enum class Phase { NEW, CHALLENGED, PAIRING, AUTHENTICATED, CLOSED }

class ConnectionRecord(val conn: Connection, val openedAtMs: Long, @Volatile var deadlineMs: Long) {
    @Volatile var phase: Phase = Phase.NEW
    @Volatile var senderId: String? = null
    @Volatile var senderName: String? = null
    @Volatile var role: Role? = null
    @Volatile var authenticatedAtMs: Long = 0
    var negotiatedVersion: Int = Ucp.VERSION
    var lastClientSeq: Long = 0
    var malformedCount: Int = 0
    val outSeq = AtomicLong(0)
    val responseCache = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > Ucp.DUPLICATE_CACHE_SIZE
    }
}

data class ConnectedSender(
    val connectionId: String,
    val senderId: String,
    val name: String,
    val role: Role,
    val connectedAtMs: Long,
    val remoteAddress: String,
)

fun interface ConnectionsListener {
    fun onConnectionsChanged(senders: List<ConnectedSender>)
}

/**
 * The receiver-side UCP state machine. It owns no sockets: a transport feeds it [onOpen]/[onText]/[onClose].
 *
 * Every inbound message passes, in order: size limit -> strict decoding -> request-id duplicate cache ->
 * sequence check -> phase check (auth/pairing vs. protected) -> permission check on the receiver ->
 * revocation re-check -> payload validation -> execution. Any failure becomes an error response; nothing a
 * client sends can raise an exception out of this class.
 */
class UcpProtocolHandler(
    private val identity: ReceiverIdentity,
    private val registry: SenderRegistry,
    private val engine: PlaybackEngine,
    private val sessions: SessionManager,
    private val pairing: PairingManager,
    private val authenticator: Authenticator,
    private val time: TimeSource,
    private val timeouts: ReceiverTimeouts,
    private val log: DiagnosticsLog,
    private val deviceName: () -> String,
) : PairingListener, PlaybackListener, SessionListener {

    private val records = ConcurrentHashMap<String, ConnectionRecord>()
    private val connectionListeners = CopyOnWriteArrayList<ConnectionsListener>()

    fun attach() {
        engine.addListener(this)
        sessions.addListener(this)
        pairing.addListener(this)
    }

    fun detach() {
        engine.removeListener(this)
        sessions.removeListener(this)
        pairing.removeListener(this)
    }

    fun addConnectionsListener(l: ConnectionsListener) {
        connectionListeners.add(l)
    }

    fun connectedSenders(): List<ConnectedSender> =
        records.values.filter { it.phase == Phase.AUTHENTICATED }.map {
            ConnectedSender(it.conn.id, it.senderId ?: "", it.senderName ?: "", it.role ?: Role.OBSERVER, it.authenticatedAtMs, it.conn.remoteAddress)
        }.sortedBy { it.connectedAtMs }

    private fun notifyConnections() {
        val list = connectedSenders()
        connectionListeners.forEach { it.onConnectionsChanged(list) }
    }

    // ---- transport callbacks -------------------------------------------------------------------------

    fun onOpen(conn: Connection) {
        val now = time.nowMs()
        val unauthenticated = records.values.count { it.phase != Phase.AUTHENTICATED }
        val rec = ConnectionRecord(conn, now, now + timeouts.authTimeoutMs)
        if (unauthenticated >= Ucp.MAX_UNAUTHENTICATED_CONNECTIONS) {
            log.warn("Rejected connection from ${conn.remoteAddress}: too many unauthenticated connections")
            rec.phase = Phase.CLOSED
            conn.close(Ucp.CLOSE_TRY_LATER, "too many connections")
            return
        }
        records[conn.id] = rec
    }

    fun onText(conn: Connection, text: String) {
        val rec = records[conn.id] ?: return
        synchronized(rec) {
            try {
                process(rec, text)
            } catch (e: Exception) {
                log.error("Unexpected failure while handling a message", e)
                closeRecord(rec, Ucp.CLOSE_PROTOCOL_ERROR, "internal error")
            }
        }
    }

    fun onBinary(conn: Connection) {
        val rec = records[conn.id] ?: return
        closeRecord(rec, 1003, "binary frames are not supported")
    }

    fun onClose(conn: Connection) {
        val rec = records.remove(conn.id) ?: return
        val wasAuthenticated = rec.phase == Phase.AUTHENTICATED
        rec.phase = Phase.CLOSED
        authenticator.forgetConnection(conn.id)
        pairing.onConnectionClosed(conn.id)
        sessions.endForConnection(conn.id, SessionEndReason.CONNECTION_CLOSED)
        if (wasAuthenticated) notifyConnections()
    }

    /** Closes connections that did not finish authentication or pairing in time. Call about once per second. */
    fun sweep() {
        val now = time.nowMs()
        for (rec in records.values) {
            if (rec.phase != Phase.AUTHENTICATED && rec.phase != Phase.CLOSED && now >= rec.deadlineMs) {
                log.warn("Authentication timeout for ${rec.conn.remoteAddress}")
                sendError(rec, null, ErrorCode.AUTH_TIMEOUT, "Authentication timed out")
                closeRecord(rec, Ucp.CLOSE_AUTH_TIMEOUT, "authentication timeout")
            }
        }
    }

    /** Persists the revocation, then immediately drops every live connection of that sender. */
    fun revokeSender(senderId: String): Boolean {
        val known = registry.revoke(senderId, time.nowMs())
        for (rec in records.values) {
            if (rec.senderId == senderId) {
                sessions.endForConnection(rec.conn.id, SessionEndReason.REVOKED)
                closeRecord(rec, Ucp.CLOSE_REVOKED, "access revoked")
            }
        }
        notifyConnections()
        if (known) log.info("Revoked sender ${senderId.take(12)}")
        return known
    }

    fun closeAll(code: Int, reason: String) {
        records.values.forEach { closeRecord(it, code, reason) }
    }

    // ---- core pipeline -------------------------------------------------------------------------------

    private fun process(rec: ConnectionRecord, text: String) {
        if (rec.phase == Phase.CLOSED) return
        if (text.toByteArray(Charsets.UTF_8).size > Ucp.MAX_MESSAGE_BYTES) {
            sendError(rec, null, ErrorCode.MESSAGE_TOO_LARGE, "Message exceeds ${Ucp.MAX_MESSAGE_BYTES} bytes")
            closeRecord(rec, Ucp.CLOSE_TOO_LARGE, "message too large")
            return
        }
        val env = try {
            EnvelopeCodec.decode(text)
        } catch (e: UcpException) {
            rec.malformedCount++
            sendError(rec, null, e.code, e.message ?: "Malformed message")
            if (rec.malformedCount >= Ucp.MAX_MALFORMED_MESSAGES || rec.phase != Phase.AUTHENTICATED) {
                closeRecord(rec, Ucp.CLOSE_PROTOCOL_ERROR, "malformed message")
            }
            return
        }
        if (env.type != MessageType.REQUEST) {
            rec.malformedCount++
            sendError(rec, env.id, ErrorCode.MALFORMED_MESSAGE, "Only requests are accepted from senders")
            if (rec.malformedCount >= Ucp.MAX_MALFORMED_MESSAGES || rec.phase != Phase.AUTHENTICATED) {
                closeRecord(rec, Ucp.CLOSE_PROTOCOL_ERROR, "unexpected message type")
            }
            return
        }
        val id = env.id!!

        // Duplicate request protection: a retransmitted id gets the original answer and is never re-executed.
        rec.responseCache[id]?.let {
            safeSend(rec, it)
            return
        }
        if (env.seq <= rec.lastClientSeq) {
            sendError(rec, id, ErrorCode.SEQUENCE_ERROR, "Sequence number ${env.seq} is not greater than ${rec.lastClientSeq}")
            return
        }
        rec.lastClientSeq = env.seq

        var closeCode: Int? = null
        val response: Envelope = try {
            val result = route(rec, env) // may publish events first; allocate the response seq afterwards
            Envelope.response(id, rec.outSeq.incrementAndGet(), result)
        } catch (e: UcpException) {
            closeCode = e.closeCode
            Envelope.errorResponse(id, rec.outSeq.incrementAndGet(), UcpError(e.code, e.message ?: e.code.name))
        } catch (e: Exception) {
            log.error("Request ${env.name} failed", e)
            Envelope.errorResponse(id, rec.outSeq.incrementAndGet(), UcpError(ErrorCode.INTERNAL, "Internal error"))
        }
        val encoded = EnvelopeCodec.encode(response)
        rec.responseCache[id] = encoded
        safeSend(rec, encoded)
        if (closeCode != null) closeRecord(rec, closeCode, "protocol policy")
    }

    private fun route(rec: ConnectionRecord, env: Envelope): JsonObject {
        return when (env.name!!) {
            Method.AUTH_HELLO -> authHello(rec, env)
            Method.AUTH_RESPONSE -> authResponse(rec, env)
            Method.PAIR_REQUEST -> pairRequest(rec, env)
            Method.PAIR_CONFIRM -> pairConfirm(rec, env)
            Method.PAIR_CANCEL -> pairCancel(rec)
            else -> {
                if (rec.phase != Phase.AUTHENTICATED) {
                    throw UcpException(ErrorCode.UNAUTHENTICATED, "Authentication is required", Ucp.CLOSE_POLICY)
                }
                authenticated(rec, env)
            }
        }
    }

    // ---- authentication ------------------------------------------------------------------------------

    private fun authHello(rec: ConnectionRecord, env: Envelope): JsonObject {
        if (rec.phase != Phase.NEW) throw UcpException(ErrorCode.INVALID_STATE, "Hello is only valid once, first", Ucp.CLOSE_POLICY)
        val f = Fields(env.payload)
        val versions = f.longList("versions", 8, 1, 1000).map { it.toInt() }
        val senderId = f.string("senderId", 64, 64)
        if (!Fingerprint.isWellFormed(senderId)) throw UcpException(ErrorCode.INVALID_PAYLOAD, "Malformed senderId")
        val clientNonce = f.bytes("clientNonce", exactLength = 32)
        val negotiated = Ucp.SUPPORTED_VERSIONS.filter { it in versions }.maxOrNull()
            ?: throw UcpException(ErrorCode.UNSUPPORTED_VERSION, "No common protocol version", Ucp.CLOSE_POLICY)
        val sender = registry.find(senderId)
        if (sender == null || sender.isRevoked) {
            log.warn("Rejected hello from ${rec.conn.remoteAddress}: unknown or revoked identity")
            throw UcpException(ErrorCode.AUTH_FAILED, "Authentication failed", Ucp.CLOSE_AUTH_FAILED)
        }
        val challenge = authenticator.issue(rec.conn.id, senderId, clientNonce, negotiated)
        rec.senderId = senderId
        rec.negotiatedVersion = negotiated
        rec.phase = Phase.CHALLENGED
        return linkedMapOf(
            "challengeId" to challenge.id,
            "challenge" to Bytes.b64(challenge.bytes),
            "expiresAtMs" to challenge.expiresAtMs,
            "version" to negotiated,
            "receiverFingerprint" to identity.fingerprint,
            "receiverDeviceId" to identity.deviceId,
        )
    }

    private fun authResponse(rec: ConnectionRecord, env: Envelope): JsonObject {
        val f = Fields(env.payload)
        val challengeId = f.string("challengeId", Ucp.MAX_ID_LENGTH)
        val signature = f.bytes("signature", maxLength = 80)
        if (rec.phase != Phase.CHALLENGED) {
            val code = if (authenticator.wasConsumed(challengeId)) ErrorCode.REPLAY_DETECTED else ErrorCode.AUTH_FAILED
            log.warn("Rejected auth response from ${rec.conn.remoteAddress}: no challenge outstanding ($code)")
            throw UcpException(code, "Authentication failed", Ucp.CLOSE_AUTH_FAILED)
        }
        val senderId = rec.senderId!!
        val sender = registry.find(senderId)
        val result = authenticator.verify(rec.conn.id, challengeId, signature, sender)
        if (!result.success || sender == null) {
            val failure = result.failure
            log.warn("Authentication failed for ${senderId.take(12)} from ${rec.conn.remoteAddress}: $failure")
            val code = if (failure == AuthFailure.REPLAYED) ErrorCode.REPLAY_DETECTED else ErrorCode.AUTH_FAILED
            throw UcpException(code, "Authentication failed", Ucp.CLOSE_AUTH_FAILED)
        }
        val ch = result.challenge!!
        val now = time.nowMs()
        rec.role = sender.role
        rec.senderName = sender.name
        rec.authenticatedAtMs = now
        rec.phase = Phase.AUTHENTICATED
        registry.markConnected(sender.senderId, now)
        val serverProof = identity.sign(
            AuthTranscripts.serverData(identity.fingerprint, senderId, ch.version, ch.id, ch.bytes, ch.clientNonce, signature)
        )
        log.info("Authenticated ${sender.name} as ${sender.role}")
        notifyConnections()
        return linkedMapOf(
            "role" to sender.role.name,
            "serverProof" to Bytes.b64(serverProof),
            "receiverFingerprint" to identity.fingerprint,
        )
    }

    // ---- pairing -------------------------------------------------------------------------------------

    private fun pairRequest(rec: ConnectionRecord, env: Envelope): JsonObject {
        if (rec.phase != Phase.NEW) throw UcpException(ErrorCode.INVALID_STATE, "Pairing must be the first request", Ucp.CLOSE_POLICY)
        val response = try {
            pairing.handleRequest(rec.conn.id, Fields(env.payload))
        } catch (e: UcpException) {
            log.warn("Pairing request from ${rec.conn.remoteAddress} rejected: ${e.code}")
            throw UcpException(e.code, e.message ?: "Pairing failed", Ucp.CLOSE_PROTOCOL_ERROR)
        }
        rec.phase = Phase.PAIRING
        rec.deadlineMs = response.expiresAtMs + 5_000
        return response.payload
    }

    private fun pairConfirm(rec: ConnectionRecord, env: Envelope): JsonObject {
        if (rec.phase != Phase.PAIRING) throw UcpException(ErrorCode.INVALID_STATE, "No pairing in progress on this connection", Ucp.CLOSE_POLICY)
        pairing.handleSenderConfirm(rec.conn.id, Fields(env.payload))
        return emptyMap()
    }

    private fun pairCancel(rec: ConnectionRecord): JsonObject {
        if (rec.phase != Phase.PAIRING) throw UcpException(ErrorCode.INVALID_STATE, "No pairing in progress on this connection", Ucp.CLOSE_POLICY)
        pairing.handleSenderCancel(rec.conn.id)
        return emptyMap()
    }

    override fun onPairingChanged(view: PairingView?) {
        // UI-facing only; the handler reacts to final outcomes in onPairingFinished.
    }

    override fun onPairingFinished(connectionId: String, outcome: PairingOutcome) {
        val rec = records[connectionId] ?: return
        val payload = linkedMapOf<String, Any?>(
            "status" to outcome.status,
            "role" to outcome.role?.name,
            "reason" to outcome.reason,
            "mac" to outcome.mac?.let { Bytes.b64(it) },
        )
        sendEvent(rec, EventName.PAIR_RESULT, payload)
        closeRecord(rec, Ucp.CLOSE_NORMAL, "pairing finished")
    }

    // ---- protected methods ---------------------------------------------------------------------------

    private fun authenticated(rec: ConnectionRecord, env: Envelope): JsonObject {
        val method = env.name!!
        val permission = Permissions.requiredFor(method) ?: throw UcpException(ErrorCode.UNKNOWN_METHOD, "Unknown method '$method'")
        // Revocation and role are re-read on every request, so a revoked or demoted sender loses access at once.
        val sender = registry.find(rec.senderId ?: "")
        if (sender == null || sender.isRevoked) {
            throw UcpException(ErrorCode.AUTH_FAILED, "Access revoked", Ucp.CLOSE_REVOKED)
        }
        rec.role = sender.role
        if (!sender.role.allows(permission)) {
            log.warn("Denied $method for ${sender.name} (${sender.role})")
            throw UcpException(ErrorCode.FORBIDDEN, "Role ${sender.role} may not call $method")
        }
        val f = Fields(env.payload)
        return when (method) {
            Method.PING -> linkedMapOf("nonce" to f.optString("nonce", 64), "serverTimeMs" to time.nowMs())
            Method.CAPABILITIES_GET -> capabilities(sender.role)
            Method.PLAYBACK_SNAPSHOT -> linkedMapOf("snapshot" to engine.snapshot.toJson())
            Method.SESSION_CREATE -> {
                val r = sessions.create(rec.conn.id, sender.senderId)
                linkedMapOf("sessionId" to r.session.id, "replacedPrevious" to (r.replaced != null))
            }
            Method.SESSION_TERMINATE -> {
                val s = sessions.validate(env.session, rec.conn.id)
                sessions.terminate(s.id, rec.conn.id)
                emptyMap()
            }
            else -> {
                sessions.validate(env.session, rec.conn.id)
                control(method, f)
            }
        }
    }

    private fun control(method: String, f: Fields): JsonObject {
        when (method) {
            Method.MEDIA_LOAD -> {
                val uri = try {
                    MediaUriValidator.validateUri(f.string("uri"))
                } catch (e: IllegalArgumentException) {
                    throw UcpException(ErrorCode.UNSUPPORTED_MEDIA, e.message ?: "Unsupported media URI")
                }
                val mime = f.optString("mimeType", 128)?.let {
                    try {
                        MediaUriValidator.validateMime(it)
                    } catch (e: IllegalArgumentException) {
                        throw UcpException(ErrorCode.INVALID_PAYLOAD, "Invalid mimeType")
                    }
                }
                val title = f.optString("title", 256)
                val autoplay = f.optBool("autoplay") ?: true
                val start = f.optLong("startPositionMs", 0, 7L * 24 * 3600 * 1000) ?: 0L
                val headers = f.optObj("headers")?.let {
                    try {
                        MediaHeaders.validate(it.raw)
                    } catch (e: IllegalArgumentException) {
                        throw UcpException(ErrorCode.INVALID_PAYLOAD, e.message ?: "Invalid headers")
                    }
                } ?: emptyMap()
                val subtitles = f.raw["subtitles"]?.let {
                    try {
                        SubtitleValidator.validate(it)
                    } catch (e: IllegalArgumentException) {
                        throw UcpException(ErrorCode.INVALID_PAYLOAD, e.message ?: "Invalid subtitles")
                    }
                } ?: emptyList()
                engine.load(MediaItemInfo(uri, mime, title, headers, subtitles), autoplay, start)
            }
            Method.PLAYBACK_STOP -> engine.stop()
            else -> {
                if (engine.snapshot.status == PlaybackStatus.IDLE) {
                    throw UcpException(ErrorCode.INVALID_STATE, "No media is loaded")
                }
                when (method) {
                    Method.PLAYBACK_PLAY -> engine.play()
                    Method.PLAYBACK_PAUSE -> engine.pause()
                    Method.PLAYBACK_RESUME -> engine.resume()
                    Method.PLAYBACK_SEEK -> engine.seekTo(f.long("positionMs", 0, Long.MAX_VALUE / 4))
                    Method.PLAYBACK_SET_SPEED -> engine.setSpeed(f.double("speed", 0.25, 4.0).toFloat())
                    Method.PLAYBACK_SET_VOLUME -> engine.setVolume(f.double("volume", 0.0, 1.0).toFloat())
                    else -> throw UcpException(ErrorCode.UNKNOWN_METHOD, "Unknown method '$method'")
                }
            }
        }
        return linkedMapOf("snapshot" to engine.snapshot.toJson())
    }

    private fun capabilities(role: Role): JsonObject = linkedMapOf(
        "ucpVersions" to Ucp.SUPPORTED_VERSIONS,
        "deviceName" to deviceName(),
        "deviceId" to identity.deviceId,
        "receiverFingerprint" to identity.fingerprint,
        "role" to role.name,
        "permissions" to role.permissions.map { it.name }.sorted(),
        "maxMessageBytes" to Ucp.MAX_MESSAGE_BYTES,
        "features" to listOf("session", "snapshot", "events", "seek", "speed", "volume", "media-headers", "subtitles"),
        // Browser casting still uses direct URLs: the TV fetches the stream itself (with the headers above).
        "playbackMethods" to listOf("direct-url"),
        "media" to linkedMapOf(
            "uriSchemes" to listOf("http", "https"),
            "containers" to listOf("mp4", "webm", "hls", "dash", "mp3", "aac", "ogg", "flac", "wav"),
            "speedRange" to listOf(0.25, 4.0),
        ),
    )

    // ---- events from the engine and the session manager -----------------------------------------------

    override fun onSnapshot(snapshot: PlaybackSnapshot) {
        val payload = linkedMapOf<String, Any?>("snapshot" to snapshot.toJson())
        for (rec in records.values) {
            if (rec.phase == Phase.AUTHENTICATED) sendEvent(rec, EventName.PLAYBACK_STATE_CHANGED, payload)
        }
    }

    override fun onSessionEnded(session: Session, reason: SessionEndReason) {
        val owner = records[session.ownerConnectionId]
        if (owner != null && owner.phase == Phase.AUTHENTICATED && reason != SessionEndReason.CONNECTION_CLOSED) {
            sendEvent(owner, EventName.SESSION_TERMINATED, linkedMapOf("sessionId" to session.id, "reason" to reason.name))
        }
        // Playback belongs to its session: when the session ends the screen is released (unless a new session took over).
        if (reason != SessionEndReason.REPLACED) engine.stop()
    }

    // ---- sending --------------------------------------------------------------------------------------

    private fun sendEvent(rec: ConnectionRecord, name: String, payload: JsonObject) {
        safeSend(rec, EnvelopeCodec.encode(Envelope.event(rec.outSeq.incrementAndGet(), name, payload)))
    }

    private fun sendError(rec: ConnectionRecord, id: String?, code: ErrorCode, message: String) {
        val env = Envelope.errorResponse(id ?: "none", rec.outSeq.incrementAndGet(), UcpError(code, message))
        safeSend(rec, EnvelopeCodec.encode(env))
    }

    private fun safeSend(rec: ConnectionRecord, text: String) {
        try {
            rec.conn.send(text)
        } catch (e: Exception) {
            log.warn("Send failed: ${e.javaClass.simpleName}")
        }
    }

    private fun closeRecord(rec: ConnectionRecord, code: Int, reason: String) {
        val was = rec.phase
        rec.phase = Phase.CLOSED
        try {
            rec.conn.close(code, reason)
        } catch (e: Exception) {
            // already closed
        }
        if (was == Phase.AUTHENTICATED) notifyConnections()
    }
}
