package com.universalcast.receiver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.universalcast.receiver.ReceiverSettings
import com.universalcast.receiver.UniversalCastApp
import com.universalcast.receiver.policy.shouldAutostart

/**
 * Starts the receiver service after the TV boots (BOOT_COMPLETED, vendor QUICKBOOT_POWERON) or after an app update
 * (MY_PACKAGE_REPLACED), if "Start when the TV turns on" is enabled.
 *
 * Limits (Android, not this app): after installing, Android keeps an app in the "stopped" state and delivers no
 * broadcasts until the user has opened it once; and a TV in deep standby suspends apps, which no app can change.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (!shouldAutostart(action, ReceiverSettings.autostartEnabled(context))) return
        val log = (context.applicationContext as? UniversalCastApp)?.runtime?.log
        log?.info("Auto-start after ${action?.substringAfterLast('.')}")
        try {
            ReceiverService.start(context) // logs its own failures
        } catch (e: Exception) {
            log?.error("Auto-start failed", e)
        }
    }
}
