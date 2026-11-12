package com.universalcast.sender

import com.universalcast.playback.MediaHeaders
import com.universalcast.playback.MediaUriValidator
import com.universalcast.playback.SubtitleValidator
import com.universalcast.playback.PlaybackSnapshot
import com.universalcast.playback.PlaybackStatus
import com.universalcast.security.SenderIdentity
import com.universalcast.ucp.Envelope
import com.universalcast.ucp.ErrorCode
import com.universalcast.ucp.Fields
import com.universalcast.ucp.Method
import com.universalcast.ucp.Ucp
import java.security.cert.CertificateException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED }

enum class FailureKind {
    /** TV off, wrong Wi-Fi, firewall… Retrying may help. */
    UNREACHABLE,
    /** The TV does not (or no longer) accept this phone: revoked or never paired. Pair again. */
    REJECTED,
    /** A host answered but its TLS key is not the pinned one: the TV's identity changed or it is not the TV. */
    PIN_MISMATCH,
}

/** Immutable view of the connection for the UI. [message] is a short user-facing status/error line. */
data class SenderState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val receiver: PairedReceiver? = null,
    val role: String? = null,
    val hasSession: Boolean = false,
    val snapshot: PlaybackSnapshot = PlaybackSnapshot(),
    /** Phone-clock time at which [snapshot] arrived; used for extrapolation (receiver clocks are never compared). */
    val snapshotReceivedAtMs: Long = 0,
    val failure: FailureKind? = null,
    val message: String? = null,
) {
    val canControl: Boolean get() = phase == ConnectionPhase.CONNECTED && role == "CONTROLLER"

    /** Smoothly extrapolated playback position while playing. */
    fun estimatedPositionMs(nowMs: Long): Long {
        val s = snapshot
        if (!s.isPlaying || s.status != PlaybackStatus.PLAYING) return s.positionMs
        val elapsed = (nowMs - snapshotReceivedAtMs).coerceAtLeast(0)
        val est = s.positionMs + (elapsed * s.speed).toLong()
        return s.durationMs?.let { est.coerceAtMost(it) } ?: est
    }
}

/**
 * Phone-side connection manager: pinned TLS connect, authentication, lazy session creation, playback commands,
 * state synchronisation from receiver events, heartbeat and automatic reconnect.
 *
 * Every blocking operation runs on one worker thread, which also keeps request sequence numbers strictly
 * ordered (the receiver rejects out-of-order requests). All public methods are non-blocking and thread-safe;
 * results arrive through [addListener] / [state].
 */
class SenderController(
    private val identity: SenderIdentity,
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectTimeoutMs: Long = 4_000,
    private val heartbeatMs: Long = 10_000,
    private val reconnectDelaysMs: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 30_000L, 30_000L), // ~2 min: rides out a Wi-Fi nap in the background
) {
    private val worker = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "ucp-sender").also { it.isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<(SenderState) -> Unit>()

    @Volatile
    var state = SenderState()
        private set

    /** Called on the worker thread after every successful authentication (e.g. to persist "last connected"). */
    @Volatile
    var onAuthenticated: ((PairedReceiver) -> Unit)? = null

    // Worker-thread-owned.
    private var client: UcpClient? = null
    private var sessionId: String? = null
    private var target: PairedReceiver? = null
    private var preferredHosts: List<String> = emptyList()
    private var wantConnected = false
    private var heartbeat: ScheduledFuture<*>? = null
    private var reconnect: ScheduledFuture<*>? = null

    @Volatile
    private var generation = 0

    fun addListener(l: (SenderState) -> Unit) { listeners.add(l) }
    fun removeListener(l: (SenderState) -> Unit) { listeners.remove(l) }

    // ---- public API --------------------------------------------------------------------------------------

    /** Connects to [receiver]. [preferredHosts] (e.g. from mDNS) are tried before the stored addresses. */
    fun connect(receiver: PairedReceiver, preferredHosts: List<String> = emptyList()) = submit {
        cancelReconnect()
        dropClient()
        target = receiver
        this.preferredHosts = preferredHosts
        wantConnected = true
        attemptConnect(0, reconnecting = false)
    }

    fun disconnect() = submit {
        wantConnected = false
        cancelReconnect()
        dropClient()
        update { SenderState(phase = ConnectionPhase.DISCONNECTED, receiver = it.receiver) }
    }

    fun shutdown() {
        submit {
            wantConnected = false
            cancelReconnect()
            dropClient()
        }
        worker.shutdown()
    }

    /** Casts [url] (the "Cast a link" field and shared links). */
    fun load(url: String, title: String? = null, autoplay: Boolean = true) =
        load(MediaCast(url, title = title, autoplay = autoplay))

    /**
     * Casts [cast]: optional [MediaCast.mimeType] tells the TV the format when the URL doesn't (HLS without ".m3u8"),
     * [MediaCast.headers] are the request headers the stream needs and [MediaCast.subtitles] side-loaded text tracks.
     * Anything the receiver would reject is refused here first, with a user-facing message.
     */
    fun load(cast: MediaCast) {
        val clean = try {
            MediaUriValidator.validateUri(cast.uri.trim())
        } catch (e: RuntimeException) { // UcpException or IllegalArgumentException for malformed input
            note("Enter a valid http:// or https:// link.")
            return
        }
        val headers = try {
            MediaHeaders.validate(cast.headers)
        } catch (e: IllegalArgumentException) {
            note("This stream needs request headers the TV doesn't accept.")
            return
        }
        val subtitles = cast.subtitles.map { BrowserCast.toWire(it) }
        try {
            SubtitleValidator.validate(subtitles)
        } catch (e: IllegalArgumentException) {
            note("The selected subtitles can't be sent to the TV.")
            return
        }
        val payload = linkedMapOf<String, Any?>("uri" to clean, "autoplay" to cast.autoplay)
        if (!cast.title.isNullOrBlank()) payload["title"] = cast.title.trim().take(200)
        if (cast.mimeType != null) payload["mimeType"] = cast.mimeType
        if (headers.isNotEmpty()) payload["headers"] = LinkedHashMap<String, Any?>(headers)
        if (subtitles.isNotEmpty()) payload["subtitles"] = subtitles
        control(Method.MEDIA_LOAD, payload)
    }

    fun play() = control(Method.PLAYBACK_PLAY)
    fun pause() = control(Method.PLAYBACK_PAUSE)
    fun resume() = control(Method.PLAYBACK_RESUME)
    fun stop() = control(Method.PLAYBACK_STOP)
    fun seek(positionMs: Long) = control(Method.PLAYBACK_SEEK, mapOf("positionMs" to positionMs.coerceAtLeast(0)))
    fun setSpeed(speed: Double) = control(Method.PLAYBACK_SET_SPEED, mapOf("speed" to speed.coerceIn(0.25, 4.0)))
    fun setVolume(volume: Double) = control(Method.PLAYBACK_SET_VOLUME, mapOf("volume" to volume.coerceIn(0.0, 1.0)))

    fun refresh() = submit { client?.let { refreshSnapshot(it) } }

    // ---- state plumbing ----------------------------------------------------------------------------------

    private fun submit(block: () -> Unit) {
        try {
            worker.execute {
                try {
                    block()
                } catch (e: Exception) {
                    note("Unexpected error: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        } catch (e: RejectedExecutionException) {
            // shut down
        }
    }

    private fun update(f: (SenderState) -> SenderState) {
        val s = synchronized(this) { f(state).also { state = it } }
        listeners.forEach { l -> runCatching { l(s) } }
    }

    private fun note(message: String?) = update { it.copy(message = message) }

    private fun fail(kind: FailureKind, message: String) =
        update { it.copy(phase = ConnectionPhase.FAILED, failure = kind, message = message, role = null, hasSession = false) }

    // ---- connecting --------------------------------------------------------------------------------------

    private fun attemptConnect(attempt: Int, reconnecting: Boolean) {
        val r = target ?: return
        update {
            it.copy(
                phase = if (reconnecting) ConnectionPhase.RECONNECTING else ConnectionPhase.CONNECTING,
                receiver = r, failure = null, message = if (reconnecting) RECONNECTING_MESSAGE else null,
            )
        }
        var pinProblem = false
        for (host in (preferredHosts + r.addresses).distinct().take(4)) {
            if (!wantConnected) return
            val c = UcpClient(identity, host, r.port, r.fingerprint)
            try {
                if (!c.connect(connectTimeoutMs, r.path)) {
                    System.err.println("UCP connect to $host:${r.port} failed: ${c.lastError?.let { it.javaClass.simpleName + ": " + it.message } ?: "no connection within ${connectTimeoutMs}ms"}")
                    if (generateSequence<Throwable>(c.lastError) { it.cause }.any { it is CertificateException }) pinProblem = true
                    c.close()
                    continue
                }
                val info = c.authenticate()
                attach(c, r, info.role)
                return
            } catch (e: UcpClientException) {
                System.err.println("UCP auth with $host:${r.port} failed: ${e.code} ${e.message}")
                c.close()
                if (e.code == ErrorCode.AUTH_FAILED) {
                    wantConnected = false
                    fail(FailureKind.REJECTED, "${r.name} does not recognise this phone (it may have been removed). Pair again.")
                    return
                }
            } catch (e: Exception) {
                System.err.println("UCP connect to $host:${r.port} error: ${e.javaClass.simpleName}: ${e.message}")
                c.close()
            }
        }
        if (pinProblem) {
            wantConnected = false
            fail(FailureKind.PIN_MISMATCH, "The security identity of ${r.name} changed, or this is not that TV. Remove it and pair again.")
        } else if (reconnecting) {
            scheduleReconnect(attempt + 1)
        } else {
            fail(FailureKind.UNREACHABLE, "Could not reach ${r.name}. Check that the TV is on and on the same Wi-Fi.")
        }
    }

    private fun attach(c: UcpClient, r: PairedReceiver, role: String) {
        generation++
        val gen = generation
        client = c
        sessionId = null
        c.onClosed = { code -> submit { onClosed(gen, code) } }
        startEventPump(c, gen)
        heartbeat = worker.scheduleWithFixedDelay({ beat(gen) }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS)
        // Before publishing CONNECTED: anyone who observes CONNECTED (volatile write) also sees the hook's effects.
        runCatching { onAuthenticated?.invoke(r) }
        update {
            it.copy(
                phase = ConnectionPhase.CONNECTED, receiver = r, role = role, hasSession = false,
                failure = null, message = null, snapshot = PlaybackSnapshot(), snapshotReceivedAtMs = 0,
            )
        }
        refreshSnapshot(c)
    }

    private fun startEventPump(c: UcpClient, gen: Int) {
        Thread({
            try {
                while (gen == generation) {
                    val e = c.events.poll(500, TimeUnit.MILLISECONDS)
                    if (e != null) submit { if (gen == generation) handleEvent(e) } else if (!c.isOpen) break
                }
            } catch (_: InterruptedException) {
            }
        }, "ucp-sender-events").apply { isDaemon = true; start() }
    }

    private fun handleEvent(e: Envelope) {
        when (e.name) {
            "playback.stateChanged" -> runCatching {
                val s = PlaybackSnapshot.fromJson(Fields(e.payload).obj("snapshot"))
                if (s.revision >= state.snapshot.revision) update { it.copy(snapshot = s, snapshotReceivedAtMs = clock()) }
            }
            "session.terminated" -> {
                val id = Fields(e.payload).optString("sessionId", 64)
                if (id == null || id == sessionId) {
                    sessionId = null
                    val replaced = Fields(e.payload).optString("reason", 32) == "REPLACED"
                    update { it.copy(hasSession = false, message = if (replaced) "Another phone took over control of this TV." else it.message) }
                }
            }
        }
    }

    private fun onClosed(gen: Int, code: Int) {
        if (gen != generation) return // superseded or user-initiated
        generation++
        cancelHeartbeat()
        client = null
        sessionId = null
        when {
            code == Ucp.CLOSE_REVOKED -> {
                wantConnected = false
                fail(FailureKind.REJECTED, "This phone was removed on the TV. Pair again to reconnect.")
            }
            code == Ucp.CLOSE_AUTH_FAILED -> {
                wantConnected = false
                fail(FailureKind.REJECTED, "The TV rejected this phone.")
            }
            wantConnected -> scheduleReconnect(0)
            else -> update { it.copy(phase = ConnectionPhase.DISCONNECTED, role = null, hasSession = false) }
        }
    }

    private fun scheduleReconnect(attempt: Int) {
        val name = target?.name ?: "the TV"
        if (attempt >= reconnectDelaysMs.size) {
            wantConnected = false
            fail(FailureKind.UNREACHABLE, "Lost connection to $name.")
            return
        }
        update { it.copy(phase = ConnectionPhase.RECONNECTING, role = null, hasSession = false, message = RECONNECTING_MESSAGE) }
        reconnect = worker.schedule({
            try {
                if (wantConnected && client == null) attemptConnect(attempt, reconnecting = true)
            } catch (e: Exception) {
                fail(FailureKind.UNREACHABLE, "Lost connection to $name.")
            }
        }, reconnectDelaysMs[attempt], TimeUnit.MILLISECONDS)
    }

    private fun beat(gen: Int) {
        if (gen != generation) return
        val c = client ?: return
        try {
            if (c.request(Method.PING, timeoutMs = 4_000).ok != true) c.close()
        } catch (e: Exception) {
            c.close() // triggers onClosed → reconnect
        }
    }

    private fun cancelHeartbeat() { heartbeat?.cancel(false); heartbeat = null }
    private fun cancelReconnect() { reconnect?.cancel(false); reconnect = null }

    private fun dropClient() {
        generation++ // stale callbacks from the old client are ignored
        cancelHeartbeat()
        val c = client
        client = null
        sessionId = null
        c?.onClosed = null
        c?.close()
    }

    // ---- commands ----------------------------------------------------------------------------------------

    private fun refreshSnapshot(c: UcpClient) {
        try {
            val s = c.snapshot()
            update { it.copy(snapshot = s, snapshotReceivedAtMs = clock()) }
        } catch (e: Exception) {
            // not fatal: state events will follow
        }
    }

    private fun control(method: String, payload: Map<String, Any?> = emptyMap()) = submit {
        val c = client
        if (c == null || state.phase != ConnectionPhase.CONNECTED) {
            note(if (state.phase == ConnectionPhase.RECONNECTING) RECONNECTING_MESSAGE else "Not connected to a TV.")
            return@submit
        }
        if (state.role != "CONTROLLER") {
            note("This phone can only watch this TV (observer).")
            return@submit
        }
        try {
            val r = withSession(c, method, payload)
            if (r.ok == true) {
                val snap = (r.payload["snapshot"] as? Map<*, *>)?.let {
                    runCatching { PlaybackSnapshot.fromJson(Fields(r.payload).obj("snapshot")) }.getOrNull()
                }
                update { st -> if (snap != null && snap.revision >= st.snapshot.revision) st.copy(snapshot = snap, snapshotReceivedAtMs = clock(), message = null) else st.copy(message = null) }
            } else {
                note(describe(r.error?.code, r.error?.message))
            }
        } catch (e: UcpClientException) {
            if (e.connectionLost) connectionLost(c) else note(describe(e.code, e.message))
        }
    }

    /**
     * The socket of [c] turned out to be closed while commands were still queued on this worker. The asynchronous
     * close notification is queued *behind* those commands, so handle the loss right here: every later queued
     * command then sees the reconnecting state instead of hitting the dead socket, and the stale notification is
     * ignored (generation check).
     */
    private fun connectionLost(c: UcpClient) {
        if (c !== client) return
        onClosed(generation, c.closeCode ?: CLOSE_ABNORMAL)
        c.onClosed = null
        c.close()
    }

    private fun withSession(c: UcpClient, method: String, payload: Map<String, Any?>): Envelope {
        val sid = sessionId ?: createSession(c)
        var r = c.request(method, payload, sid)
        if (r.ok != true && (r.error?.code == ErrorCode.SESSION_INVALID || r.error?.code == ErrorCode.SESSION_REQUIRED)) {
            sessionId = null
            r = c.request(method, payload, createSession(c)) // another phone took over; taking back is the user's explicit action
        }
        return r
    }

    private fun createSession(c: UcpClient): String {
        val id = Fields(c.call(Method.SESSION_CREATE).payload).string("sessionId", 64)
        sessionId = id
        update { it.copy(hasSession = true) }
        return id
    }

    private companion object {
        const val RECONNECTING_MESSAGE = "Connection lost. Reconnecting…"
        /** RFC 6455 "abnormal closure" (no close frame), used when the socket died without telling us why. */
        const val CLOSE_ABNORMAL = 1006
    }

    private fun describe(code: ErrorCode?, message: String?): String = when (code) {
        ErrorCode.UNSUPPORTED_MEDIA -> "The TV can't play that link. Use a direct http(s) video, audio, HLS or DASH URL."
        ErrorCode.FORBIDDEN -> "This phone is not allowed to control the TV."
        ErrorCode.INVALID_STATE -> "Nothing is loaded on the TV yet."
        ErrorCode.PLAYBACK_ERROR -> "The TV could not play this media."
        ErrorCode.INVALID_PAYLOAD -> "The TV refused that request."
        else -> message ?: "Request failed."
    }
}
