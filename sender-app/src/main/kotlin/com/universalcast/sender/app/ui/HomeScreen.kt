package com.universalcast.sender.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalcast.sender.ConnectionPhase
import com.universalcast.sender.PairedReceiver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onPair: () -> Unit,
    onOpenRemote: () -> Unit,
    onBrowse: () -> Unit,
    onAbout: () -> Unit = {},
) {
    val rt = vm.rt
    val receivers by rt.receivers.collectAsState()
    val nearby by rt.discovery.tvs.collectAsState()
    val state by rt.state.collectAsState()
    val notice by rt.notice.collectAsState()
    val name by rt.deviceName.collectAsState()
    val pending by vm.pendingUrl.collectAsState()
    var forgetting by remember { mutableStateOf<PairedReceiver?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var appearance by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("UniversalCast") },
                subtitle = {
                    val paired = receivers.size
                    Text(
                        if (paired == 0) "Cast anything to your TV"
                        else "$paired TV${if (paired == 1) "" else "s"} paired" + if (nearby.isNotEmpty()) " · ${nearby.size} nearby" else "",
                    )
                },
                scrollBehavior = scroll,
                actions = {
                    IconButton(onClick = { appearance = true }) { Icon(Icons.Filled.Palette, "Appearance") }
                    var appMenu by remember { mutableStateOf(false) }
                    val updates = (LocalContext.current.applicationContext as com.universalcast.sender.app.SenderApp).updates
                    Box {
                        IconButton(onClick = { appMenu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                        DropdownMenu(expanded = appMenu, onDismissRequest = { appMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("This phone: $name") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                onClick = { appMenu = false; renaming = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Check for updates (v${updates.currentVersion})") }, leadingIcon = { Icon(Icons.Filled.SystemUpdate, null) },
                                onClick = { appMenu = false; updates.checkNow() },
                            )
                            DropdownMenuItem(
                                text = { Text("About UniversalCast") }, leadingIcon = { Icon(Icons.Filled.Info, null) },
                                onClick = { appMenu = false; onAbout() },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            // With no TV paired the empty state already carries the big "Pair a TV" button.
            if (receivers.isNotEmpty()) {
                MediumExtendedFloatingActionButton(onClick = onPair, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Pair a TV") })
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 112.dp, top = 4.dp),
        ) {
            notice?.let { n ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)) {
                        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Info, null)
                            Text(n, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { rt.dismissNotice() }) { Text("Dismiss") }
                        }
                    }
                }
            }
            pending?.let { url ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Link ready to cast", style = MaterialTheme.typography.titleSmall)
                            Text(url, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            Text("Tap a TV below to cast it.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { vm.setPendingUrl(null) }) { Text("Discard link") }
                        }
                    }
                }
            }
            if (state.phase == ConnectionPhase.CONNECTED || state.phase == ConnectionPhase.RECONNECTING) {
                item {
                    val connected = state.phase == ConnectionPhase.CONNECTED
                    ElevatedCard(
                        onClick = onOpenRemote,
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            PulseDot(if (connected) MaterialTheme.status.ok else MaterialTheme.status.warn, 12.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (state.isDummyMode) "Connected to Dummy TV (Dev Simulator)"
                                    else if (connected) "Connected to ${state.receiver?.name}"
                                    else "Reconnecting to ${state.receiver?.name}…",
                                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text("Tap to open the remote", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            if (state.isDummyMode) {
                                TextButton(onClick = { rt.controller.disconnect() }) { Text("Exit", color = MaterialTheme.colorScheme.error) }
                            } else Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }

            item { SectionLabel("Your TVs") }
            if (receivers.isEmpty()) {
                item {
                    Panel {
                        Text("No TV paired yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Open UniversalCast on your TV and choose “Pair a new device”, then scan the QR code it shows.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = onPair, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                            Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Pair a TV")
                        }
                    }
                }
            }
            items(receivers, key = { it.fingerprint }) { r ->
                val isNearby = nearby.any { it.deviceId != null && it.deviceId == r.deviceId }
                val connectedHere = state.receiver?.fingerprint == r.fingerprint && state.phase == ConnectionPhase.CONNECTED
                // One tap on the whole card connects and opens the remote.
                Card(
                    onClick = { rt.connect(r); onOpenRemote() },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ShapeBadge(Icons.Filled.Tv, MaterialShapes.Cookie9Sided)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip(r.role.lowercase().replaceFirstChar { it.uppercase() }, MaterialTheme.colorScheme.primary)
                                if (connectedHere) Chip("Connected", MaterialTheme.status.ok, live = true)
                                else if (isNearby) Chip("Nearby", MaterialTheme.status.ok, live = true)
                                else Chip("Not seen yet", MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Forget this TV", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; forgetting = r },
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    onClick = onBrowse,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ShapeBadge(Icons.Filled.TravelExplore, MaterialShapes.Flower, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                        Column(Modifier.weight(1f)) {
                            Text("Cast from a website", style = MaterialTheme.typography.titleSmall)
                            Text("Play a video in the browser, cast what it plays.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            val unpaired = nearby.filter { t -> receivers.none { it.deviceId == t.deviceId } }
            if (unpaired.isNotEmpty()) {
                item { SectionLabel("Found nearby") }
                items(unpaired, key = { it.serviceName }) { t ->
                    // Tapping goes straight to the scanner.
                    Card(onClick = onPair, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            PulseDot(MaterialTheme.status.ok, 10.dp)
                            Column(Modifier.weight(1f)) {
                                Text(t.serviceName, style = MaterialTheme.typography.titleSmall)
                                Text("Not paired yet · tap to pair by scanning its QR code", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Filled.QrCodeScanner, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    if (appearance) AppearanceSheet { appearance = false }

    forgetting?.let { r ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text("Forget ${r.name}?") },
            text = { Text("This phone will stop connecting to it. To use it again you must pair again. (To also stop the TV accepting this phone, use “Paired devices” on the TV.)") },
            confirmButton = { TextButton(onClick = { rt.forget(r); forgetting = null }) { Text("Forget", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Keep") } },
        )
    }
    if (renaming) {
        var draft by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Name of this phone") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Shown on the TV when you pair.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(draft, { draft = it.take(40) }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { rt.setDeviceName(draft); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}
