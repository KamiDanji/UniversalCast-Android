package com.universalcast.receiver.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.universalcast.core.DiagnosticsLog

/**
 * A 1×1 px transparent, non-focusable, non-touchable TYPE_APPLICATION_OVERLAY window kept while the receiver
 * service runs and "Display over other apps" (SYSTEM_ALERT_WINDOW) is granted.
 *
 * Why: Android 10+ lets an app start an activity from the background when the user granted SYSTEM_ALERT_WINDOW.
 * Android 15 additionally says "Block non-visible windows from being considered for background activity launches"
 * and requires a *visible* TYPE_APPLICATION_OVERLAY window for the related SYSTEM_ALERT_WINDOW foreground-service
 * exemption. A single transparent pixel is the least intrusive visible overlay window. It never takes focus or touches,
 * so it cannot capture remote keys or input. Android TV builds vary (some lack the permission screen or forbid
 * overlays), so every call is best effort and never throws.
 */
class OverlayAnchor(private val context: Context, private val log: DiagnosticsLog) {
    private var view: View? = null

    val isShown: Boolean get() = view?.windowVisibility == View.VISIBLE

    fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < 23 || runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    /** Adds the window if allowed and not present; removes it if the permission was withdrawn. */
    fun sync() {
        if (Build.VERSION.SDK_INT < 26) return // TYPE_APPLICATION_OVERLAY is API 26; older Androids don't restrict launches
        if (!canDrawOverlays()) { remove(); return }
        if (view != null) return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val v = View(context).apply { setBackgroundColor(0x00000000) }
        @Suppress("DEPRECATION")
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            title = "UniversalCast anchor"
        }
        try {
            wm.addView(v, lp)
            view = v
            log.info("Auto-open: overlay anchor window added")
        } catch (e: Exception) {
            log.warn("Auto-open: overlay window not allowed on this TV (${e.javaClass.simpleName})")
        }
    }

    fun remove() {
        val v = view ?: return
        view = null
        runCatching { context.getSystemService(WindowManager::class.java)?.removeView(v) }
    }
}
