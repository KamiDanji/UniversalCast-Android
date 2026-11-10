package com.universalcast.playback

import com.universalcast.ucp.ErrorCode
import com.universalcast.ucp.Fields
import com.universalcast.ucp.JsonObject
import com.universalcast.ucp.UcpException

enum class PlaybackStatus { IDLE, LOADING, BUFFERING, READY, PLAYING, PAUSED, ENDED, ERROR }

data class PlaybackErrorInfo(val code: String, val message: String, val recoverable: Boolean)

/**
 * What to play. [headers] are the extra HTTP request headers the stream needs (see [MediaHeaders]); they can carry
 * cookies, so they are never logged, never put in snapshots and never shown to other senders.
 */
data class MediaItemInfo(
    val uri: String,
    val mimeType: String? = null,
    val title: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    override fun toString() = "MediaItemInfo(uri=$uri, mimeType=$mimeType, title=$title, headers=${headers.keys})"
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
    )

    companion object {
        fun fromJson(f: Fields): PlaybackSnapshot {
            val statusName = f.string("status", 32)
            val status = PlaybackStatus.values().firstOrNull { it.name == statusName }
                ?: throw UcpException(ErrorCode.INVALID_PAYLOAD, "Unknown status")
            val err = f.optObj("error")?.let {
                PlaybackErrorInfo(it.string("code", 64), it.string("message", 512, 0), it.bool("recoverable"))
            }
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
    fun addListener(listener: PlaybackListener)
    fun removeListener(listener: PlaybackListener)
    fun release()
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
 * forwards the page's Referer/Origin/User-Agent and, where needed, its cookies for the media host.
 *
 * Header names are matched case-insensitively and normalised. Values must be single-line printable text (no header
 * injection); Referer and Origin must be http(s) URLs.
 */
object MediaHeaders {
    const val REFERER = "Referer"
    const val ORIGIN = "Origin"
    const val USER_AGENT = "User-Agent"
    const val COOKIE = "Cookie"
    private val ALLOWED = listOf(REFERER, ORIGIN, USER_AGENT, COOKIE).associateBy { it.lowercase() }
    private val MAX_LEN = mapOf(REFERER to 2048, ORIGIN to 512, USER_AGENT to 512, COOKIE to 8192)

    /** Returns the normalised map or throws [IllegalArgumentException] with a reason. */
    fun validate(raw: Map<String, Any?>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in raw) {
            val name = ALLOWED[k.lowercase()] ?: throw IllegalArgumentException("Header '$k' is not allowed")
            if (name in out) throw IllegalArgumentException("Header '$name' is repeated")
            if (v !is String) throw IllegalArgumentException("Header '$name' must be a string")
            if (v.isEmpty() || v.length > MAX_LEN.getValue(name)) throw IllegalArgumentException("Header '$name' has invalid length")
            if (v.any { it.code < 0x20 || it.code == 0x7F }) throw IllegalArgumentException("Header '$name' contains control characters")
            if (name == REFERER || name == ORIGIN) {
                val scheme = runCatching { java.net.URI(v).scheme?.lowercase() }.getOrNull()
                if (scheme != "http" && scheme != "https") throw IllegalArgumentException("Header '$name' must be an http(s) URL")
            }
            out[name] = v
        }
        return out
    }
}
