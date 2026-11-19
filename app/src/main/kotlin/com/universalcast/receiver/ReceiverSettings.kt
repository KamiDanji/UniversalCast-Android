package com.universalcast.receiver

import android.content.Context
import com.universalcast.playback.SubtitleStyle
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

    private val _showPlayerOsd = MutableStateFlow(prefs.getBoolean(KEY_SHOW_OSD, true))
    /** "Show subtle 2-second status pill on TV when seeking, changing volume, tracks or speed" (default on). */
    val showPlayerOsd: StateFlow<Boolean> = _showPlayerOsd.asStateFlow()

    private val _autoAmbient = MutableStateFlow(prefs.getBoolean(KEY_AUTO_AMBIENT, true))
    /** "Enter ambient clock screen when idle" (default on). */
    val autoAmbient: StateFlow<Boolean> = _autoAmbient.asStateFlow()

    private val _subtitleStyle = MutableStateFlow(readSubtitleStyle(prefs))
    /** Subtitle appearance on the TV player (size, background opacity, bottom margin). */
    val subtitleStyle: StateFlow<SubtitleStyle> = _subtitleStyle.asStateFlow()

    fun setAutostartOnBoot(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOSTART, on).apply()
        _autostartOnBoot.value = on
    }

    fun setAutoOpenOnCast(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_OPEN, on).apply()
        _autoOpenOnCast.value = on
    }

    fun setShowPlayerOsd(on: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_OSD, on).apply()
        _showPlayerOsd.value = on
    }

    fun setAutoAmbient(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_AMBIENT, on).apply()
        _autoAmbient.value = on
    }

    fun setSubtitleStyle(style: SubtitleStyle) {
        val clean = SubtitleStyle(
            fontScale = style.fontScale.coerceIn(0.5f, 2.5f),
            backgroundOpacity = style.backgroundOpacity.coerceIn(0f, 1f),
            bottomPaddingFraction = style.bottomPaddingFraction.coerceIn(0f, 0.35f),
        )
        prefs.edit()
            .putFloat(KEY_SUB_SCALE, clean.fontScale)
            .putFloat(KEY_SUB_BG, clean.backgroundOpacity)
            .putFloat(KEY_SUB_PAD, clean.bottomPaddingFraction)
            .apply()
        _subtitleStyle.value = clean
    }

    companion object {
        const val FILE = "ucp_settings"
        private const val KEY_AUTOSTART = "autostart_on_boot"
        private const val KEY_AUTO_OPEN = "auto_open_on_cast"
        private const val KEY_SHOW_OSD = "show_player_osd"
        private const val KEY_AUTO_AMBIENT = "auto_ambient"
        private const val KEY_SUB_SCALE = "sub_font_scale"
        private const val KEY_SUB_BG = "sub_bg_opacity"
        private const val KEY_SUB_PAD = "sub_bottom_pad"

        private fun readAutostart(p: android.content.SharedPreferences) = p.getBoolean(KEY_AUTOSTART, true)

        private fun readSubtitleStyle(p: android.content.SharedPreferences) = SubtitleStyle(
            fontScale = p.getFloat(KEY_SUB_SCALE, 1.0f).coerceIn(0.5f, 2.5f),
            backgroundOpacity = p.getFloat(KEY_SUB_BG, 0.6f).coerceIn(0f, 1f),
            bottomPaddingFraction = p.getFloat(KEY_SUB_PAD, 0.08f).coerceIn(0f, 0.35f),
        )

        /** For the boot receiver, which must not depend on the whole runtime having started. */
        fun autostartEnabled(context: Context): Boolean =
            readAutostart(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
