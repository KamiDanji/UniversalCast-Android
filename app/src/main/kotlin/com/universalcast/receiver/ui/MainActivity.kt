package com.universalcast.receiver.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.universalcast.playback.PlaybackStatus
import com.universalcast.receiver.service.ReceiverService

private enum class Screen { HOME, PAIRING, DEVICES, SETTINGS }

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    /** True while this activity was brought to the front by a cast and has not been left since. */
    private var openedByCast by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Denial is fine: the foreground service still runs, only its notification is hidden.
            registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ReceiverService.start(this)
        handleCastIntent(intent)

        setContent {
            UniversalCastTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                val playback by vm.runtime.playback.collectAsState()
                val playing = playback.status != PlaybackStatus.IDLE

                // Immersive while a video is shown; system bars come back when playback ends.
                LaunchedEffect(playing) { setSystemBarsHidden(playing) }
                // Opened by a cast and the cast is over: give the screen back to whatever the TV showed before.
                var wasPlaying by remember { mutableStateOf(playing) }
                LaunchedEffect(playing) {
                    if (wasPlaying && !playing && openedByCast) {
                        openedByCast = false
                        setWakeFlags(false)
                        moveTaskToBack(true)
                    }
                    wasPlaying = playing
                }

                if (playing) {
                    BackHandler { vm.runtime.engine.stop() } // safety exit: Back stops playback and returns Home
                    PlayerScreen(vm.runtime.engine, playback)
                } else {
                    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
                    when (screen) {
                        Screen.HOME -> HomeScreen(vm, { screen = Screen.PAIRING }, { screen = Screen.DEVICES }, { screen = Screen.SETTINGS })
                        Screen.PAIRING -> PairingScreen(vm) { screen = Screen.HOME }
                        Screen.DEVICES -> DevicesScreen(vm) { screen = Screen.HOME }
                        Screen.SETTINGS -> SettingsScreen(vm) { screen = Screen.HOME }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCastIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        openedByCast = false // the user went elsewhere themselves (Home): don't move the task later
    }

    /** While the player is shown: media keys per [ALLOW_REMOTE_MEDIA_KEYS], every other non-system key does nothing. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val snap = vm.runtime.playback.value
        if (snap.status != PlaybackStatus.IDLE && handlePlayerKey(event, snap, vm.runtime.engine)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleCastIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_FROM_CAST, false) == true) {
            openedByCast = true
            setWakeFlags(true) // wake a dimmed screen
            i.removeExtra(EXTRA_FROM_CAST) // not again on recreation
        }
    }

    private fun setWakeFlags(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setTurnScreenOn(on)
            setShowWhenLocked(on)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            if (on) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    private fun setSystemBarsHidden(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        const val EXTRA_FROM_CAST = "com.universalcast.receiver.extra.FROM_CAST"

        /** Read by the service to decide whether a cast needs to bring the app to the front. */
        @Volatile
        @JvmStatic
        var activityResumed: Boolean = false
            private set
    }
}
