package com.universalcast.sender

import com.universalcast.playback.MediaHeaders
import com.universalcast.playback.MediaItemInfo
import com.universalcast.playback.MediaTrack
import com.universalcast.playback.MediaUriValidator
import com.universalcast.playback.PlaybackQueue
import com.universalcast.playback.SubtitleInfo
import com.universalcast.playback.SubtitleValidator
import com.universalcast.playback.PlaybackSnapshot
import com.universalcast.playback.PlaybackStatus
import com.universalcast.playback.RemoteKey
import com.universalcast.playback.SubtitleStyle
import com.universalcast.playback.TrackKind
import com.universalcast.playback.VideoScaleMode
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
    val isDummyMode: Boolean = false,
    val dummyLastAction: String? = null,
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
    private var dummyActive = false
    private val dummyQueue = PlaybackQueue()
    private var heartbeat: ScheduledFuture<*>? = null
    private var reconnect: ScheduledFuture<*>? = null

    @Volatile
    private var generation = 0

    fun addListener(l: (SenderState) -> Unit) { listeners.add(l) }
    fun removeListener(l: (SenderState) -> Unit) { listeners.remove(l) }

    // ---- public API --------------------------------------------------------------------------------------

    /** Connects to [receiver]. [preferredHosts] (e.g. from mDNS) are tried before the stored addresses. */
    fun connect(receiver: PairedReceiver, preferredHosts: List<String> = emptyList()) {
        update { it.copy(phase = ConnectionPhase.CONNECTING, receiver = receiver, failure = null, message = null, isDummyMode = false, dummyLastAction = null) }
        submit {
            dummyActive = false
            cancelReconnect()
            dropClient()
            target = receiver
            this.preferredHosts = preferredHosts
            wantConnected = true
            attemptConnect(0, reconnecting = false)
        }
    }

    /**
     * Activates Developer Dummy TV Mode: simulates a live connected TV receiver in-process so all Remote,
     * Touchpad, D-Pad, Keyboard, Track Switching, Queue and Browser Cast features can be tested without a physical TV.
     */
    fun startDummyMode() = submit {
        wantConnected = false
        cancelReconnect()
        dropClient()
        dummyActive = true
        val now = clock()
        val dummyReceiver = PairedReceiver(
            fingerprint = "d".repeat(64),
            deviceId = "dummy-dev-tv",
            name = "Dummy TV (Dev Simulator)",
            addresses = listOf("127.0.0.1"),
            port = 8765,
            path = Ucp.WS_PATH,
            role = "CONTROLLER",
            pairedAtMs = now,
            lastConnectedAtMs = now,
        )
        dummyQueue.clear()
        val ep1 = MediaItemInfo("https://demo.universalcast.local/ep1.m3u8", "application/x-mpegURL", "Episode 1 — Romance Dawn")
        val ep2 = MediaItemInfo("https://demo.universalcast.local/ep2.m3u8", "application/x-mpegURL", "Episode 2 — Enter Zoro")
        val ep3 = MediaItemInfo("https://demo.universalcast.local/ep3.m3u8", "application/x-mpegURL", "Episode 3 — Morgan vs. Luffy")
        dummyQueue.add(ep1, playNext = false, startPositionMs = 0L, isCurrentlyIdle = true)
        dummyQueue.add(ep2, playNext = false, startPositionMs = 0L, isCurrentlyIdle = false)
        dummyQueue.add(ep3, playNext = false, startPositionMs = 0L, isCurrentlyIdle = false)
        val (qList, qIdx) = dummyQueue.summaries()
        val snap = PlaybackSnapshot(
            status = PlaybackStatus.PLAYING,
            mediaUri = ep1.uri,
            title = ep1.title,
            positionMs = 125_000L,
            durationMs = 1_440_000L,
            bufferedPositionMs = 480_000L,
            isPlaying = true,
            speed = 1.0f,
            volume = 0.8f,
            revision = 1L,
            capturedAtMs = now,
            audioTracks = listOf(
                MediaTrack("a:0:0", TrackKind.AUDIO, "Japanese (Original · 2ch)", "ja", true),
                MediaTrack("a:0:1", TrackKind.AUDIO, "English Dub (5.1ch)", "en", false),
                MediaTrack("a:0:2", TrackKind.AUDIO, "Director Commentary", "en", false),
            ),
            subtitleTracks = listOf(
                MediaTrack("s:0:0", TrackKind.SUBTITLE, "English", "en", true),
                MediaTrack("s:0:1", TrackKind.SUBTITLE, "Dutch (Nederlands)", "nl", false),
                MediaTrack("s:0:2", TrackKind.SUBTITLE, "Spanish (Español)", "es", false),
            ),
            subtitlesEnabled = true,
            queue = qList,
            queueIndex = qIdx,
            mediaKind = "VIDEO",
        )
        target = dummyReceiver
        update {
            SenderState(
                phase = ConnectionPhase.CONNECTED,
                receiver = dummyReceiver,
                role = "CONTROLLER",
                hasSession = true,
                snapshot = snap,
                snapshotReceivedAtMs = now,
                isDummyMode = true,
                dummyLastAction = "📺 Dummy TV Simulator ready — test any control or gesture!",
            )
        }
    }

    fun disconnect() = submit {
        wantConnected = false
        dummyActive = false
        dummyQueue.clear()
        cancelReconnect()
        dropClient()
        update { SenderState(phase = ConnectionPhase.DISCONNECTED, receiver = it.receiver?.takeIf { r -> r.deviceId != "dummy-dev-tv" }) }
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
    fun load(url: String, title: String? = null, autoplay: Boolean = true, startPositionMs: Long = 0) =
        load(MediaCast(url, title = title, autoplay = autoplay, startPositionMs = startPositionMs))

    private fun buildMediaPayload(cast: MediaCast): LinkedHashMap<String, Any?>? {
        val clean = try {
            MediaUriValidator.validateUri(cast.uri.trim())
        } catch (e: RuntimeException) { // UcpException or IllegalArgumentException for malformed input
            note("Enter a valid http:// or https:// link.")
            return null
        }
        val headers = try {
            MediaHeaders.validate(cast.headers)
        } catch (e: IllegalArgumentException) {
            note("This stream needs request headers the TV doesn't accept.")
            return null
        }
        val subtitles = cast.subtitles.map { BrowserCast.toWire(it) }
        try {
            SubtitleValidator.validate(subtitles)
        } catch (e: IllegalArgumentException) {
            note("The selected subtitles can't be sent to the TV.")
            return null
        }
        val payload = linkedMapOf<String, Any?>("uri" to clean, "autoplay" to cast.autoplay)
        if (!cast.title.isNullOrBlank()) payload["title"] = cast.title.trim().take(200)
        if (cast.mimeType != null) payload["mimeType"] = cast.mimeType
        if (cast.startPositionMs > 0) payload["startPositionMs"] = cast.startPositionMs
        if (headers.isNotEmpty()) payload["headers"] = LinkedHashMap<String, Any?>(headers)
        if (subtitles.isNotEmpty()) payload["subtitles"] = subtitles
        return payload
    }

    /**
     * Casts [cast]: optional [MediaCast.mimeType] tells the TV the format when the URL doesn't (HLS without ".m3u8"),
     * [MediaCast.headers] are the request headers the stream needs and [MediaCast.subtitles] side-loaded text tracks.
     * Anything the receiver would reject is refused here first, with a user-facing message.
     */
    fun load(cast: MediaCast) {
        val payload = buildMediaPayload(cast) ?: return
        control(Method.MEDIA_LOAD, payload)
    }

    /** Adds [cast] to the receiver's playback queue (starts playing immediately if the TV is idle). */
    fun enqueue(cast: MediaCast, playNext: Boolean = false) {
        val payload = buildMediaPayload(cast) ?: return
        if (playNext) payload["playNext"] = true
        control(Method.QUEUE_ADD, payload)
    }

    fun enqueue(url: String, title: String? = null, playNext: Boolean = false) =
        enqueue(MediaCast(url, title = title), playNext = playNext)

    fun queueRemove(itemId: String) = control(Method.QUEUE_REMOVE, mapOf("itemId" to itemId))
    fun queueReorder(itemId: String, toIndex: Int) = control(Method.QUEUE_REORDER, mapOf("itemId" to itemId, "toIndex" to toIndex.toLong()))
    fun queueClear() = control(Method.QUEUE_CLEAR)
    fun queueNext() = control(Method.QUEUE_NEXT)
    fun queuePrevious() = control(Method.QUEUE_PREVIOUS)
    fun queueJump(itemId: String) = control(Method.QUEUE_JUMP, mapOf("itemId" to itemId))

    fun play() = control(Method.PLAYBACK_PLAY)
    fun pause() = control(Method.PLAYBACK_PAUSE)
    fun resume() = control(Method.PLAYBACK_RESUME)
    fun stop() = control(Method.PLAYBACK_STOP)
    fun seek(positionMs: Long) = control(Method.PLAYBACK_SEEK, mapOf("positionMs" to positionMs.coerceAtLeast(0)))
    fun setSpeed(speed: Double) = control(Method.PLAYBACK_SET_SPEED, mapOf("speed" to speed.coerceIn(0.25, 4.0)))
    fun setVolume(volume: Double) = control(Method.PLAYBACK_SET_VOLUME, mapOf("volume" to volume.coerceIn(0.0, 1.0)))

    fun selectTracks(audioTrackId: String? = null, subtitleTrackId: String? = null) {
        val payload = linkedMapOf<String, Any?>()
        if (audioTrackId != null) payload["audioTrackId"] = audioTrackId
        if (subtitleTrackId != null) payload["subtitleTrackId"] = subtitleTrackId
        if (payload.isEmpty()) return
        control(Method.PLAYBACK_SELECT_TRACKS, payload)
    }

    fun setSubtitleOffset(offsetMs: Long) =
        control(Method.PLAYBACK_SET_SUBTITLE_OFFSET, mapOf("offsetMs" to offsetMs.coerceIn(-60_000L, 60_000L)))

    fun setSubtitleStyle(style: SubtitleStyle) =
        control(Method.PLAYBACK_SET_SUBTITLE_STYLE, style.toJson())

    fun setVideoScale(scale: VideoScaleMode) =
        control(Method.PLAYBACK_SET_VIDEO_SCALE, mapOf("scale" to scale.name))

    fun setSleepTimer(durationMs: Long) =
        control(Method.PLAYBACK_SET_SLEEP_TIMER, mapOf("durationMs" to durationMs.coerceIn(0L, 24L * 3600 * 1000)))

    fun remoteKey(key: RemoteKey) =
        control(Method.REMOTE_KEY, mapOf("key" to key.name))

    fun remoteText(text: String, submit: Boolean = false) {
        val clean = text.filter { it.code >= 0x20 || it == '\n' || it == '\t' }.take(1024)
        if (clean.isEmpty()) return
        control(Method.REMOTE_TEXT, mapOf("text" to clean, "submit" to submit))
    }

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
                val updated = r.copy(addresses = (listOf(host) + r.addresses).distinct().take(8))
                target = updated
                attach(c, updated, info.role)
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
        if (dummyActive && state.isDummyMode) {
            handleDummyControl(method, payload)
            return@submit
        }
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

    private fun handleDummyControl(method: String, payload: Map<String, Any?>) {
        val now = clock()
        val cur = state.snapshot
        val posNow = state.estimatedPositionMs(now)
        var actionText = "📺 TV executed $method"
        var nextSnap = cur.copy(positionMs = posNow, revision = cur.revision + 1, capturedAtMs = now)

        fun loadDummyItem(item: MediaItemInfo, autoplay: Boolean, startPos: Long) {
            val (qList, qIdx) = dummyQueue.summaries()
            val subTracks = if (item.subtitles.isNotEmpty()) {
                item.subtitles.mapIndexed { idx, s ->
                    MediaTrack("s:0:$idx", TrackKind.SUBTITLE, s.label ?: s.language ?: "Subtitle ${idx + 1}", s.language, idx == 0)
                }
            } else cur.subtitleTracks
            nextSnap = nextSnap.copy(
                status = if (autoplay) PlaybackStatus.PLAYING else PlaybackStatus.READY,
                mediaUri = item.uri,
                title = item.title ?: item.uri.substringAfterLast('/'),
                positionMs = startPos,
                durationMs = 1_440_000L,
                bufferedPositionMs = (startPos + 300_000L).coerceAtMost(1_440_000L),
                isPlaying = autoplay,
                subtitleTracks = subTracks,
                subtitlesEnabled = subTracks.isNotEmpty(),
                queue = qList,
                queueIndex = qIdx,
                error = null,
            )
        }

        when (method) {
            Method.MEDIA_LOAD -> {
                val uri = payload["uri"] as? String ?: "https://demo.universalcast.local/video.mp4"
                val title = payload["title"] as? String
                val mime = payload["mimeType"] as? String
                val autoplay = (payload["autoplay"] as? Boolean) ?: true
                val start = (payload["startPositionMs"] as? Long) ?: 0L
                val item = MediaItemInfo(uri, mime, title)
                dummyQueue.onDirectLoad(item, start)
                loadDummyItem(item, autoplay, start)
                actionText = "📺 TV loaded: ${title ?: uri.take(36)}"
            }
            Method.PLAYBACK_PLAY, Method.PLAYBACK_RESUME -> {
                nextSnap = nextSnap.copy(status = PlaybackStatus.PLAYING, isPlaying = true)
                actionText = "📺 TV: ▶ Playing"
            }
            Method.PLAYBACK_PAUSE -> {
                nextSnap = nextSnap.copy(status = PlaybackStatus.PAUSED, isPlaying = false)
                actionText = "📺 TV: ⏸ Paused at ${posNow / 1000}s"
            }
            Method.PLAYBACK_STOP -> {
                dummyQueue.clear()
                nextSnap = PlaybackSnapshot(
                    status = PlaybackStatus.IDLE,
                    speed = cur.speed,
                    volume = cur.volume,
                    revision = cur.revision + 1,
                    capturedAtMs = now,
                    subtitleStyle = cur.subtitleStyle,
                    videoScale = cur.videoScale,
                )
                actionText = "📺 TV: ⏹ Stopped playback"
            }
            Method.PLAYBACK_SEEK -> {
                val targetPos = ((payload["positionMs"] as? Long) ?: 0L).coerceIn(0L, cur.durationMs ?: Long.MAX_VALUE)
                nextSnap = nextSnap.copy(positionMs = targetPos)
                actionText = "📺 TV Seek → ${targetPos / 1000}s"
            }
            Method.PLAYBACK_SET_SPEED -> {
                val sp = ((payload["speed"] as? Number)?.toFloat() ?: 1.0f).coerceIn(0.25f, 4.0f)
                nextSnap = nextSnap.copy(speed = sp)
                actionText = "📺 TV Speed → ${sp}×"
            }
            Method.PLAYBACK_SET_VOLUME -> {
                val vol = ((payload["volume"] as? Number)?.toFloat() ?: 1.0f).coerceIn(0f, 1f)
                nextSnap = nextSnap.copy(volume = vol)
                actionText = "📺 TV Volume → ${(vol * 100).toInt()}%"
            }
            Method.PLAYBACK_SELECT_TRACKS -> {
                val audioId = payload["audioTrackId"] as? String
                val subId = payload["subtitleTrackId"] as? String
                val nextAudio = if (audioId != null) cur.audioTracks.map { it.copy(selected = it.id == audioId) } else cur.audioTracks
                val subsOff = subId.equals("off", ignoreCase = true)
                val nextSubs = when {
                    subsOff -> cur.subtitleTracks.map { it.copy(selected = false) }
                    subId != null -> cur.subtitleTracks.map { it.copy(selected = it.id == subId) }
                    else -> cur.subtitleTracks
                }
                val enabled = when {
                    subsOff -> false
                    subId != null -> nextSubs.any { it.selected }
                    else -> cur.subtitlesEnabled
                }
                nextSnap = nextSnap.copy(audioTracks = nextAudio, subtitleTracks = nextSubs, subtitlesEnabled = enabled)
                val chosenLabel = if (audioId != null) nextAudio.firstOrNull { it.selected }?.label else if (subsOff) "Subtitles Off" else nextSubs.firstOrNull { it.selected }?.label
                actionText = "📺 TV Track → ${chosenLabel ?: "Updated"}"
            }
            Method.PLAYBACK_SET_SUBTITLE_OFFSET -> {
                val off = (payload["offsetMs"] as? Long) ?: 0L
                nextSnap = nextSnap.copy(subtitleOffsetMs = off)
                actionText = "📺 TV Subtitle Delay → ${if (off > 0) "+" else ""}${off / 1000.0}s"
            }
            Method.PLAYBACK_SET_SUBTITLE_STYLE -> {
                val style = SubtitleStyle.fromJson(Fields(payload))
                nextSnap = nextSnap.copy(subtitleStyle = style)
                actionText = "📺 TV Subtitle Style → Scale ${style.fontScale}×, Bg ${(style.backgroundOpacity * 100).toInt()}%"
            }
            Method.PLAYBACK_SET_VIDEO_SCALE -> {
                val sc = (payload["scale"] as? String)?.let { VideoScaleMode.fromWire(it) } ?: VideoScaleMode.FIT
                nextSnap = nextSnap.copy(videoScale = sc)
                actionText = "📺 TV Aspect Ratio → ${sc.name}"
            }
            Method.PLAYBACK_SET_SLEEP_TIMER -> {
                val dur = (payload["durationMs"] as? Long) ?: 0L
                nextSnap = nextSnap.copy(sleepTimerRemainingMs = dur.takeIf { it > 0 })
                actionText = if (dur > 0) "📺 TV Sleep Timer → ${dur / 60_000L} min" else "📺 TV Sleep Timer → Off"
            }
            Method.QUEUE_ADD -> {
                val uri = payload["uri"] as? String ?: "https://demo.universalcast.local/item.mp4"
                val title = payload["title"] as? String
                val mime = payload["mimeType"] as? String
                val playNext = (payload["playNext"] as? Boolean) ?: false
                val start = (payload["startPositionMs"] as? Long) ?: 0L
                val item = MediaItemInfo(uri, mime, title)
                val immediate = dummyQueue.add(item, playNext, start, cur.status == PlaybackStatus.IDLE)
                if (immediate != null) {
                    loadDummyItem(immediate.item, autoplay = true, startPos = start)
                } else {
                    val (qList, qIdx) = dummyQueue.summaries()
                    nextSnap = nextSnap.copy(queue = qList, queueIndex = qIdx)
                }
                actionText = "📺 TV Queue + ${title ?: uri.take(30)}"
            }
            Method.QUEUE_REMOVE -> {
                val id = payload["itemId"] as? String ?: ""
                dummyQueue.remove(id)
                val (qList, qIdx) = dummyQueue.summaries()
                nextSnap = nextSnap.copy(queue = qList, queueIndex = qIdx)
                actionText = "📺 TV Queue removed item"
            }
            Method.QUEUE_REORDER -> {
                val id = payload["itemId"] as? String ?: ""
                val toIdx = ((payload["toIndex"] as? Long) ?: 0L).toInt()
                dummyQueue.move(id, toIdx)
                val (qList, qIdx) = dummyQueue.summaries()
                nextSnap = nextSnap.copy(queue = qList, queueIndex = qIdx)
                actionText = "📺 TV Queue reordered"
            }
            Method.QUEUE_CLEAR -> {
                dummyQueue.clear()
                nextSnap = nextSnap.copy(queue = emptyList(), queueIndex = -1)
                actionText = "📺 TV Queue cleared"
            }
            Method.QUEUE_NEXT -> {
                val next = dummyQueue.next()
                if (next != null) {
                    loadDummyItem(next.item, autoplay = true, startPos = next.startPositionMs)
                    actionText = "📺 TV Skipped Next → ${next.item.title ?: next.item.uri}"
                }
            }
            Method.QUEUE_PREVIOUS -> {
                val prev = dummyQueue.previous()
                if (prev != null) {
                    loadDummyItem(prev.item, autoplay = true, startPos = prev.startPositionMs)
                    actionText = "📺 TV Skipped Previous → ${prev.item.title ?: prev.item.uri}"
                }
            }
            Method.QUEUE_JUMP -> {
                val id = payload["itemId"] as? String ?: ""
                val target = dummyQueue.jumpTo(id)
                if (target != null) {
                    loadDummyItem(target.item, autoplay = true, startPos = target.startPositionMs)
                    actionText = "📺 TV Jumped to → ${target.item.title ?: target.item.uri}"
                }
            }
            Method.REMOTE_KEY -> {
                val keyName = payload["key"] as? String ?: ""
                val key = RemoteKey.fromWire(keyName)
                when (key) {
                    RemoteKey.PLAY_PAUSE -> {
                        val playing = !cur.isPlaying
                        nextSnap = nextSnap.copy(status = if (playing) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED, isPlaying = playing)
                        actionText = "📺 TV Remote Key: PLAY_PAUSE (${if (playing) "Playing" else "Paused"})"
                    }
                    RemoteKey.REWIND -> {
                        val p = (posNow - 10_000L).coerceAtLeast(0L)
                        nextSnap = nextSnap.copy(positionMs = p)
                        actionText = "📺 TV Remote Key: ⏪ REWIND (−10s → ${p / 1000}s)"
                    }
                    RemoteKey.FAST_FORWARD -> {
                        val p = (posNow + 10_000L).coerceAtMost(cur.durationMs ?: Long.MAX_VALUE)
                        nextSnap = nextSnap.copy(positionMs = p)
                        actionText = "📺 TV Remote Key: ⏩ FAST_FORWARD (+10s → ${p / 1000}s)"
                    }
                    RemoteKey.VOLUME_UP -> {
                        val v = (cur.volume + 0.08f).coerceIn(0f, 1f)
                        nextSnap = nextSnap.copy(volume = v)
                        actionText = "📺 TV Remote Key: 🔊 VOLUME_UP (${(v * 100).toInt()}%)"
                    }
                    RemoteKey.VOLUME_DOWN -> {
                        val v = (cur.volume - 0.08f).coerceIn(0f, 1f)
                        nextSnap = nextSnap.copy(volume = v)
                        actionText = "📺 TV Remote Key: 🔉 VOLUME_DOWN (${(v * 100).toInt()}%)"
                    }
                    RemoteKey.MUTE -> {
                        val v = if (cur.volume > 0.01f) 0f else 0.8f
                        nextSnap = nextSnap.copy(volume = v)
                        actionText = "📺 TV Remote Key: 🔇 MUTE (${if (v == 0f) "Muted" else "Unmuted"})"
                    }
                    else -> {
                        actionText = "📺 TV Remote Key: ${key?.name ?: keyName}"
                    }
                }
            }
            Method.REMOTE_TEXT -> {
                val text = payload["text"] as? String ?: ""
                val submit = payload["submit"] == true
                actionText = "📺 TV Keyboard Input: \"$text\"${if (submit) " [Enter]" else ""}"
            }
        }
        update { st ->
            st.copy(
                snapshot = nextSnap,
                snapshotReceivedAtMs = now,
                message = null,
                dummyLastAction = actionText,
            )
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
