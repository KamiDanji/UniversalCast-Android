package com.universalcast.receiver.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.universalcast.network.NetworkAddresses
import com.universalcast.security.Fingerprint
import com.universalcast.storage.LoadOutcome

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val rt = vm.runtime
    val name by rt.receiverName.collectAsState()
    val running by rt.running.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var draft by remember(name) { mutableStateOf(name) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); tick++ } }
    val entries = remember(tick) { rt.log.entries().takeLast(40).reversed() }

    ScreenScaffold("Settings & diagnostics", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(Modifier.fillMaxWidth()) {
                    Text("Receiver name", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
                    Text(name, color = Palette.Text, style = MaterialTheme.typography.titleLarge)
                    TvButton("Rename", { editing = true }, primary = false)
                }
                Card(Modifier.fillMaxWidth()) {
                    InfoRow("Mode", rt.config.mode.wire)
                    InfoRow("Transport", "WSS (TLS 1.2/1.3 only), authentication required")
                    InfoRow("Status", if (running) "listening on port ${rt.core.boundPort}" else "stopped")
                    InfoRow("Addresses", NetworkAddresses.local().joinToString().ifEmpty { "none" })
                    InfoRow("mDNS", if (running) "_universalcast._tcp, id ${rt.identity.deviceId}" else "off")
                    InfoRow("Identity", "Android Keystore (EC P-256, non-exportable)")
                    Text("Fingerprint (SHA-256)", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
                    Text(Fingerprint.display(rt.identity.fingerprint), color = Palette.Text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    InfoRow("Paired-device store", when (rt.registryOutcome) {
                        LoadOutcome.FRESH -> "new"; LoadOutcome.LOADED -> "loaded"; LoadOutcome.MIGRATED -> "migrated"
                        LoadOutcome.CORRUPT_RESET -> "was corrupt – reset"; LoadOutcome.UNSUPPORTED_VERSION_RESET -> "newer version – set aside"
                        LoadOutcome.IDENTITY_CHANGED_RESET -> "identity changed – reset"
                    })
                }
            }
            Card(Modifier.weight(1f).fillMaxHeight()) {
                Text("Event log", color = Palette.TextDim, style = MaterialTheme.typography.titleMedium)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(entries) { e ->
                        val c = when (e.level) { "ERROR" -> Palette.Danger; "WARN" -> Palette.Warn; else -> Palette.TextDim }
                        Text("${formatTime(e.atMs % 86_400_000)} ${e.level}  ${e.message}", color = c, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    }
                }
            }
        }
    }

    if (editing) AlertDialog(
        onDismissRequest = { editing = false },
        containerColor = Palette.Surface,
        title = { Text("Receiver name", color = Palette.Text) },
        text = { OutlinedTextField(draft, { draft = it.take(40) }, singleLine = true) },
        confirmButton = { TvButton("Save", { rt.setName(draft); editing = false }) },
        dismissButton = { TvButton("Cancel", { editing = false }, primary = false) },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column { Text(label, color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium); Text(value, color = Palette.Text, style = MaterialTheme.typography.bodyLarge) }
}
