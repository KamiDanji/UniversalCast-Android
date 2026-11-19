package com.universalcast.receiver.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.tv.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalcast.identity.PairedSender

@Composable
fun DevicesScreen(vm: AppViewModel, onBack: () -> Unit) {
    val senders by vm.runtime.senders.collectAsState()
    val connected by vm.runtime.connected.collectAsState()
    var confirm by remember { mutableStateOf<PairedSender?>(null) }

    ScreenScaffold("Paired devices", onBack) {
        if (senders.isEmpty()) {
            Text("No devices are paired yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(senders, key = { it.senderId }) { s ->
                    InfoCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(s.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                                Text("ID ${s.senderId.take(12)}… · paired ${formatDate(s.pairedAtMs)} · last seen ${formatDate(s.lastConnectedAtMs)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (s.isRevoked) Chip("Revoked ${formatDate(s.revokedAtMs)}", MaterialTheme.colorScheme.error)
                                    else {
                                        Chip(s.role.name.lowercase().replaceFirstChar { it.uppercase() }, MaterialTheme.colorScheme.primary)
                                        if (connected.any { it.senderId == s.senderId }) Chip("Connected", MaterialTheme.status.ok, live = true)
                                    }
                                }
                            }
                            if (!s.isRevoked) TvButton("Revoke", { confirm = s }, danger = true)
                        }
                    }
                }
            }
        }
    }

    confirm?.let { s ->
        TvDialog(
            title = "Revoke “${s.name}”?",
            onDismiss = { confirm = null },
            buttons = {
                TvButton("Keep", { confirm = null }, primary = false)
                TvButton("Revoke", { vm.revoke(s.senderId); confirm = null }, danger = true)
            },
        ) {
            Text("It will be disconnected immediately, playback it controls stops, and it can only return through a new pairing.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
