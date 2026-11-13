package com.universalcast.sender.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalcast.playback.PlaybackStatus
import com.universalcast.sender.ConnectionPhase
import com.universalcast.sender.FailureKind
import com.universalcast.sender.app.Links
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(vm: AppViewModel, onClose: () -> Unit, onPairAgain: () -> Unit, onBrowse: () -> Unit) {
    val rt = vm.rt
    val st by rt.state.collectAsState()
    val pending by vm.pendingUrl.collectAsState()
    val snap = st.snapshot
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    // A shared link pre-fills the field once.
    LaunchedEffect(pending) { pending?.let { url = it; vm.setPendingUrl(null) } }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    val clipboard = LocalClipboardManager.current

    Scaffold(
        modifier = Modifier.appBackground(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(st.receiver?.name ?: "Remote", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    when (st.phase) {
                        ConnectionPhase.CONNECTED -> Chip(if (st.canControl) "Controller" else "Observer", Palette.Ok, live = true)
                        ConnectionPhase.CONNECTING, ConnectionPhase.RECONNECTING -> Chip("Connecting…", Palette.Warn, live = true)
                        else -> Chip("Offline", Palette.Danger)
                    }
                    Spacer(Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent, titleContentColor = Palette.Text, navigationIconContentColor = Palette.Text),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // --- connection problems -------------------------------------------------------------------
            if (st.phase == ConnectionPhase.CONNECTING || st.phase == ConnectionPhase.RECONNECTING) {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Text(st.message ?: "Connecting securely…", color = Palette.Text)
                    }
                }
            }
            if (st.phase == ConnectionPhase.FAILED || st.phase == ConnectionPhase.DISCONNECTED) {
                Panel {
                    Text(if (st.phase == ConnectionPhase.FAILED) "Not connected" else "Disconnected", style = MaterialTheme.typography.titleMedium, color = Palette.Danger)
                    Text(st.message ?: "Tap Connect to reach the TV.", color = Palette.Text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val r = st.receiver
                        if (r != null && st.failure != FailureKind.REJECTED && st.failure != FailureKind.PIN_MISMATCH) {
                            Button(onClick = { rt.connect(r) }) { Text("Try again") }
                        }
                        if (st.failure == FailureKind.REJECTED || st.failure == FailureKind.PIN_MISMATCH) {
                            Button(onClick = onPairAgain) { Text("Pair again") }
                        }
                        if (r != null && (st.failure == FailureKind.REJECTED || st.failure == FailureKind.PIN_MISMATCH)) {
                            OutlinedButton(onClick = { rt.forget(r); onClose() }) { Text("Forget TV") }
                        }
                    }
                }
            }

            if (st.phase == ConnectionPhase.CONNECTED) {
                // --- now playing -----------------------------------------------------------------------
                Panel {
                    val active = snap.status != PlaybackStatus.IDLE
                    Text(if (active) (snap.title ?: snap.mediaUri.orEmpty()) else "Nothing playing", style = MaterialTheme.typography.titleMedium, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Chip(snap.status.name.lowercase().replaceFirstChar { it.uppercase() }, statusColor(snap.status))
                    snap.error?.let { Text(it.message, color = Palette.Danger, style = MaterialTheme.typography.bodySmall) }

                    val duration = snap.durationMs
                    val pos = st.estimatedPositionMs(now)
                    var drag by remember { mutableStateOf<Float?>(null) }
                    var hold by remember { mutableStateOf<Pair<Float, Long>?>(null) } // keeps the thumb where released until the TV catches up
                    val shown = drag ?: hold?.takeIf { now < it.second }?.first ?: pos.toFloat()
                    if (active && duration != null && duration > 0) {
                        Slider(
                            value = shown.coerceIn(0f, duration.toFloat()), valueRange = 0f..duration.toFloat(),
                            onValueChange = { drag = it },
                            onValueChangeFinished = { drag?.let { v -> rt.controller.seek(v.toLong()); hold = v to (now + 1500) }; drag = null },
                            enabled = st.canControl,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatTime(shown.toLong()), color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                            Text(formatTime(duration), color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (active) {
                        Text(if (duration == null) "Live · ${formatTime(pos)}" else formatTime(pos), color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                    }
                }

                // --- transport -------------------------------------------------------------------------
                Panel {
                    val c = rt.controller
                    val playing = snap.status == PlaybackStatus.PLAYING || snap.status == PlaybackStatus.BUFFERING || snap.status == PlaybackStatus.LOADING
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { c.seek((st.estimatedPositionMs(System.currentTimeMillis()) - 10_000).coerceAtLeast(0)) }, enabled = st.canControl && snap.status != PlaybackStatus.IDLE, modifier = Modifier.weight(1f)) { Text("−10 s") }
                        Button(
                            onClick = { if (playing) c.pause() else if (snap.status == PlaybackStatus.PAUSED) c.resume() else c.play() },
                            enabled = st.canControl && snap.status != PlaybackStatus.IDLE, modifier = Modifier.weight(1.4f),
                        ) { Text(if (playing) "Pause" else "Play") }
                        OutlinedButton(onClick = { c.seek(st.estimatedPositionMs(System.currentTimeMillis()) + 10_000) }, enabled = st.canControl && snap.status != PlaybackStatus.IDLE, modifier = Modifier.weight(1f)) { Text("+10 s") }
                    }
                    OutlinedButton(onClick = { c.stop() }, enabled = st.canControl && snap.status != PlaybackStatus.IDLE, modifier = Modifier.fillMaxWidth()) { Text("Stop", color = if (st.canControl) Palette.Danger else Palette.TextDim) }

                    Text("Speed", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (sp in listOf(0.5, 1.0, 1.25, 1.5, 2.0)) {
                            FilterChip(
                                selected = kotlin.math.abs(snap.speed - sp.toFloat()) < 0.01f,
                                onClick = { c.setSpeed(sp) }, enabled = st.canControl && snap.status != PlaybackStatus.IDLE,
                                label = { Text(if (sp == 1.0) "1×" else "$sp×") },
                            )
                        }
                    }
                    var vol by remember { mutableStateOf<Float?>(null) }
                    Text("TV volume (player)", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = vol ?: snap.volume, onValueChange = { vol = it },
                        onValueChangeFinished = { vol?.let { v -> c.setVolume(v.toDouble()) }; vol = null },
                        enabled = st.canControl && snap.status != PlaybackStatus.IDLE,
                    )
                }

                // --- cast from a website ---------------------------------------------------------------
                Panel {
                    Text("Cast from a website", style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                    Text("For sites without a Cast button: open the page, play the video, tap Cast. Works with HLS (.m3u8), DASH and MP4 streams.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onBrowse, enabled = st.canControl, modifier = Modifier.fillMaxWidth()) { Text("Find a video on a website") }
                }

                // --- cast a link -----------------------------------------------------------------------
                Panel {
                    Text("Cast a link", style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                    OutlinedTextField(
                        url, { url = it }, label = { Text("Direct video / audio / stream URL") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), enabled = st.canControl,
                    )
                    OutlinedTextField(title, { title = it.take(100) }, label = { Text("Title (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = st.canControl)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { clipboard.getText()?.text?.let { Links.extractHttpUrl(it) ?: it }?.let { url = it } }, enabled = st.canControl) { Text("Paste") }
                        Button(
                            onClick = { rt.controller.load(url, title.ifBlank { null }); },
                            enabled = st.canControl && url.isNotBlank(), modifier = Modifier.weight(1f),
                        ) { Text("Cast to ${st.receiver?.name ?: "TV"}") }
                    }
                    Text("The TV downloads the stream itself, so the link must be reachable from the TV (http/https; MP4, WebM, HLS, DASH, MP3…). Files stored on this phone can't be cast.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                }

                if (!st.canControl) {
                    Panel { Text("This phone is an observer: it can see what is playing but cannot control the TV. Pair again and choose “Control playback” on the TV to change that.", color = Palette.Warn) }
                }
                st.message?.let { Text(it, color = Palette.Warn, modifier = Modifier.padding(horizontal = 4.dp)) }
                Text("If you close the app or lose the connection for good, the TV stops playback.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun statusColor(s: PlaybackStatus) = when (s) {
    PlaybackStatus.PLAYING -> Palette.Ok
    PlaybackStatus.PAUSED, PlaybackStatus.READY -> Palette.Accent
    PlaybackStatus.LOADING, PlaybackStatus.BUFFERING -> Palette.Warn
    PlaybackStatus.ERROR -> Palette.Danger
    else -> Palette.TextDim
}
