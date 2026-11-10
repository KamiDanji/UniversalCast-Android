package com.universalcast.receiver.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.universalcast.playback.PlaybackStatus

@Composable
fun HomeScreen(vm: AppViewModel, onPair: () -> Unit, onDevices: () -> Unit, onSettings: () -> Unit) {
    val rt = vm.runtime
    val running by rt.running.collectAsState()
    val error by rt.startError.collectAsState()
    val name by rt.receiverName.collectAsState()
    val senders by rt.senders.collectAsState()
    val connected by rt.connected.collectAsState()
    val playback by rt.playback.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    ScreenScaffold("UniversalCast", onBack = null) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp), modifier = Modifier.fillMaxSize()) {
            Card(Modifier.weight(1.3f).fillMaxHeight()) {
                Text(name, style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
                Spacer(Modifier.height(8.dp))
                when {
                    error != null -> { Chip("Not running", Palette.Danger); Text(error!!, color = Palette.Danger, style = MaterialTheme.typography.bodyLarge) }
                    running -> {
                        Chip("Ready · secure (TLS) · port ${rt.core.boundPort}", Palette.Ok)
                        if (rt.config.mode.wire == "development") { Spacer(Modifier.height(4.dp)); Chip("Development build", Palette.Warn) }
                    }
                    else -> Chip("Starting…", Palette.Warn)
                }
                Spacer(Modifier.height(16.dp))
                Text("${senders.count { !it.isRevoked }} paired device(s) · ${connected.size} connected", color = Palette.TextDim, style = MaterialTheme.typography.bodyLarge)
                if (playback.status != PlaybackStatus.IDLE) {
                    Spacer(Modifier.height(16.dp))
                    Text("Now playing: ${playback.title ?: playback.mediaUri ?: ""}", color = Palette.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                }
                if (connected.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("Connected", color = Palette.TextDim, style = MaterialTheme.typography.titleMedium)
                    connected.forEach { Text("• ${it.name} (${it.role.name.lowercase()})", color = Palette.Text, style = MaterialTheme.typography.bodyLarge) }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                TvButton("Pair a new device", onPair, Modifier.fillMaxWidth().focusRequester(first), enabled = running)
                TvButton("Paired devices", onDevices, Modifier.fillMaxWidth(), primary = false)
                TvButton("Settings & diagnostics", onSettings, Modifier.fillMaxWidth(), primary = false)
            }
        }
    }
}
