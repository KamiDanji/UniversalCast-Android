package com.universalcast.receiver.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.universalcast.identity.Role
import com.universalcast.pairing.PairingBootstrap
import com.universalcast.pairing.PairingState
import com.universalcast.security.Fingerprint

@Composable
fun PairingScreen(vm: AppViewModel, onClose: () -> Unit) {
    var bootstrap by remember { mutableStateOf<PairingBootstrap?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var role by remember { mutableStateOf(Role.CONTROLLER) }
    val view by vm.runtime.pairing.collectAsState()

    LaunchedEffect(Unit) {
        vm.startPairing().onSuccess { bootstrap = it }.onFailure { failure = it.message ?: "Could not start pairing" }
    }
    // Leaving the screen always cancels a pending pairing.
    DisposableEffect(Unit) { onDispose { vm.cancelPairing() } }

    val qr = remember(bootstrap) { bootstrap?.let { QrCode.render(it.toUri()).asImageBitmap() } }

    ScreenScaffold("Pair a new device", onBack = onClose) {
        val v = view
        when {
            failure != null -> Text(failure!!, color = Palette.Danger, style = MaterialTheme.typography.titleLarge)
            v == null || bootstrap == null -> Text("Preparing…", color = Palette.TextDim, style = MaterialTheme.typography.titleLarge)
            v.state == PairingState.APPROVED -> ResultPane("Device paired", "${v.senderName ?: "Device"} was approved.", Palette.Ok, onClose)
            v.state == PairingState.REJECTED -> ResultPane("Pairing rejected", "Nothing was saved.", Palette.Warn, onClose)
            v.state == PairingState.EXPIRED -> ResultPane("Pairing window expired", "Start again to get a new code.", Palette.Warn, onClose)
            v.state == PairingState.FAILED -> ResultPane("Pairing failed", v.failureReason ?: "Verification failed. Start again.", Palette.Danger, onClose)
            v.state == PairingState.WAITING_FOR_SENDER -> Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                qr?.let { Image(it, "Pairing QR code", Modifier.size(420.dp)) }
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Scan this code with the sender app", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
                    Text("Expires in ${formatTime(v.remainingMs)}", style = MaterialTheme.typography.headlineMedium, color = if (v.remainingMs < 20_000) Palette.Warn else Palette.Accent)
                    Text("${bootstrap!!.addresses.firstOrNull() ?: "no network"}:${bootstrap!!.port}", color = Palette.TextDim, style = MaterialTheme.typography.bodyLarge)
                    Text("Fingerprint ${Fingerprint.display(bootstrap!!.fingerprint).take(47)}…", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    TvButton("Cancel", onClose, primary = false)
                }
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Verification code", color = Palette.TextDim, style = MaterialTheme.typography.titleMedium)
                    val code = v.sas.orEmpty()
                    Text(if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code,
                        fontSize = 88.sp, fontFamily = FontFamily.Monospace, color = Palette.Text, textAlign = TextAlign.Center)
                    Text("Expires in ${formatTime(v.remainingMs)}", color = if (v.remainingMs < 20_000) Palette.Warn else Palette.TextDim, style = MaterialTheme.typography.bodyLarge)
                }
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("“${v.senderName ?: "Unknown device"}” wants to pair", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
                    if (!v.sasVerifiedByUser) {
                        Text("Does this code match the one shown on the sender?", color = Palette.TextDim, style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TvButton("Codes match", { vm.sasMatches(true) })
                            TvButton("They differ", { vm.sasMatches(false) }, danger = true)
                        }
                    } else if (v.state != PairingState.READY_FOR_APPROVAL) {
                        Text("Waiting for the sender to confirm…", color = Palette.TextDim, style = MaterialTheme.typography.bodyLarge)
                    } else {
                        Text("Allow this device to:", color = Palette.TextDim, style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TvButton("Control playback", { role = Role.CONTROLLER }, primary = role == Role.CONTROLLER)
                            TvButton("Only observe", { role = Role.OBSERVER }, primary = role == Role.OBSERVER)
                        }
                        Text(if (role == Role.CONTROLLER) "Can start, pause, seek and stop playback." else "Can only see what is playing.", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TvButton("Approve", { vm.approve(role) })
                            TvButton("Reject", { vm.cancelPairing() }, danger = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultPane(title: String, text: String, color: androidx.compose.ui.graphics.Color, onClose: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = color)
        Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.TextDim)
        TvButton("Done", onClose)
    }
}
