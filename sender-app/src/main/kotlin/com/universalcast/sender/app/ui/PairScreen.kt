package com.universalcast.sender.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(vm: AppViewModel, onClose: () -> Unit) {
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
    // Leaving the screen always abandons a half-finished pairing.
    DisposableEffect(Unit) { onDispose { vm.resetPairing() } }

    Scaffold(
        modifier = Modifier.appBackground(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Pair a TV") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent, titleContentColor = Palette.Text, navigationIconContentColor = Palette.Text),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (val s = stage) {
                PairStage.Idle -> {
                    Panel {
                        Text("On the TV, open UniversalCast Receiver and choose “Pair a new device”. A QR code appears for two minutes.", color = Palette.TextDim)
                        Button(onClick = { scan() }, Modifier.fillMaxWidth()) { Text("Scan QR code") }
                        TextButton(onClick = { manualOpen = !manualOpen }, Modifier.fillMaxWidth()) { Text("Enter code manually") }
                        if (manualOpen) {
                            OutlinedTextField(manual, { manual = it }, label = { Text("ucp://pair?d=…") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                            Button(onClick = { vm.startPairing(manual) }, enabled = manual.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                        }
                    }
                }
                PairStage.Connecting -> Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text("Connecting securely to the TV…", color = Palette.Text)
                    }
                }
                is PairStage.Compare -> Panel {
                    Text("Compare this code with ${s.tvName}", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                    Text(
                        if (s.sas.length == 6) s.sas.substring(0, 3) + " " + s.sas.substring(3) else s.sas,
                        fontSize = 52.sp, fontFamily = FontFamily.Monospace, color = Palette.Accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                    Text("The same six digits must appear on the TV screen. This protects against someone intercepting the pairing.", color = Palette.TextDim)
                    Button(onClick = { vm.codesMatch() }, Modifier.fillMaxWidth()) { Text("They match") }
                    OutlinedButton(onClick = { vm.codesDiffer() }, Modifier.fillMaxWidth()) { Text("They are different", color = Palette.Danger) }
                }
                is PairStage.Waiting -> Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Column {
                            Text("Waiting for approval on ${s.tvName}", color = Palette.Text, style = MaterialTheme.typography.titleSmall)
                            Text("On the TV, confirm the code, choose what this phone may do, and press Approve.", color = Palette.TextDim)
                        }
                    }
                }
                is PairStage.Done -> Panel {
                    Text("Paired with ${s.receiver.name}", style = MaterialTheme.typography.titleLarge, color = Palette.Ok)
                    Text(
                        if (s.receiver.role == "CONTROLLER") "This phone can control playback." else "This phone can only watch what is playing.",
                        color = Palette.TextDim,
                    )
                    Button(onClick = onClose, Modifier.fillMaxWidth()) { Text("Done") }
                }
                is PairStage.Error -> Panel {
                    Text("Pairing did not finish", style = MaterialTheme.typography.titleMedium, color = Palette.Danger)
                    Text(s.message, color = Palette.Text)
                    Button(onClick = { vm.resetPairing() }, Modifier.fillMaxWidth()) { Text("Try again") }
                }
            }
        }
    }
}
