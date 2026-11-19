package com.universalcast.receiver.ui

import androidx.compose.foundation.layout.*
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.universalcast.playback.PlaybackStatus
import com.universalcast.receiver.platform.TvRemoteDispatcher

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onPair: () -> Unit,
    onDevices: () -> Unit,
    onSettings: () -> Unit,
    onAmbient: () -> Unit = {},
) {
    val rt = vm.runtime
    val running by rt.running.collectAsState()
    val error by rt.startError.collectAsState()
    val name by rt.receiverName.collectAsState()
    val senders by rt.senders.collectAsState()
    val connected by rt.connected.collectAsState()
    val playback by rt.playback.collectAsState()
    val first = remember { FocusRequester() }
    // The phone's D-pad / Back / Home only work in other TV apps once the accessibility service is on: offer it in one press.
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); tick++ } }
    val systemRemoteOn = remember(tick) { TvRemoteDispatcher.isAccessibilityEnabled(ctx) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    ScreenScaffold("UniversalCast", onBack = null) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp), modifier = Modifier.fillMaxSize()) {
            InfoCard(Modifier.weight(1.3f).fillMaxHeight()) {
                Text(name, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                when {
                    error != null -> { Chip("Not running", MaterialTheme.colorScheme.error); Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
                    running -> {
                        Chip("Ready · secure (TLS) · port ${rt.core.boundPort}", MaterialTheme.status.ok, live = true)
                        if (rt.config.mode.wire == "development") { Spacer(Modifier.height(4.dp)); Chip("Development build", MaterialTheme.status.warn) }
                    }
                    else -> Chip("Starting…", MaterialTheme.status.warn, live = true)
                }
                Spacer(Modifier.height(16.dp))
                Text("${senders.count { !it.isRevoked }} paired device(s) · ${connected.size} connected", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                if (playback.status != PlaybackStatus.IDLE) {
                    Spacer(Modifier.height(16.dp))
                    Text("Now playing: ${playback.title ?: playback.mediaUri ?: ""}", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                }
                if (connected.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("Connected", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
                    connected.forEach { Text("• ${it.name} (${it.role.name.lowercase()})", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge) }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                TvButton("Pair a new device", onPair, Modifier.fillMaxWidth().focusRequester(first), enabled = running)
                TvButton("Paired devices", onDevices, Modifier.fillMaxWidth(), primary = false)
                TvButton("Ambient clock screen", onAmbient, Modifier.fillMaxWidth(), primary = false)
                TvButton("Settings & diagnostics", onSettings, Modifier.fillMaxWidth(), primary = false)
                if (!systemRemoteOn) {
                    // The phone's D-pad / Back / Home only reach other TV apps once the accessibility service is on.
                    TvButton(
                        "Turn on phone remote for all apps",
                        { if (!TvRemoteDispatcher.selfEnable(ctx)) TvRemoteDispatcher.openAccessibilitySettings(ctx) },
                        Modifier.fillMaxWidth(), primary = false,
                    )
                    Text(
                        "Right now your phone's D-pad, Back and Home only work inside UniversalCast.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
