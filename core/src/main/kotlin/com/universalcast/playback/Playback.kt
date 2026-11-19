package com.universalcast.playback

import com.universalcast.ucp.ErrorCode
import com.universalcast.ucp.Fields
import com.universalcast.ucp.JsonObject
import com.universalcast.ucp.UcpException

enum class PlaybackStatus { IDLE, LOADING, BUFFERING, READY, PLAYING, PAUSED, ENDED, ERROR }

data class PlaybackErrorInfo(val code: String, val message: String, val recoverable: Boolean)

/**
 * What to play. [headers] are the extra HTTP request headers the stream needs (see [MediaHeaders]); they can carry
 * cookies, so they are never logged, never put in snapshots and never shown to other senders. [subtitles] are
 * side-loaded text tracks (see [SubtitleInfo]).
 */
data class MediaItemInfo(
    val uri: String,
    val mimeType: String? = null,
    val title: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleInfo> = emptyList(),
) {
    override fun toString() =
        "MediaItemInfo(uri=$uri, mimeType=$mimeType, title=$title, headers=${headers.keys}, subtitles=${subtitles.size})"
}

/** One external subtitle file. [language] is a BCP-47-like tag ("en", "pt-BR"), [label] a display name. */
data class SubtitleInfo(
    val uri: String,
    val mimeType: String,
    val language: String? = null,
    val label: String? = null,
)

enum class TrackKind { AUDIO, SUBTITLE }

/** An audio or subtitle track currently available in the active media item. */
data class MediaTrack(
    val id: String,
    val kind: TrackKind,
    val label: String,
    val language: String? = null,
    val selected: Boolean = false,
) {
    fun toJson(): JsonObject = linkedMapOf(
        "id" to id,
        "kind" to kind.name,
        "label" to label,
        "language" to language,
        "selected" to selected,
    )

    companion object {
        fun fromJson(f: Fields): MediaTrack {
            val kindName = f.string("kind", 16)
            val kind = TrackKind.values().firstOrNull { it.name == kindName }
                ?: throw UcpException(ErrorCode.INVALID_PAYLOAD, "Unknown track kind")
            return MediaTrack(
                id = f.string("id", 64),
                kind = kind,
                label = f.string("label", 128, 0),
                language = f.optString("language", 32, 0),
                selected = f.optBool("selected") ?: false,
            )
        }
    }
}

/** Visual appearance of subtitles on the TV receiver. */
data class SubtitleStyle(
    val fontScale: Float = 1.0f,
    val backgroundOpacity: Float = 0.6f,
    val bottomPaddingFraction: Float = 0.08f,
) {
    fun toJson(): JsonObject = linkedMapOf(
        "fontScale" to fontScale.toDouble(),
        "backgroundOpacity" to backgroundOpacity.toDouble(),
        "bottomPaddingFraction" to bottomPaddingFraction.toDouble(),
    )

    companion object {
        fun fromJson(f: Fields): SubtitleStyle = SubtitleStyle(
            fontScale = (f.optDouble("fontScale", 0.5, 2.5) ?: 1.0).toFloat(),
            backgroundOpacity = (f.optDouble("backgroundOpacity", 0.0, 1.0) ?: 0.6).toFloat(),
            bottomPaddingFraction = (f.optDouble("bottomPaddingFraction", 0.0, 0.35) ?: 0.08).toFloat(),
        )
    }
}

/** Public, header-free view of an item in the playback queue. */
data class QueueItemSummary(
    val id: String,
    val uri: String,
    val title: String? = null,
    val mimeType: String? = null,
) {
    fun toJson(): JsonObject = linkedMapOf(
        "id" to id,
        "uri" to uri,
        "title" to title,
        "mimeType" to mimeType,
    )

    companion object {
        fun fromJson(f: Fields): QueueItemSummary = QueueItemSummary(
            id = f.string("id", 64),
            uri = f.string("uri", 2048),
            title = f.optString("title", 256, 0),
            mimeType = f.optString("mimeType", 128, 0),
        )
    }
}

/** Keys supported by `remote.key` for Google TV-style phone remote control. */
enum class RemoteKey {
    UP, DOWN, LEFT, RIGHT, CENTER, BACK, HOME,
    PLAY_PAUSE, REWIND, FAST_FORWARD,
    MUTE, VOLUME_UP, VOLUME_DOWN;

    companion object {
        fun fromWire(s: String): RemoteKey? = values().firstOrNull { it.name.equals(s.trim(), ignoreCase = true) }
    }
}

/**
 * Immutable view of the player. [revision] increases with every published change, [capturedAtMs] lets a
 * sender extrapolate [positionMs] while [isPlaying]. [durationMs] is null while unknown (e.g. live streams).
 */
data class PlaybackSnapshot(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val mediaUri: String? = null,
    val title: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val bufferedPositionMs: Long = 0,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val revision: Long = 0,
    val error: PlaybackErrorInfo? = null,
    val capturedAtMs: Long = 0,
    val audioTracks: List<MediaTrack> = emptyList(),
    val subtitleTracks: List<MediaTrack> = emptyList(),
    val subtitlesEnabled: Boolean = true,
    val subtitleOffsetMs: Long = 0,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val queue: List<QueueItemSummary> = emptyList(),
    val queueIndex: Int = -1,
) {
    fun toJson(): JsonObject = linkedMapOf(
        "status" to status.name,
        "mediaUri" to mediaUri,
        "title" to title,
        "positionMs" to positionMs,
        "durationMs" to durationMs,
        "bufferedPositionMs" to bufferedPositionMs,
        "isPlaying" to isPlaying,
        "speed" to speed.toDouble(),
        "volume" to volume.toDouble(),
        "revision" to revision,
        "capturedAtMs" to capturedAtMs,
        "error" to error?.let { mapOf("code" to it.code, "message" to it.message, "recoverable" to it.recoverable) },
        "audioTracks" to audioTracks.map { it.toJson() },
        "subtitleTracks" to subtitleTracks.map { it.toJson() },
        "subtitlesEnabled" to subtitlesEnabled,
        "subtitleOffsetMs" to subtitleOffsetMs,
        "subtitleStyle" to subtitleStyle.toJson(),
        "queue" to queue.map { it.toJson() },
        "queueIndex" to queueIndex.toLong(),
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun parseObjList(raw: Any?, maxItems: Int): List<Fields> {
            if (raw == null) return emptyList()
            val list = raw as? List<*> ?: throw UcpException(ErrorCode.INVALID_PAYLOAD, "Expected array")
            if (list.size > maxItems) throw UcpException(ErrorCode.INVALID_PAYLOAD, "Too many items")
            return list.map { item ->
                val m = item as? Map<String, Any?> ?: throw UcpException(ErrorCode.INVALID_PAYLOAD, "Expected object item")
                Fields(m)
            }
        }

        fun fromJson(f: Fields): PlaybackSnapshot {
            val statusName = f.string("status", 32)
            val status = PlaybackStatus.values().firstOrNull { it.name == statusName }
                ?: throw UcpException(ErrorCode.INVALID_PAYLOAD, "Unknown status")
            val err = f.optObj("error")?.let {
                PlaybackErrorInfo(it.string("code", 64), it.string("message", 512, 0), it.bool("recoverable"))
            }
            val audioTracks = parseObjList(f.raw["audioTracks"], 32).map { MediaTrack.fromJson(it) }
            val subtitleTracks = parseObjList(f.raw["subtitleTracks"], 32).map { MediaTrack.fromJson(it) }
            val queue = parseObjList(f.raw["queue"], 100).map { QueueItemSummary.fromJson(it) }
            return PlaybackSnapshot(
                status = status,
                mediaUri = f.optString("mediaUri", 2048),
                title = f.optString("title", 256, 0),
                positionMs = f.long("positionMs", 0, Long.MAX_VALUE),
                durationMs = f.optLong("durationMs", 0, Long.MAX_VALUE),
                bufferedPositionMs = f.long("bufferedPositionMs", 0, Long.MAX_VALUE),
                isPlaying = f.bool("isPlaying"),
                speed = f.double("speed", 0.0, 100.0).toFloat(),
                volume = f.double("volume", 0.0, 1.0).toFloat(),
                revision = f.long("revision", 0, Long.MAX_VALUE),
                error = err,
                capturedAtMs = f.long("capturedAtMs", 0, Long.MAX_VALUE),
                audioTracks = audioTracks,
                subtitleTracks = subtitleTracks,
                subtitlesEnabled = f.optBool("subtitlesEnabled") ?: true,
                subtitleOffsetMs = f.optLong("subtitleOffsetMs", -60_000, 60_000) ?: 0L,
                subtitleStyle = f.optObj("subtitleStyle")?.let { SubtitleStyle.fromJson(it) } ?: SubtitleStyle(),
                queue = queue,
                queueIndex = (f.optLong("queueIndex", -1, 1000) ?: -1L).toInt(),
            )
        }
    }
}

fun interface PlaybackListener {
    fun onSnapshot(snapshot: PlaybackSnapshot)
}

/**
 * The single playback endpoint. Implementations must be thread-safe: remote commands arrive on network
 * threads, local UI commands on the main thread. [snapshot] may be read from any thread.
 * This interface deliberately knows nothing about Media3 or about networking.
 */
interface PlaybackEngine {
    val snapshot: PlaybackSnapshot
    fun load(item: MediaItemInfo, autoplay: Boolean, startPositionMs: Long)
    fun play()
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun setVolume(volume: Float)
    fun selectTracks(audioTrackId: String?, subtitleTrackId: String?) {}
    fun setSubtitleOffset(offsetMs: Long) {}
    fun setSubtitleStyle(style: SubtitleStyle) {}
    fun enqueue(item: MediaItemInfo, playNext: Boolean = false, startPositionMs: Long = 0) {
        load(item, autoplay = true, startPositionMs = startPositionMs)
    }
    fun removeQueueItem(id: String) {}
    fun moveQueueItem(id: String, toIndex: Int) {}
    fun clearQueue() {}
    fun skipNext(): Boolean = false
    fun skipPrevious(): Boolean = false
    fun jumpToQueueItem(id: String): Boolean = false
    fun onRemoteKey(key: RemoteKey) {}
    fun addListener(listener: PlaybackListener)
    fun removeListener(listener: PlaybackListener)
    fun release()
}

/** Full entry in a [PlaybackQueue], retaining request headers in memory while never exposing them in summaries. */
data class QueueEntry(
    val id: String,
    val item: MediaItemInfo,
    val startPositionMs: Long = 0,
) {
    fun toSummary() = QueueItemSummary(id = id, uri = item.uri, title = item.title, mimeType = item.mimeType)
}

/**
 * Thread-safe queue state machine shared by [PlaybackEngine] implementations.
 * Caps queue length at [MAX_ITEMS] so snapshots always remain well below the 64 KiB UCP message limit.
 */
class PlaybackQueue {
    companion object {
        const val MAX_ITEMS = 50
    }

    private val items = ArrayList<QueueEntry>()
    private var currentIndex = -1
    private var nextSeq = 1L

    @Synchronized
    fun onDirectLoad(item: MediaItemInfo, startPositionMs: Long = 0) {
        val existingIdx = items.indexOfFirst { it.item.uri == item.uri }
        if (existingIdx >= 0 && items.size > 1) {
            items[existingIdx] = items[existingIdx].copy(item = item, startPositionMs = startPositionMs)
            currentIndex = existingIdx
        } else {
            items.clear()
            items.add(QueueEntry("q${nextSeq++}", item, startPositionMs))
            currentIndex = 0
        }
    }

    /** Adds [item] to the queue. Returns the entry if it should be loaded and played immediately (when queue was empty/idle). */
    @Synchronized
    fun add(item: MediaItemInfo, playNext: Boolean, startPositionMs: Long, isCurrentlyIdle: Boolean): QueueEntry? {
        if (items.size >= MAX_ITEMS) throw IllegalArgumentException("Queue is full (max $MAX_ITEMS items)")
        val entry = QueueEntry("q${nextSeq++}", item, startPositionMs)
        if (isCurrentlyIdle || items.isEmpty() || currentIndex !in items.indices) {
            items.add(entry)
            currentIndex = items.lastIndex
            return entry
        }
        val insertAt = if (playNext) (currentIndex + 1).coerceAtMost(items.size) else items.size
        items.add(insertAt, entry)
        return null
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return false
        items.removeAt(idx)
        when {
            items.isEmpty() -> currentIndex = -1
            idx < currentIndex -> currentIndex--
            idx == currentIndex -> currentIndex = currentIndex.coerceAtMost(items.lastIndex)
        }
        return true
    }

    @Synchronized
    fun move(id: String, toIndex: Int): Boolean {
        val from = items.indexOfFirst { it.id == id }
        if (from < 0 || items.isEmpty()) return false
        val target = toIndex.coerceIn(0, items.lastIndex)
        if (from == target) return true
        val currentId = items.getOrNull(currentIndex)?.id
        val moved = items.removeAt(from)
        items.add(target, moved)
        if (currentId != null) {
            currentIndex = items.indexOfFirst { it.id == currentId }
        }
        return true
    }

    @Synchronized
    fun clear() {
        items.clear()
        currentIndex = -1
    }

    @Synchronized
    fun next(): QueueEntry? {
        if (currentIndex + 1 in items.indices) {
            currentIndex++
            return items[currentIndex]
        }
        return null
    }

    @Synchronized
    fun previous(): QueueEntry? {
        if (currentIndex - 1 in items.indices) {
            currentIndex--
            return items[currentIndex]
        }
        return null
    }

    @Synchronized
    fun jumpTo(id: String): QueueEntry? {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return null
        currentIndex = idx
        return items[idx]
    }

    @Synchronized
    fun summaries(): Pair<List<QueueItemSummary>, Int> =
        items.map { it.toSummary() } to currentIndex
}

/** Primitive player states, mirroring the four Media3 Player.STATE_* values without depending on Media3. */
enum class EngineState { IDLE, BUFFERING, READY, ENDED }

/** Pure mapping from low-level player facts to the protocol-level [PlaybackStatus]. */
object PlaybackStatusDeriver {
    fun derive(
        engineState: EngineState,
        playWhenReady: Boolean,
        hasMedia: Boolean,
        hasError: Boolean,
        hasBeenReady: Boolean,
        hasStarted: Boolean,
    ): PlaybackStatus {
        if (hasError) return PlaybackStatus.ERROR
        if (!hasMedia) return PlaybackStatus.IDLE
        return when (engineState) {
            EngineState.IDLE -> PlaybackStatus.LOADING
            EngineState.BUFFERING -> if (hasBeenReady) PlaybackStatus.BUFFERING else PlaybackStatus.LOADING
            EngineState.READY -> when {
                playWhenReady -> PlaybackStatus.PLAYING
                hasStarted -> PlaybackStatus.PAUSED
                else -> PlaybackStatus.READY
            }
            EngineState.ENDED -> PlaybackStatus.ENDED
        }
    }
}

/** Validates media URIs accepted over the network. Only http/https are ever handed to the player by remote senders. */
object MediaUriValidator {
    private val MIME = Regex("^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}$")

    fun validateUri(uri: String): String {
        if (uri.length > 2048) throw IllegalArgumentException("URI too long")
        if (uri.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) throw IllegalArgumentException("URI contains illegal characters")
        val parsed = try {
            java.net.URI(uri)
        } catch (e: java.net.URISyntaxException) {
            throw IllegalArgumentException("Malformed URI")
        }
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw IllegalArgumentException("Only http and https media URIs are supported")
        if (parsed.host.isNullOrEmpty()) throw IllegalArgumentException("URI has no host")
        return uri
    }

    fun validateMime(mime: String): String {
        if (!MIME.matches(mime)) throw IllegalArgumentException("Invalid MIME type")
        return mime
    }
}

/**
 * The only request headers a sender may ask the receiver to send when fetching media. Many sites only serve their
 * streams to requests that look like they come from their own player page, so the browser-side sender
 * forwards the page's Referer/Origin/User-Agent/Accept-Language and, where needed, its cookies for the media host.
 *
 * Rules (UCP 1.0 `media.load.headers`): names from the allowlist only, matched case-insensitively and normalised;
 * no name twice; at most [MAX_ENTRIES] entries; each value 1..[MAX_VALUE_LENGTH] visible ASCII characters
 * (0x20-0x7E, so no CR/LF/control characters and no header injection); names + values at most [MAX_TOTAL_BYTES]
 * bytes in total; Referer and Origin must be http(s) URLs.
 */
object MediaHeaders {
    const val REFERER = "Referer"
    const val ORIGIN = "Origin"
    const val USER_AGENT = "User-Agent"
    const val COOKIE = "Cookie"
    const val ACCEPT_LANGUAGE = "Accept-Language"
    const val MAX_ENTRIES = 5
    const val MAX_VALUE_LENGTH = 4096
    const val MAX_TOTAL_BYTES = 8192
    val ALLOWED: List<String> = listOf(REFERER, ORIGIN, USER_AGENT, COOKIE, ACCEPT_LANGUAGE)
    private val BY_LOWER = ALLOWED.associateBy { it.lowercase() }

    /** The canonical spelling of [name] if it is allowlisted, else null. */
    fun canonical(name: String): String? = BY_LOWER[name.lowercase()]

    fun isVisibleAscii(v: String) = v.all { it.code in 0x20..0x7E }

    /** Returns the normalised map or throws [IllegalArgumentException] with a reason (never containing a value). */
    fun validate(raw: Map<String, Any?>): Map<String, String> {
        if (raw.size > MAX_ENTRIES) throw IllegalArgumentException("At most $MAX_ENTRIES headers are allowed")
        val out = LinkedHashMap<String, String>()
        var total = 0
        for ((k, v) in raw) {
            val name = canonical(k) ?: throw IllegalArgumentException("Header '${k.take(40).filter { it.code in 0x21..0x7E }}' is not allowed")
            if (name in out) throw IllegalArgumentException("Header '$name' is repeated")
            if (v !is String) throw IllegalArgumentException("Header '$name' must be a string")
            if (v.isEmpty() || v.length > MAX_VALUE_LENGTH) throw IllegalArgumentException("Header '$name' has invalid length")
            if (!isVisibleAscii(v)) throw IllegalArgumentException("Header '$name' must be visible ASCII")
            if (name == REFERER || name == ORIGIN) {
                val scheme = runCatching { java.net.URI(v).scheme?.lowercase() }.getOrNull()
                if (scheme != "http" && scheme != "https") throw IllegalArgumentException("Header '$name' must be an http(s) URL")
            }
            total += name.length + v.length
            if (total > MAX_TOTAL_BYTES) throw IllegalArgumentException("Headers exceed $MAX_TOTAL_BYTES bytes")
            out[name] = v
        }
        return out
    }
}

/** Validation of `media.load.subtitles`. */
object SubtitleValidator {
    const val MAX_TRACKS = 8
    const val MAX_LABEL = 64
    val MIME_TYPES: Set<String> = setOf("text/vtt", "application/x-subrip", "text/x-ssa", "application/ttml+xml")
    private val KEYS = setOf("uri", "mimeType", "language", "label")
    private val LANGUAGE = Regex("^[A-Za-z0-9-]{1,16}$")

    /** Throws [IllegalArgumentException] with a reason. */
    fun validate(raw: Any?): List<SubtitleInfo> {
        if (raw !is List<*>) throw IllegalArgumentException("subtitles must be an array")
        if (raw.size > MAX_TRACKS) throw IllegalArgumentException("At most $MAX_TRACKS subtitle tracks are allowed")
        return raw.mapIndexed { i, item ->
            @Suppress("UNCHECKED_CAST")
            val m = item as? Map<String, Any?> ?: throw IllegalArgumentException("subtitles[$i] must be an object")
            if (m.keys.any { it !in KEYS }) throw IllegalArgumentException("subtitles[$i] has an unknown field")
            val uri = (m["uri"] as? String) ?: throw IllegalArgumentException("subtitles[$i].uri is required")
            try {
                MediaUriValidator.validateUri(uri)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("subtitles[$i].uri: ${e.message}")
            }
            val mime = (m["mimeType"] as? String)?.takeIf { it in MIME_TYPES }
                ?: throw IllegalArgumentException("subtitles[$i].mimeType is not supported")
            val language = m["language"]?.let {
                (it as? String)?.takeIf { s -> LANGUAGE.matches(s) } ?: throw IllegalArgumentException("subtitles[$i].language is invalid")
            }
            val label = m["label"]?.let {
                (it as? String)?.takeIf { s -> s.isNotEmpty() && s.length <= MAX_LABEL && s.none { c -> c.code < 0x20 || c.code == 0x7F } }
                    ?: throw IllegalArgumentException("subtitles[$i].label is invalid")
            }
            SubtitleInfo(uri, mime, language, label)
        }
    }
}
