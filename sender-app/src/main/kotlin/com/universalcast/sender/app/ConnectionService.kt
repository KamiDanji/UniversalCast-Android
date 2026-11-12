package com.universalcast.sender.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.universalcast.sender.ConnectionPhase
import com.universalcast.sender.SenderState
import com.universalcast.sender.app.ui.MainActivity

/**
 * Keeps the phone's connection to the TV alive while the app is in the background (switching to another app, screen
 * off). A foreground service stops Android from freezing the process, and the wake/Wi-Fi locks stop the radio from
 * sleeping, so the heartbeat keeps running and the TV never sees the phone disappear (which would stop the stream).
 * Started by [SenderRuntime] when a connection begins, stopped when it ends or the user taps "Disconnect".
 */
class ConnectionService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            runtime.controller.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        val state = runtime.state.value
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(state), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        runtime.controller.removeListener(listener)
        runtime.controller.addListener(listener)
        if (!state.isActive()) stopSelf()
        return START_NOT_STICKY
    }

    private fun onState(s: SenderState) {
        if (!s.isActive()) { stopSelf(); return }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
    }

    private fun SenderState.isActive() = phase == ConnectionPhase.CONNECTED || phase == ConnectionPhase.CONNECTING || phase == ConnectionPhase.RECONNECTING

    override fun onDestroy() {
        runCatching { runtime.controller.removeListener(listener) }
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        runCatching { wifi?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    private fun notification(s: SenderState): Notification {
        val tv = s.receiver?.name ?: "your TV"
        val text = when (s.phase) {
            ConnectionPhase.CONNECTED -> "Connected to $tv"
            ConnectionPhase.RECONNECTING -> "Reconnecting to $tv…"
            else -> "Connecting to $tv…"
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("UniversalCast")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Disconnect", stop)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "TV connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while this phone is connected to a TV, so the stream keeps running in the background."
                setShowBadge(false)
            })
        }
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT = "com.universalcast.sender.DISCONNECT"

        fun start(context: Context) {
            // Starting a foreground service from the background can be refused (Android 12+); the connection still works then.
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java)) }
        }
    }
}
