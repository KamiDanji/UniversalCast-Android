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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.universalcast.receiver.R
import com.universalcast.receiver.UniversalCastApp
import com.universalcast.receiver.policy.AutoLaunchDecision
import com.universalcast.receiver.policy.AutoLaunchPolicy
import com.universalcast.receiver.ui.MainActivity

/**
 * Hosts the secure receiver for as long as the app is alive: foreground notification, multicast lock (mDNS),
 * a partial wake lock, re-advertisement when the network changes, and bringing the player to the front when a
 * phone casts while the app is in the background.
 *
 * Foreground-service type: `connectedDevice` (phones connect to the TV over the network). It is NOT `mediaPlayback`
 * because Android 15 forbids starting `mediaPlayback` (and dataSync, camera, phoneCall, mediaProjection, microphone)
 * services from BOOT_COMPLETED, and the service itself never plays media (the activity does). Its runtime
 * prerequisite is met by the CHANGE_WIFI_MULTICAST_STATE permission the app already declares for mDNS.
 */
class ReceiverService : Service() {
    private val runtime get() = (application as UniversalCastApp).runtime
    private val main = Handler(Looper.getMainLooper())
    private var multicast: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var anchor: OverlayAnchor? = null
    private var lastLaunchAtMs: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startInForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireLocks()
        runtime.startReceiver()
        registerNetworkCallback()
        (anchor ?: OverlayAnchor(this, runtime.log).also { anchor = it }).sync()
        runtime.onCastStarted = { main.post { onCastStarted() } }
        return START_STICKY
    }

    /** @return false if Android refused to make this a foreground service (logged, never a crash). */
    private fun startInForeground(): Boolean {
        return try {
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
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+), SecurityException for a missing type permission, …
            runtime.log.error("Receiver could not run as a foreground service", e)
            false
        }
    }

    private fun onCastStarted() {
        val a = anchor ?: OverlayAnchor(this, runtime.log).also { anchor = it }
        a.sync() // the permission may have been granted since the service started
        val now = SystemClock.elapsedRealtime()
        val decision = AutoLaunchPolicy.decide(
            activityResumed = MainActivity.activityResumed,
            canDrawOverlays = a.canDrawOverlays(),
            sdkInt = Build.VERSION.SDK_INT,
            settingEnabled = runtime.settings.autoOpenOnCast.value,
            lastLaunchAtMs = lastLaunchAtMs,
            nowMs = now,
        )
        when (decision) {
            AutoLaunchDecision.Launch -> {
                if (Build.VERSION.SDK_INT >= 35 && !a.isShown) {
                    runtime.log.warn("Auto-open: overlay window not visible; Android 15 may block the launch")
                }
                lastLaunchAtMs = now
                try {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(MainActivity.EXTRA_FROM_CAST, true)
                    )
                    runtime.log.info("Auto-open: bringing the player to the front")
                } catch (e: Exception) {
                    runtime.log.warn("Auto-open blocked: grant \"Display over other apps\" (${e.javaClass.simpleName})")
                }
            }
            is AutoLaunchDecision.Skip -> when (decision.reason) {
                AutoLaunchDecision.Reason.NO_OVERLAY_PERMISSION -> runtime.log.warn("Auto-open blocked: grant \"Display over other apps\"")
                AutoLaunchDecision.Reason.SETTING_OFF -> runtime.log.info("Auto-open is switched off in Settings")
                else -> {}
            }
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
        runtime.onCastStarted = null
        anchor?.remove(); anchor = null
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

        /** Starts the service; any refusal by Android is logged, never thrown. */
        fun start(context: Context): Boolean {
            val i = Intent(context, ReceiverService::class.java)
            return try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
                true
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException / BackgroundServiceStartNotAllowedException / SecurityException
                (context.applicationContext as? UniversalCastApp)?.runtime?.log?.error("Could not start the receiver service", e)
                false
            }
        }
    }
}
