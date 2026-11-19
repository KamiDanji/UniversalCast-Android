package com.universalcast.receiver.platform

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.universalcast.playback.PlaybackStatus
import com.universalcast.playback.RemoteKey
import java.lang.ref.WeakReference

/**
 * Executes Google TV-style remote commands (`remote.key`) received over the authenticated UCP channel.
 *
 * Works immediately out-of-the-box inside UniversalCast (dispatching real [KeyEvent]s to the active window)
 * and for TV system volume/media keys via [AudioManager]. If the user also enables [TvRemoteAccessibilityService]
 * in Android TV Accessibility Settings, D-pad, Back and Home work system-wide across all TV apps.
 */
class TvRemoteDispatcher(
    private val context: Context,
    private val engine: Media3PlaybackEngine,
) {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile
    private var activeActivityRef: WeakReference<Activity>? = null

    /** Optional hook invoked when a remote key is pressed (e.g. to wake from Ambient screen or show an OSD toast). */
    @Volatile
    var onKeyAction: ((RemoteKey, String?) -> Unit)? = null

    fun attachActivity(activity: Activity) {
        activeActivityRef = WeakReference(activity)
    }

    fun detachActivity(activity: Activity) {
        if (activeActivityRef?.get() === activity) {
            activeActivityRef = null
        }
    }

    fun dispatchText(text: String, submit: Boolean) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("UniversalCast", text))
        }
        val act = activeActivityRef?.get()
        if (act != null) {
            runCatching {
                val decor = act.window?.decorView
                if (decor != null) {
                    val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
                    events?.forEach { ev -> decor.dispatchKeyEvent(ev) }
                    if (submit) {
                        sendKeyEventToActivity(act, KeyEvent.KEYCODE_ENTER)
                    }
                }
            }
        }
        onKeyAction?.invoke(RemoteKey.CENTER, "⌨ ${text.take(28)}")
    }

    fun dispatch(key: RemoteKey) {
        when (key) {
            RemoteKey.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE, +0.08f, "Volume +")
            RemoteKey.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER, -0.08f, "Volume −")
            RemoteKey.MUTE -> {
                runCatching {
                    if (audio.isVolumeFixed) {
                        val cur = engine.snapshot.volume
                        val next = if (cur > 0.01f) 0f else 1f
                        engine.setVolume(next)
                    } else {
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                    }
                }
                onKeyAction?.invoke(key, "Mute toggled")
            }
            RemoteKey.PLAY_PAUSE -> {
                val snap = engine.snapshot
                if (snap.status != PlaybackStatus.IDLE) {
                    if (snap.isPlaying) engine.pause() else engine.play()
                } else {
                    dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                }
                onKeyAction?.invoke(key, null)
            }
            RemoteKey.REWIND -> {
                val snap = engine.snapshot
                if (snap.status != PlaybackStatus.IDLE) {
                    engine.seekTo((snap.positionMs - 10_000L).coerceAtLeast(0L))
                } else {
                    dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_REWIND)
                }
                onKeyAction?.invoke(key, "−10 s")
            }
            RemoteKey.FAST_FORWARD -> {
                val snap = engine.snapshot
                if (snap.status != PlaybackStatus.IDLE) {
                    engine.seekTo(snap.positionMs + 10_000L)
                } else {
                    dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                }
                onKeyAction?.invoke(key, "+10 s")
            }
            RemoteKey.HOME -> {
                val a11y = TvRemoteAccessibilityService.instance
                if (a11y != null) {
                    a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                } else {
                    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(homeIntent) }
                }
                onKeyAction?.invoke(key, "Home")
            }
            RemoteKey.BACK -> {
                val snap = engine.snapshot
                val act = activeActivityRef?.get()
                val a11y = TvRemoteAccessibilityService.instance
                when {
                    snap.status != PlaybackStatus.IDLE -> engine.stop()
                    act != null -> sendKeyEventToActivity(act, KeyEvent.KEYCODE_BACK)
                    a11y != null -> a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }
                onKeyAction?.invoke(key, null)
            }
            RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT, RemoteKey.CENTER -> {
                val act = activeActivityRef?.get()
                val a11y = TvRemoteAccessibilityService.instance
                val keyCode = toKeyCode(key)
                if (act != null) {
                    sendKeyEventToActivity(act, keyCode)
                } else if (a11y != null) {
                    a11y.performDpad(key)
                }
                onKeyAction?.invoke(key, null)
            }
        }
    }

    private fun adjustVolume(direction: Int, playerDelta: Float, label: String) {
        runCatching {
            if (audio.isVolumeFixed) {
                engine.setVolume((engine.snapshot.volume + playerDelta).coerceIn(0f, 1f))
            } else {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            }
        }
        onKeyAction?.invoke(if (playerDelta > 0) RemoteKey.VOLUME_UP else RemoteKey.VOLUME_DOWN, label)
    }

    private fun dispatchSystemMediaKey(keyCode: Int) {
        runCatching {
            val now = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }
    }

    private fun sendKeyEventToActivity(activity: Activity, keyCode: Int) {
        runCatching {
            val now = SystemClock.uptimeMillis()
            val decor = activity.window?.decorView ?: return
            decor.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            decor.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }
    }

    companion object {
        fun toKeyCode(key: RemoteKey): Int = when (key) {
            RemoteKey.UP -> KeyEvent.KEYCODE_DPAD_UP
            RemoteKey.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            RemoteKey.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            RemoteKey.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            RemoteKey.CENTER -> KeyEvent.KEYCODE_DPAD_CENTER
            RemoteKey.BACK -> KeyEvent.KEYCODE_BACK
            RemoteKey.HOME -> KeyEvent.KEYCODE_HOME
            RemoteKey.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            RemoteKey.REWIND -> KeyEvent.KEYCODE_MEDIA_REWIND
            RemoteKey.FAST_FORWARD -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            RemoteKey.MUTE -> KeyEvent.KEYCODE_VOLUME_MUTE
            RemoteKey.VOLUME_UP -> KeyEvent.KEYCODE_VOLUME_UP
            RemoteKey.VOLUME_DOWN -> KeyEvent.KEYCODE_VOLUME_DOWN
        }

        fun isAccessibilityEnabled(context: Context): Boolean = TvRemoteAccessibilityService.instance != null || runCatching {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            enabled.contains(context.packageName)
        }.getOrDefault(false)

        fun openAccessibilitySettings(context: Context) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/**
 * Optional Android TV Accessibility Service that enables system-wide D-pad, Back and Home control from a
 * paired phone when UniversalCast is in the background.
 */
class TvRemoteAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun performDpad(key: RemoteKey): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val action = when (key) {
                RemoteKey.UP -> GLOBAL_ACTION_DPAD_UP
                RemoteKey.DOWN -> GLOBAL_ACTION_DPAD_DOWN
                RemoteKey.LEFT -> GLOBAL_ACTION_DPAD_LEFT
                RemoteKey.RIGHT -> GLOBAL_ACTION_DPAD_RIGHT
                RemoteKey.CENTER -> GLOBAL_ACTION_DPAD_CENTER
                else -> return false
            }
            return performGlobalAction(action)
        }
        return false
    }

    companion object {
        @Volatile
        var instance: TvRemoteAccessibilityService? = null
            private set
    }
}
