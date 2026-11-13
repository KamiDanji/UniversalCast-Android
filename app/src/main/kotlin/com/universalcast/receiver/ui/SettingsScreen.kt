package com.universalcast.receiver.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val autostart by rt.settings.autostartOnBoot.collectAsState()
    val autoOpen by rt.settings.autoOpenOnCast.collectAsState()
    val ctx = LocalContext.current
    val overlayGranted = remember(tick) { OverlayPermission.granted(ctx) }

    ScreenScaffold("Settings & diagnostics", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(Modifier.fillMaxWidth()) {
                    Text("Receiver name", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
                    Text(name, color = Palette.Text, style = MaterialTheme.typography.titleLarge)
                    TvButton("Rename", { editing = true }, primary = false)
                }
                Card(Modifier.fillMaxWidth()) {
                    Text("Start when the TV turns on", color = Palette.Text, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Starts the receiver after the TV boots. Open the app once after installing (Android keeps new apps stopped " +
                            "until then). A TV in deep standby pauses all apps; phones can reach it again once it is fully on.",
                        color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium,
                    )
                    TvButton(if (autostart) "On" else "Off", { rt.settings.setAutostartOnBoot(!autostart) }, primary = autostart)
                }
                Card(Modifier.fillMaxWidth()) {
                    Text("Open automatically when a phone casts", color = Palette.Text, style = MaterialTheme.typography.titleMedium)
                    TvButton(if (autoOpen) "On" else "Off", { rt.settings.setAutoOpenOnCast(!autoOpen) }, primary = autoOpen)
                    InfoRow(
                        "“Display over other apps”",
                        if (overlayGranted) "granted" else "not granted – Android blocks opening the player from the background",
                    )
                    if (!overlayGranted && OverlayPermission.canOpenSettings(ctx)) {
                        TvButton("Open permission screen", { OverlayPermission.openSettings(ctx) }, primary = false)
                    }
                    Text("If this TV has no such screen, run once from a computer:", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
                    Text(OverlayPermission.adbCommand(ctx), color = Palette.Text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
                Card(Modifier.fillMaxWidth()) {
                    val updates = (ctx.applicationContext as com.universalcast.receiver.UniversalCastApp).updates
                    val us by updates.state.collectAsState()
                    Text("App version", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
                    Text(updates.currentVersion, color = Palette.Text, style = MaterialTheme.typography.titleLarge)
                    TvButton(
                        if (us is com.universalcast.updater.UpdateState.Checking) "Checking…" else "Check for updates",
                        { updates.checkNow() }, primary = false, enabled = us !is com.universalcast.updater.UpdateState.Checking,
                    )
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
