package com.universalcast.sender.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.universalcast.sender.ConnectionPhase
import com.universalcast.sender.PairedReceiver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, onPair: () -> Unit, onOpenRemote: () -> Unit, onBrowse: () -> Unit) {
    val rt = vm.rt
    val receivers by rt.receivers.collectAsState()
    val nearby by rt.discovery.tvs.collectAsState()
    val state by rt.state.collectAsState()
    val notice by rt.notice.collectAsState()
    val name by rt.deviceName.collectAsState()
    val pending by vm.pendingUrl.collectAsState()
    var forgetting by remember { mutableStateOf<PairedReceiver?>(null) }
    var renaming by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.appBackground(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("UniversalCast", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    TextButton(onClick = { renaming = true }) { Text(name, color = Palette.TextDim, maxLines = 1) }
                    var appMenu by remember { mutableStateOf(false) }
                    val updates = (LocalContext.current.applicationContext as com.universalcast.sender.app.SenderApp).updates
                    Box {
                        IconButton(onClick = { appMenu = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = Palette.TextDim) }
                        DropdownMenu(expanded = appMenu, onDismissRequest = { appMenu = false }) {
                            DropdownMenuItem(text = { Text("Check for updates (v${updates.currentVersion})") }, onClick = { appMenu = false; updates.checkNow() })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent, titleContentColor = Palette.Text),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onPair, modifier = Modifier.pressScale(), containerColor = Palette.Accent, contentColor = Palette.OnAccent) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Pair a TV")
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 96.dp, top = 4.dp),
        ) {
            notice?.let { n ->
                item {
                    Panel {
                        Text(n, color = Palette.Warn)
                        TextButton(onClick = { rt.dismissNotice() }) { Text("Dismiss") }
                    }
                }
            }
            pending?.let { url ->
                item {
                    Panel {
                        Text("Link ready to cast", style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                        Text(url, color = Palette.TextDim, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                        Text("Choose a TV below.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.setPendingUrl(null) }) { Text("Discard link") }
                    }
                }
            }
            if (state.phase == ConnectionPhase.CONNECTED || state.phase == ConnectionPhase.RECONNECTING) {
                item {
                    Panel {
                        Text(if (state.phase == ConnectionPhase.CONNECTED) "Connected to ${state.receiver?.name}" else "Reconnecting to ${state.receiver?.name}…", color = Palette.Ok, style = MaterialTheme.typography.titleSmall)
                        Button(onClick = onOpenRemote) { Text("Open remote") }
                    }
                }
            }

            item { Text("Your TVs", style = MaterialTheme.typography.titleMedium, color = Palette.Text, modifier = Modifier.padding(top = 8.dp)) }
            if (receivers.isEmpty()) {
                item {
                    Panel {
                        Text("No TV paired yet", style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                        Text("Open UniversalCast on your TV, choose “Pair a new device”, then tap “Pair a TV” and scan the QR code.", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(receivers, key = { it.fingerprint }) { r ->
                val isNearby = nearby.any { it.deviceId != null && it.deviceId == r.deviceId }
                val connectedHere = state.receiver?.fingerprint == r.fingerprint && state.phase == ConnectionPhase.CONNECTED
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip(r.role.lowercase().replaceFirstChar { it.uppercase() }, Palette.Accent)
                                if (connectedHere) Chip("Connected", Palette.Ok, live = true) else if (isNearby) Chip("Nearby", Palette.Ok, live = true) else Chip("Not seen yet", Palette.TextDim)
                            }
                        }
                        Button(onClick = { rt.connect(r); onOpenRemote() }, modifier = Modifier.pressScale()) { Text(if (connectedHere) "Open" else "Connect") }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = Palette.TextDim) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Forget this TV", color = Palette.Danger) }, onClick = { menu = false; forgetting = r })
                            }
                        }
                    }
                }
            }

            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Cast from a website", style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                            Text("Play a video in the browser, cast what it plays.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(12.dp))
                        FilledTonalButton(onClick = onBrowse, modifier = Modifier.pressScale()) { Text("Browser") }
                    }
                }
            }

            val unpaired = nearby.filter { t -> receivers.none { it.deviceId == t.deviceId } }
            if (unpaired.isNotEmpty()) {
                item { Text("Found nearby", style = MaterialTheme.typography.titleMedium, color = Palette.Text, modifier = Modifier.padding(top = 8.dp)) }
                items(unpaired, key = { it.serviceName }) { t ->
                    Panel {
                        Text(t.serviceName, style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                        Text("Not paired. Pair it by scanning the QR code on the TV.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    forgetting?.let { r ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${r.name}?") },
            text = { Text("This phone will stop connecting to it. To use it again you must pair again. (To also stop the TV accepting this phone, use “Paired devices” on the TV.)") },
            confirmButton = { TextButton(onClick = { rt.forget(r); forgetting = null }) { Text("Forget", color = Palette.Danger) } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Keep") } },
        )
    }
    if (renaming) {
        var draft by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Name of this phone") },
            text = { Column { Text("Shown on the TV when you pair.", color = Palette.TextDim); OutlinedTextField(draft, { draft = it.take(40) }, singleLine = true) } },
            confirmButton = { TextButton(onClick = { rt.setDeviceName(draft); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}
