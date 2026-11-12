package com.universalcast.receiver.platform

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.universalcast.sender.MediaSniffer
import okhttp3.OkHttpClient
import com.universalcast.playback.EngineState
import com.universalcast.playback.MediaHeaders
import com.universalcast.playback.MediaItemInfo
import com.universalcast.playback.PlaybackEngine
import com.universalcast.playback.PlaybackErrorInfo
import com.universalcast.playback.PlaybackListener
import com.universalcast.playback.PlaybackSnapshot
import com.universalcast.playback.PlaybackStatusDeriver
import com.universalcast.ucp.ErrorCode
import com.universalcast.ucp.UcpException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * [PlaybackEngine] over Media3 ExoPlayer. ExoPlayer is only touched on the main thread; network threads call in
 * through [onMain], which runs the command synchronously on the main looper so the snapshot returned to the
 * sender reflects the command. Listener notifications are re-dispatched to a separate executor so a network
 * thread blocked in [onMain] can never deadlock with a listener on the main thread.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3PlaybackEngine(context: Context) : PlaybackEngine {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val notifier = Executors.newSingleThreadExecutor { r -> Thread(r, "ucp-playback-events").also { it.isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<PlaybackListener>()

    private var exo: ExoPlayer? = null
    private var revision = 0L
    private var mediaUri: String? = null
    private var title: String? = null
    private var error: PlaybackErrorInfo? = null
    private var hasBeenReady = false
    private var hasStarted = false
    private var lastPublished: PlaybackSnapshot? = null
    private var released = false

    init {
        // Media3's own logcat output can include request details of failed loads (URLs with signed query strings).
        // Headers/cookies/tokens must never reach logcat, so the library logger is off; errors still reach the phone
        // through the snapshot and the TV's diagnostics log as short, sanitised messages.
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
    }

    /** For rendering only (PlayerView). Must be used on the main thread. */
    val player: ExoPlayer get() = ensurePlayer()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) hasBeenReady = true
            publish()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) hasStarted = true
            publish()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publish()
        override fun onPlayerError(e: PlaybackException) {
            error = PlaybackErrorInfo(e.errorCodeName, describe(e).take(200), recoverable = true)
            publish()
        }

        override fun onPositionDiscontinuity(o: Player.PositionInfo, n: Player.PositionInfo, reason: Int) = publish()
        override fun onPlaybackParametersChanged(p: PlaybackParameters) = publish()
        override fun onVolumeChanged(volume: Float) = publish()
    }

    /** A message the phone can show; "Source error" alone says nothing about what to try next. */
    private fun describe(e: PlaybackException): String {
        var cause: Throwable? = e
        while (cause != null && cause !is HttpDataSource.InvalidResponseCodeException) cause = cause.cause
        val code = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return when (code) {
            401, 403 -> "The video server refused the TV (HTTP $code). This source only plays inside its own page; try another server or quality on the site."
            404, 410 -> "The video is gone from the server (HTTP $code). The link may have expired; reload the page and cast again."
            null -> when (e.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    "The TV could not reach the video server. Check the TV's internet connection."
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                    "The TV received something that isn't a playable video. Try another stream from the list."
                else -> e.message ?: "Playback error"
            }
            else -> "The video server answered with an error (HTTP $code)."
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        check(Looper.myLooper() == Looper.getMainLooper())
        exo?.let { return it }
        check(!released) { "Engine released" }
        return ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(app, ResolvingDataSource.Factory(http, resolver))))
            .build().also {
                // Subtitles: prefer English, but also pick tracks without a language tag (common for side-loaded files).
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                    .setPreferredTextLanguage("en")
                    .setSelectUndeterminedTextLanguage(true)
                    .build()
                it.addListener(playerListener)
                exo = it
            }
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask { block() }
        main.post(task)
        try {
            return task.get(5, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            task.cancel(false)
            throw UcpException(ErrorCode.INTERNAL, "Player did not respond")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    private fun build(bumpRevision: Boolean): PlaybackSnapshot {
        val p = exo
        val now = System.currentTimeMillis()
        if (p == null || mediaUri == null) {
            return PlaybackSnapshot(revision = revision, capturedAtMs = now, error = error)
        }
        val state = when (p.playbackState) {
            Player.STATE_BUFFERING -> EngineState.BUFFERING
            Player.STATE_READY -> EngineState.READY
            Player.STATE_ENDED -> EngineState.ENDED
            else -> EngineState.IDLE
        }
        val status = PlaybackStatusDeriver.derive(state, p.playWhenReady, true, error != null, hasBeenReady, hasStarted)
        val duration = p.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET && it >= 0 }
        if (bumpRevision) revision++
        return PlaybackSnapshot(
            status = status,
            mediaUri = mediaUri,
            title = title,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = if (p.isCurrentMediaItemLive) null else duration,
            bufferedPositionMs = p.bufferedPosition.coerceAtLeast(0),
            isPlaying = p.isPlaying,
            speed = p.playbackParameters.speed,
            volume = p.volume,
            revision = revision,
            error = error,
            capturedAtMs = now,
        )
    }

    /** Publishes only when something other than the position changed, so senders are not flooded. */
    private fun publish() {
        val s = build(false)
        val prev = lastPublished
        if (prev != null && prev.copy(positionMs = 0, bufferedPositionMs = 0, capturedAtMs = 0, revision = 0) ==
            s.copy(positionMs = 0, bufferedPositionMs = 0, capturedAtMs = 0, revision = 0) &&
            kotlin.math.abs(prev.positionMs - s.positionMs) < 5_000
        ) return
        val out = build(true)
        lastPublished = out
        notifier.execute { listeners.forEach { l -> runCatching { l.onSnapshot(out) } } }
    }

    override val snapshot: PlaybackSnapshot get() = onMain { build(false) }

    override fun load(item: MediaItemInfo, autoplay: Boolean, startPositionMs: Long) = onMain {
        val p = ensurePlayer()
        error = null; hasBeenReady = false; hasStarted = false
        mediaUri = item.uri; title = item.title
        p.stop() // release the previous item's loaders before its request context is replaced
        requestContext = RequestContext.of(item)
        http.setUserAgent(item.headers[MediaHeaders.USER_AGENT] ?: DEFAULT_USER_AGENT)
        // Without an extension (".../playlist?id=1") Media3 can't tell HLS/DASH from a file: use the sender's MIME type,
        // else what the URL looks like.
        val mime = item.mimeType ?: MediaSniffer.classify(item.uri).mimeType
        val subtitles = item.subtitles.mapIndexed { i, s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.uri))
                .setMimeType(s.mimeType)
                .setLanguage(s.language)
                .setLabel(s.label)
                .setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        val mi = MediaItem.Builder().setUri(item.uri)
            .apply { mime?.let { setMimeType(it) } }
            .setSubtitleConfigurations(subtitles)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
            .build()
        p.setMediaItem(mi, startPositionMs.coerceAtLeast(0))
        p.playWhenReady = autoplay
        p.prepare()
        publish()
    }

    /**
     * Per-load request context: the headers a sender supplied for one stream (already allowlisted by the handler)
     * and the host of its media URI. Kept only in memory, never logged, replaced on every load and cleared on stop.
     */
    private class RequestContext(val headers: Map<String, String>, val mediaHost: String?) {
        companion object {
            fun of(item: MediaItemInfo) = RequestContext(
                item.headers - MediaHeaders.USER_AGENT,
                runCatching { Uri.parse(item.uri).host?.lowercase() }.getOrNull(),
            )
        }
    }

    @Volatile
    private var requestContext: RequestContext? = null

    /**
     * Adds the sender's headers to every request of the current item — master/media playlists, segments, keys and
     * subtitle files alike. Referer, Origin and Accept-Language go to every host (a CDN checks them like the page's
     * player would send them); Cookie only to the media URI's own host, so cookies never reach CDN/segment hosts.
     */
    private val resolver = ResolvingDataSource.Resolver { spec: DataSpec ->
        val ctx = requestContext
        if (ctx == null || ctx.headers.isEmpty()) return@Resolver spec
        val sameHost = ctx.mediaHost != null && spec.uri.host?.lowercase() == ctx.mediaHost
        val add = ctx.headers.filterKeys { it != MediaHeaders.COOKIE || sameHost }
        if (add.isEmpty()) spec else spec.withAdditionalHeaders(add)
    }

    /**
     * HTTP stack: OkHttp (already a dependency through media3-datasource-okhttp) rather than DefaultHttpDataSource,
     * because DefaultHttpDataSource re-sends a request's extra headers to every redirect hop, which would hand the
     * Cookie to a redirect target. The network interceptor sees each hop and strips Cookie from any request whose
     * host is not the media host. Redirects across http/https are followed (followSslRedirects).
     */
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            val host = requestContext?.mediaHost
            if (req.header(MediaHeaders.COOKIE) != null && (host == null || !req.url.host.equals(host, ignoreCase = true))) {
                chain.proceed(req.newBuilder().removeHeader(MediaHeaders.COOKIE).build())
            } else {
                chain.proceed(req)
            }
        }
        .build()

    private val http = OkHttpDataSource.Factory(okHttp).setUserAgent(DEFAULT_USER_AGENT)

    override fun play() = onMain {
        val p = ensurePlayer()
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        if (p.playbackState == Player.STATE_IDLE && mediaUri != null) { error = null; p.prepare() }
        p.play(); publish()
    }

    override fun pause() = onMain { ensurePlayer().pause(); publish() }
    override fun resume() = play()

    override fun stop() = onMain {
        val p = ensurePlayer()
        p.stop(); p.clearMediaItems()
        requestContext = null
        mediaUri = null; title = null; error = null; hasBeenReady = false; hasStarted = false
        publish()
    }

    override fun seekTo(positionMs: Long) = onMain { ensurePlayer().seekTo(positionMs.coerceAtLeast(0)); publish() }
    override fun setSpeed(speed: Float) = onMain { ensurePlayer().setPlaybackSpeed(speed); publish() }
    override fun setVolume(volume: Float) = onMain { ensurePlayer().volume = volume.coerceIn(0f, 1f); publish() }

    override fun addListener(listener: PlaybackListener) { listeners.add(listener) }
    override fun removeListener(listener: PlaybackListener) { listeners.remove(listener) }

    /** Periodic hook for the UI/service: keeps position-based snapshots flowing while playing. Main thread. */
    fun tick() { if (exo?.isPlaying == true) publish() }

    override fun release() {
        val r = Runnable {
            released = true
            exo?.removeListener(playerListener)
            exo?.release()
            exo = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) r.run() else main.post(r)
        notifier.shutdown()
    }

    private companion object {
        /** Many CDNs refuse requests from obvious non-browsers; look like a regular TV browser unless told otherwise. */
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}
