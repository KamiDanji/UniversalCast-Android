package com.universalcast.sender.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.universalcast.playback.PlaybackStatus
import com.universalcast.playback.RemoteKey
import com.universalcast.sender.ConnectionPhase
import com.universalcast.sender.SenderState
import com.universalcast.sender.app.ui.MainActivity
import kotlin.math.roundToInt

/**
 * Keeps the phone's connection to the TV alive while the app is in the background (switching to another app, screen
 * off), and exposes an Android [MediaSession] so the user gets lock-screen transport controls, a live seek bar in
 * the notification shade, and hardware volume button control while casting.
 */
class ConnectionService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var mediaSession: MediaSession? = null
    private var volumeProvider: VolumeProvider? = null
    private val listener: (SenderState) -> Unit = { s -> onState(s) }

    private val runtime get() = (application as SenderApp).runtime

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "universalcast:connection").apply { setReferenceCounted(false); acquire() }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifi = wm.createWifiLock(mode, "universalcast:connection").apply { setReferenceCounted(false); acquire() }
        setupMediaSession()
    }

    private fun setupMediaSession() {
        val session = MediaSession(this, "UniversalCast")
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        session.setSessionActivity(open)
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                val snap = runtime.state.value.snapshot
                if (snap.status == PlaybackStatus.PAUSED) runtime.controller.resume() else runtime.controller.play()
            }

            override fun onPause() {
                runtime.controller.pause()
            }

            override fun onStop() {
                runtime.controller.stop()
            }

            override fun onSeekTo(pos: Long) {
                runtime.controller.seek(pos.coerceAtLeast(0L))
            }

            override fun onFastForward() {
                val est = runtime.state.value.estimatedPositionMs(System.currentTimeMillis())
                runtime.controller.seek(est + 10_000L)
            }

            override fun onRewind() {
                val est = runtime.state.value.estimatedPositionMs(System.currentTimeMillis())
                runtime.controller.seek((est - 10_000L).coerceAtLeast(0L))
            }

            override fun onSkipToNext() {
                val snap = runtime.state.value.snapshot
                if (snap.queueIndex + 1 in snap.queue.indices) {
                    runtime.controller.queueNext()
                } else {
                    onFastForward()
                }
            }

            override fun onSkipToPrevious() {
                val snap = runtime.state.value.snapshot
                if (snap.queueIndex - 1 in snap.queue.indices) {
                    runtime.controller.queuePrevious()
                } else {
                    onRewind()
                }
            }
        })
        val vp = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, MAX_VOLUME_STEPS, MAX_VOLUME_STEPS) {
            override fun onSetVolumeTo(volume: Int) {
                val clamped = volume.coerceIn(0, MAX_VOLUME_STEPS)
                currentVolume = clamped
                runtime.controller.setVolume(clamped.toDouble() / MAX_VOLUME_STEPS.toDouble())
            }

            override fun onAdjustVolume(direction: Int) {
                if (direction > 0) {
                    val next = (currentVolume + 1).coerceIn(0, MAX_VOLUME_STEPS)
                    currentVolume = next
                    runtime.controller.setVolume(next.toDouble() / MAX_VOLUME_STEPS.toDouble())
                    runtime.controller.remoteKey(RemoteKey.VOLUME_UP)
                } else if (direction < 0) {
                    val next = (currentVolume - 1).coerceIn(0, MAX_VOLUME_STEPS)
                    currentVolume = next
                    runtime.controller.setVolume(next.toDouble() / MAX_VOLUME_STEPS.toDouble())
                    runtime.controller.remoteKey(RemoteKey.VOLUME_DOWN)
                }
            }
        }
        session.setPlaybackToRemote(vp)
        session.isActive = true
        mediaSession = session
        volumeProvider = vp
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                runtime.controller.disconnect()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PLAY_PAUSE -> {
                val snap = runtime.state.value.snapshot
                if (snap.isPlaying) runtime.controller.pause()
                else if (snap.status == PlaybackStatus.PAUSED) runtime.controller.resume()
                else runtime.controller.play()
                return START_NOT_STICKY
            }
            ACTION_REWIND -> {
                val est = runtime.state.value.estimatedPositionMs(System.currentTimeMillis())
                runtime.controller.seek((est - 10_000L).coerceAtLeast(0L))
                return START_NOT_STICKY
            }
            ACTION_FAST_FORWARD -> {
                val est = runtime.state.value.estimatedPositionMs(System.currentTimeMillis())
                runtime.controller.seek(est + 10_000L)
                return START_NOT_STICKY
            }
            ACTION_STOP_PLAYBACK -> {
                runtime.controller.stop()
                return START_NOT_STICKY
            }
        }
        val state = runtime.state.value
        updateMediaSession(state)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(state), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        runtime.controller.removeListener(listener)
        runtime.controller.addListener(listener)
        if (!state.isActive()) stopSelf()
        return START_NOT_STICKY
    }

    private fun onState(s: SenderState) {
        if (!s.isActive()) { stopSelf(); return }
        updateMediaSession(s)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
    }

    private fun updateMediaSession(s: SenderState) {
        val session = mediaSession ?: return
        val snap = s.snapshot
        val tv = s.receiver?.name ?: "TV"
        val activeMedia = s.phase == ConnectionPhase.CONNECTED && snap.status != PlaybackStatus.IDLE

        volumeProvider?.currentVolume = (snap.volume * MAX_VOLUME_STEPS).roundToInt().coerceIn(0, MAX_VOLUME_STEPS)

        val pbState = when {
            !activeMedia -> PlaybackState.STATE_NONE
            snap.status == PlaybackStatus.PLAYING -> PlaybackState.STATE_PLAYING
            snap.status == PlaybackStatus.PAUSED || snap.status == PlaybackStatus.READY -> PlaybackState.STATE_PAUSED
            snap.status == PlaybackStatus.LOADING || snap.status == PlaybackStatus.BUFFERING -> PlaybackState.STATE_BUFFERING
            snap.status == PlaybackStatus.ERROR -> PlaybackState.STATE_ERROR
            else -> PlaybackState.STATE_STOPPED
        }
        val actions = if (s.canControl && activeMedia) {
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_REWIND or
                PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS
        } else 0L

        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(pbState, s.estimatedPositionMs(System.currentTimeMillis()), if (snap.isPlaying) snap.speed else 0f)
                .setActions(actions)
                .build()
        )
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, if (activeMedia) (snap.title ?: snap.mediaUri ?: "Casting media") else "Connected to $tv")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Casting to $tv")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, snap.durationMs ?: -1L)
            .build()
        session.setMetadata(meta)
    }

    private fun SenderState.isActive() = phase == ConnectionPhase.CONNECTED || phase == ConnectionPhase.CONNECTING || phase == ConnectionPhase.RECONNECTING

    override fun onDestroy() {
        runCatching { runtime.controller.removeListener(listener) }
        runCatching {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        }
        runCatching { proxyStopIfDisconnected() }
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        runCatching { wifi?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    private fun proxyStopIfDisconnected() {
        if (!runtime.state.value.isActive()) {
            runtime.proxy.stop()
        }
    }

    private fun actionPendingIntent(reqCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this, reqCode,
            Intent(this, ConnectionService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun notification(s: SenderState): Notification {
        val tv = s.receiver?.name ?: "your TV"
        val snap = s.snapshot
        val activeMedia = s.phase == ConnectionPhase.CONNECTED && snap.status != PlaybackStatus.IDLE
        val title = if (activeMedia) (snap.title ?: snap.mediaUri ?: "UniversalCast") else "UniversalCast"
        val text = when {
            activeMedia -> "Casting to $tv · ${snap.status.name.lowercase().replaceFirstChar { it.uppercase() }}"
            s.phase == ConnectionPhase.CONNECTED -> "Connected to $tv"
            s.phase == ConnectionPhase.RECONNECTING -> "Reconnecting to $tv…"
            else -> "Connecting to $tv…"
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = actionPendingIntent(1, ACTION_DISCONNECT)

        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(if (activeMedia) Notification.CATEGORY_TRANSPORT else Notification.CATEGORY_SERVICE)

        if (activeMedia && s.canControl) {
            val rew = actionPendingIntent(2, ACTION_REWIND)
            val playPause = actionPendingIntent(3, ACTION_PLAY_PAUSE)
            val ff = actionPendingIntent(4, ACTION_FAST_FORWARD)
            val stop = actionPendingIntent(5, ACTION_STOP_PLAYBACK)
            val playing = snap.isPlaying || snap.status == PlaybackStatus.LOADING || snap.status == PlaybackStatus.BUFFERING
            builder.addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_rew, "−10s", rew
                ).build()
            )
            builder.addAction(
                Notification.Action.Builder(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pause" else "Play",
                    playPause,
                ).build()
            )
            builder.addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_ff, "+10s", ff
                ).build()
            )
            builder.addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop
                ).build()
            )
            mediaSession?.let { ms ->
                builder.setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(ms.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
            }
        } else {
            builder.addAction(Notification.Action.Builder(0, "Disconnect", disconnect).build())
        }
        return builder.build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "TV connection & media controls", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Lock-screen playback controls and persistent connection while casting to a TV."
                setShowBadge(false)
            })
        }
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1
        private const val MAX_VOLUME_STEPS = 20
        const val ACTION_DISCONNECT = "com.universalcast.sender.DISCONNECT"
        const val ACTION_PLAY_PAUSE = "com.universalcast.sender.PLAY_PAUSE"
        const val ACTION_REWIND = "com.universalcast.sender.REWIND"
        const val ACTION_FAST_FORWARD = "com.universalcast.sender.FAST_FORWARD"
        const val ACTION_STOP_PLAYBACK = "com.universalcast.sender.STOP_PLAYBACK"

        fun start(context: Context) {
            // Starting a foreground service from the background can be refused (Android 12+); the connection still works then.
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java)) }
        }
    }
}
