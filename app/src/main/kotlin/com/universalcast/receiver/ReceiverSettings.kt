package com.universalcast.receiver

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User settings of the TV receiver (SharedPreferences, same file as the receiver name). */
class ReceiverSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _autostartOnBoot = MutableStateFlow(readAutostart(prefs))
    /** "Start when the TV turns on" (default on). */
    val autostartOnBoot: StateFlow<Boolean> = _autostartOnBoot.asStateFlow()

    private val _autoOpenOnCast = MutableStateFlow(prefs.getBoolean(KEY_AUTO_OPEN, true))
    /** "Open automatically when a phone casts" (default on; also needs the overlay permission on Android 10+). */
    val autoOpenOnCast: StateFlow<Boolean> = _autoOpenOnCast.asStateFlow()

    fun setAutostartOnBoot(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOSTART, on).apply()
        _autostartOnBoot.value = on
    }

    fun setAutoOpenOnCast(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_OPEN, on).apply()
        _autoOpenOnCast.value = on
    }

    companion object {
        const val FILE = "ucp_settings"
        private const val KEY_AUTOSTART = "autostart_on_boot"
        private const val KEY_AUTO_OPEN = "auto_open_on_cast"

        private fun readAutostart(p: android.content.SharedPreferences) = p.getBoolean(KEY_AUTOSTART, true)

        /** For the boot receiver, which must not depend on the whole runtime having started. */
        fun autostartEnabled(context: Context): Boolean =
            readAutostart(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
