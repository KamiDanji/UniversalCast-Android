package com.universalcast.sender.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(vm: AppViewModel, onClose: () -> Unit, onOpenRemote: () -> Unit = onClose) {
    val stage by vm.stage.collectAsState()
    var manual by remember { mutableStateOf("") }
    var manualOpen by remember { mutableStateOf(false) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { vm.startPairing(it) }
    }
    fun scan() {
        scanner.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the QR code on your TV")
                .setBeepEnabled(false).setOrientationLocked(false)
        )
    }
    // Opening "Pair a TV" goes straight to the camera: one tap fewer. Backing out of the scanner lands on the options below.
    var autoScanned by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoScanned && stage == PairStage.Idle) { autoScanned = true; scan() }
    }
    // Leaving the screen always abandons a half-finished pairing.
    DisposableEffect(Unit) { onDispose { vm.resetPairing() } }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = heroBarColors(),
                title = { Text("Pair a TV") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (val s = stage) {
                PairStage.Idle -> {
                    Panel {
                        Icon(Icons.Filled.QrCodeScanner, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                        Text("On the TV, open UniversalCast Receiver and choose “Pair a new device”. A QR code appears for two minutes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { scan() }, Modifier.fillMaxWidth()) { Text("Scan QR code") }
                        TextButton(onClick = { manualOpen = !manualOpen }, Modifier.fillMaxWidth()) { Text("Enter code manually") }
                        if (manualOpen) {
                            OutlinedTextField(manual, { manual = it }, label = { Text("ucp://pair?d=…") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                            FilledTonalButton(onClick = { vm.startPairing(manual) }, enabled = manual.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                        }
                    }
                }
                PairStage.Connecting -> Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text("Connecting securely to the TV…")
                    }
                }
                is PairStage.Compare -> Panel {
                    Text("Compare this code with ${s.tvName}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (s.sas.length == 6) s.sas.substring(0, 3) + " " + s.sas.substring(3) else s.sas,
                        style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    Text("The same six digits must appear on the TV screen. This protects against someone intercepting the pairing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { vm.codesMatch() }, Modifier.fillMaxWidth()) { Text("They match") }
                    OutlinedButton(onClick = { vm.codesDiffer() }, Modifier.fillMaxWidth()) { Text("They are different", color = MaterialTheme.colorScheme.error) }
                }
                is PairStage.Waiting -> Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Column {
                            Text("Waiting for approval on ${s.tvName}", style = MaterialTheme.typography.titleSmall)
                            Text("On the TV, confirm the code, choose what this phone may do, and press Approve.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                is PairStage.Done -> Panel {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.status.ok, modifier = Modifier.size(40.dp))
                    Text("Paired with ${s.receiver.name}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.status.ok)
                    Text(
                        if (s.receiver.role == "CONTROLLER") "This phone can control playback." else "This phone can only watch what is playing.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Straight to the remote: saves going back Home and tapping the new TV.
                    Button(onClick = { vm.rt.connect(s.receiver); onOpenRemote() }, Modifier.fillMaxWidth()) { Text("Connect & open remote") }
                    TextButton(onClick = onClose, Modifier.fillMaxWidth()) { Text("Done") }
                }
                is PairStage.Error -> Panel {
                    Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                    Text("Pairing did not finish", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Text(s.message)
                    Button(onClick = { vm.resetPairing(); scan() }, Modifier.fillMaxWidth()) { Text("Scan again") }
                }
            }
        }
    }
}
