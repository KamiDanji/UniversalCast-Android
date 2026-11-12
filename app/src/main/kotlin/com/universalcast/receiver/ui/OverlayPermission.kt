package com.universalcast.receiver.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** "Display over other apps" (SYSTEM_ALERT_WINDOW) helpers for the Settings screen. */
object OverlayPermission {
    fun granted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    private fun intent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Many Android TV builds do not ship this settings screen. */
    fun canOpenSettings(ctx: Context): Boolean =
        runCatching { intent(ctx).resolveActivity(ctx.packageManager) != null }.getOrDefault(false)

    fun openSettings(ctx: Context) {
        runCatching { ctx.startActivity(intent(ctx)) }
    }

    /** Works on every TV with adb; [Context.getPackageName] already includes the `.debug` suffix of debug builds. */
    fun adbCommand(ctx: Context): String = adbCommand(ctx.packageName)

    fun adbCommand(packageName: String): String = "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"
}
