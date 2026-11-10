package com.universalcast.receiver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.universalcast.receiver.R
import com.universalcast.receiver.UniversalCastApp
import com.universalcast.receiver.ui.MainActivity

/**
 * Hosts the secure receiver for as long as the app is alive: foreground notification, multicast lock (mDNS),
 * a partial wake lock while a session is active, and re-advertisement when the network changes.
 */
class ReceiverService : Service() {
    private val runtime get() = (application as UniversalCastApp).runtime
    private var multicast: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        acquireLocks()
        runtime.startReceiver()
        registerNetworkCallback()
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun acquireLocks() {
        if (multicast == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicast = wifi.createMulticastLock("ucp-mdns").apply { setReferenceCounted(false); acquire() }
        }
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "universalcast:receiver").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun registerNetworkCallback() {
        if (callback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // The address list in the QR code is computed on demand; mDNS may need re-announcing.
                runtime.core.advertise()
            }
        }
        callback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    override fun onDestroy() {
        callback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        callback = null
        runtime.stopReceiver()
        multicast?.takeIf { it.isHeld }?.release(); multicast = null
        wake?.takeIf { it.isHeld }?.release(); wake = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.universalcast.receiver.STOP"
        private const val CHANNEL = "receiver"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            val i = Intent(context, ReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }
}
