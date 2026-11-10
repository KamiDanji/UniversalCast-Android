package com.universalcast.receiver.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import com.universalcast.playback.PlaybackStatus
import com.universalcast.receiver.service.ReceiverService

private enum class Screen { HOME, PAIRING, DEVICES, SETTINGS }

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

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

        setContent {
            UniversalCastTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                val playback by vm.runtime.playback.collectAsState()
                val playing = playback.status != PlaybackStatus.IDLE

                if (playing) {
                    BackHandler { vm.runtime.engine.stop() } // Back on the player stops playback and returns Home
                    PlayerScreen(vm.runtime.engine, playback, onStop = { vm.runtime.engine.stop() })
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
}
