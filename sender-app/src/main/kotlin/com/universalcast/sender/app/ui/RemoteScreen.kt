package com.universalcast.sender.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalcast.playback.PlaybackStatus
import com.universalcast.playback.RemoteKey
import com.universalcast.playback.VideoScaleMode
import com.universalcast.sender.ConnectionPhase
import com.universalcast.sender.FailureKind
import com.universalcast.sender.MediaCast
import com.universalcast.sender.app.Links
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class RemoteTab { MEDIA, TV_REMOTE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(vm: AppViewModel, onClose: () -> Unit, onPairAgain: () -> Unit, onBrowse: () -> Unit) {
    val rt = vm.rt
    val st by rt.state.collectAsState()
    val history by rt.history.collectAsState()
    val lastDirectCast by rt.lastDirectCast.collectAsState()
    val pending by vm.pendingUrl.collectAsState()
    val snap = st.snapshot
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(RemoteTab.MEDIA) }
    var showSubtitleStyle by remember { mutableStateOf(false) }
    var enqueueNextLocalFile by remember { mutableStateOf(false) }

    val localFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEachIndexed { idx, uri ->
                val asQueue = enqueueNextLocalFile || idx > 0
                rt.castLocalUri(uri, enqueue = asQueue)
            }
        }
    }

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
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
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
                if (st.isDummyMode) {
                    Panel {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Chip("Dev Mode · Dummy TV Simulator", Palette.Ok, live = true)
                            TextButton(onClick = { rt.controller.disconnect(); onClose() }) {
                                Text("Exit", color = Palette.Danger)
                            }
                        }
                        Text(
                            text = st.dummyLastAction ?: "📺 Simulated TV ready",
                            color = Palette.Text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                // --- top mode switcher: Media & Cast vs. Google TV-style Remote ------------------------
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Palette.Surface, RoundedCornerShape(14.dp))
                        .border(1.dp, Palette.Outline, RoundedCornerShape(14.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TabPill(
                        label = "Media & Cast",
                        selected = tab == RemoteTab.MEDIA,
                        onClick = { tab = RemoteTab.MEDIA },
                        modifier = Modifier.weight(1f),
                    )
                    TabPill(
                        label = "TV Remote & Touchpad",
                        selected = tab == RemoteTab.TV_REMOTE,
                        onClick = { tab = RemoteTab.TV_REMOTE },
                        modifier = Modifier.weight(1f),
                    )
                }

                if (tab == RemoteTab.TV_REMOTE) {
                    TvTouchpadAndDpadPanel(
                        canControl = st.canControl,
                        activeMedia = snap.status != PlaybackStatus.IDLE,
                        onKey = { rt.controller.remoteKey(it) },
                        onSendText = { text, submit -> rt.controller.remoteText(text, submit) },
                        onScrubDeltaMs = { delta ->
                            val target = (st.estimatedPositionMs(System.currentTimeMillis()) + delta).coerceAtLeast(0L)
                            rt.controller.seek(target)
                        },
                    )
                } else {
                    // --- now playing -----------------------------------------------------------------------
                    Panel {
                        val active = snap.status != PlaybackStatus.IDLE
                        Text(
                            if (active) (snap.title ?: snap.mediaUri.orEmpty()) else "Nothing playing",
                            style = MaterialTheme.typography.titleMedium,
                            color = Palette.Text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Chip(snap.status.name.lowercase().replaceFirstChar { it.uppercase() }, statusColor(snap.status))
                            if (snap.queue.size > 1 && snap.queueIndex >= 0) {
                                Chip("Queue ${snap.queueIndex + 1}/${snap.queue.size}", Palette.Accent)
                            }
                            snap.sleepTimerRemainingMs?.let { rem ->
                                Chip("Sleep ${(rem / 60_000L).coerceAtLeast(1L)}m", Palette.Warn)
                            }
                        }
                        snap.error?.let { err ->
                            Text(err.message, color = Palette.Danger, style = MaterialTheme.typography.bodySmall)
                            val retryCandidate = lastDirectCast
                            if (st.canControl && retryCandidate != null && (err.message.contains("403") || err.message.contains("401") || err.message.contains("refused"))) {
                                FilledTonalButton(
                                    onClick = { rt.castMedia(retryCandidate, useProxy = true) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Retry via Phone Proxy (fixes 403 / IP lock)")
                                }
                            }
                        }

                        val duration = snap.durationMs
                        val pos = st.estimatedPositionMs(now)
                        var drag by remember { mutableStateOf<Float?>(null) }
                        var hold by remember { mutableStateOf<Pair<Float, Long>?>(null) }
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
                        if (snap.queue.size > 1) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { c.queuePrevious() },
                                    enabled = st.canControl && snap.queueIndex > 0,
                                    modifier = Modifier.weight(1f),
                                ) { Text("⏮ Previous") }
                                OutlinedButton(
                                    onClick = { c.queueNext() },
                                    enabled = st.canControl && snap.queueIndex + 1 < snap.queue.size,
                                    modifier = Modifier.weight(1f),
                                ) { Text("Next ⏭") }
                            }
                        }
                        OutlinedButton(onClick = { c.stop() }, enabled = st.canControl && snap.status != PlaybackStatus.IDLE, modifier = Modifier.fillMaxWidth()) { Text("Stop", color = if (st.canControl) Palette.Danger else Palette.TextDim) }

                        Text("Speed", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            for (sp in listOf(0.5, 1.0, 1.25, 1.5, 2.0)) {
                                FilterChip(
                                    selected = abs(snap.speed - sp.toFloat()) < 0.01f,
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

                        Text("Aspect ratio / zoom", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            for ((label, mode) in listOf("Fit" to VideoScaleMode.FIT, "Zoom (Crop)" to VideoScaleMode.ZOOM, "Stretch 16:9" to VideoScaleMode.FILL)) {
                                FilterChip(
                                    selected = snap.videoScale == mode,
                                    onClick = { c.setVideoScale(mode) },
                                    enabled = st.canControl,
                                    label = { Text(label) },
                                )
                            }
                        }

                        Text("Sleep timer", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            val activeSleepMin = snap.sleepTimerRemainingMs?.let { (it / 60_000L).toInt() }
                            FilterChip(
                                selected = activeSleepMin == null,
                                onClick = { c.setSleepTimer(0L) },
                                enabled = st.canControl,
                                label = { Text("Off") },
                            )
                            for (mins in listOf(15, 30, 45, 60, 90)) {
                                FilterChip(
                                    selected = activeSleepMin != null && activeSleepMin in (mins - 7)..(mins + 7),
                                    onClick = { c.setSleepTimer(mins * 60_000L) },
                                    enabled = st.canControl,
                                    label = { Text("${mins}m") },
                                )
                            }
                        }
                    }

                    // --- live audio & subtitle tracks + delay + style --------------------------------------
                    if (snap.status != PlaybackStatus.IDLE || showSubtitleStyle) {
                        Panel {
                            val c = rt.controller
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Audio & Subtitles", style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                                TextButton(onClick = { showSubtitleStyle = !showSubtitleStyle }) {
                                    Text(if (showSubtitleStyle) "Hide style" else "Subtitle style")
                                }
                            }
                            if (snap.audioTracks.isNotEmpty()) {
                                Text("Audio track", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                    for (track in snap.audioTracks) {
                                        FilterChip(
                                            selected = track.selected,
                                            onClick = { c.selectTracks(audioTrackId = track.id) },
                                            enabled = st.canControl,
                                            label = { Text(track.label) },
                                        )
                                    }
                                }
                            }
                            Text("Subtitles", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                FilterChip(
                                    selected = !snap.subtitlesEnabled || snap.subtitleTracks.none { it.selected },
                                    onClick = { c.selectTracks(subtitleTrackId = "off") },
                                    enabled = st.canControl && snap.status != PlaybackStatus.IDLE,
                                    label = { Text("Off") },
                                )
                                for (track in snap.subtitleTracks) {
                                    FilterChip(
                                        selected = snap.subtitlesEnabled && track.selected,
                                        onClick = { c.selectTracks(subtitleTrackId = track.id) },
                                        enabled = st.canControl,
                                        label = { Text(track.label) },
                                    )
                                }
                            }
                            val offsetSec = snap.subtitleOffsetMs / 1000.0
                            Text("Subtitle timing (${if (offsetSec > 0) "+" else ""}${"%.1f".format(java.util.Locale.US, offsetSec)} s)", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { c.setSubtitleOffset(snap.subtitleOffsetMs - 500L) }, enabled = st.canControl && snap.status != PlaybackStatus.IDLE, modifier = Modifier.weight(1f)) { Text("−0.5 s") }
                                OutlinedButton(onClick = { c.setSubtitleOffset(0L) }, enabled = st.canControl && snap.status != PlaybackStatus.IDLE && snap.subtitleOffsetMs != 0L, modifier = Modifier.weight(1f)) { Text("Reset") }
                                OutlinedButton(onClick = { c.setSubtitleOffset(snap.subtitleOffsetMs + 500L) }, enabled = st.canControl && snap.status != PlaybackStatus.IDLE, modifier = Modifier.weight(1f)) { Text("+0.5 s") }
                            }

                            if (showSubtitleStyle) {
                                val style = snap.subtitleStyle
                                HorizontalDivider(color = Palette.Outline)
                                Text("Subtitle size", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for ((label, sc) in listOf("Small" to 0.8f, "Normal" to 1.0f, "Large" to 1.3f, "XL" to 1.65f)) {
                                        FilterChip(
                                            selected = abs(style.fontScale - sc) < 0.08f,
                                            onClick = { c.setSubtitleStyle(style.copy(fontScale = sc)) },
                                            enabled = st.canControl,
                                            label = { Text(label) },
                                        )
                                    }
                                }
                                Text("Background", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for ((label, bg) in listOf("None" to 0.0f, "Dim" to 0.55f, "Solid" to 0.9f)) {
                                        FilterChip(
                                            selected = abs(style.backgroundOpacity - bg) < 0.1f,
                                            onClick = { c.setSubtitleStyle(style.copy(backgroundOpacity = bg)) },
                                            enabled = st.canControl,
                                            label = { Text(label) },
                                        )
                                    }
                                }
                                Text("Bottom position", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for ((label, padFrac) in listOf("Low" to 0.04f, "Normal" to 0.08f, "High" to 0.14f)) {
                                        FilterChip(
                                            selected = abs(style.bottomPaddingFraction - padFrac) < 0.02f,
                                            onClick = { c.setSubtitleStyle(style.copy(bottomPaddingFraction = padFrac)) },
                                            enabled = st.canControl,
                                            label = { Text(label) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // --- up next queue ---------------------------------------------------------------------
                    if (snap.queue.isNotEmpty()) {
                        Panel {
                            val c = rt.controller
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Up Next Queue (${snap.queue.size})", style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                                if (st.canControl && snap.queue.size > 1) {
                                    TextButton(onClick = { c.queueClear() }) { Text("Clear", color = Palette.Danger) }
                                }
                            }
                            snap.queue.forEachIndexed { idx, item ->
                                val isCurrent = idx == snap.queueIndex
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isCurrent) Palette.Accent.copy(alpha = 0.16f) else Palette.SurfaceHigh)
                                        .clickable(enabled = st.canControl && !isCurrent) { c.queueJump(item.id) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        if (isCurrent) "▶" else "${idx + 1}.",
                                        color = if (isCurrent) Palette.Accent else Palette.TextDim,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        item.title ?: item.uri,
                                        color = Palette.Text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (st.canControl) {
                                        if (idx > 0) {
                                            TextButton(onClick = { c.queueReorder(item.id, idx - 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(30.dp)) { Text("↑") }
                                        }
                                        if (idx < snap.queue.lastIndex) {
                                            TextButton(onClick = { c.queueReorder(item.id, idx + 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(30.dp)) { Text("↓") }
                                        }
                                        TextButton(onClick = { c.queueRemove(item.id) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(30.dp)) {
                                            Text("✕", color = Palette.Danger)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // --- cast from a website or local phone file -------------------------------------------
                    Panel {
                        Text("Cast from a website or phone storage", style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                        Text("Find streams on any website with the built-in ad-blocking browser, or pick local videos, music or photos from your phone.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onBrowse, enabled = st.canControl, modifier = Modifier.fillMaxWidth()) { Text("Find a video on a website") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    enqueueNextLocalFile = false
                                    localFileLauncher.launch(arrayOf("video/*", "audio/*", "image/*"))
                                },
                                enabled = st.canControl,
                                modifier = Modifier.weight(1f),
                            ) { Text("Cast Videos / Music / Photos") }
                            OutlinedButton(
                                onClick = {
                                    enqueueNextLocalFile = true
                                    localFileLauncher.launch(arrayOf("video/*", "audio/*", "image/*"))
                                },
                                enabled = st.canControl,
                            ) { Text("+ Queue") }
                        }
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
                                onClick = {
                                    rt.castMedia(MediaCast(url, title = title.ifBlank { null }), enqueue = false)
                                },
                                enabled = st.canControl && url.isNotBlank(), modifier = Modifier.weight(1f),
                            ) { Text("Cast Now") }
                            OutlinedButton(
                                onClick = {
                                    rt.castMedia(MediaCast(url, title = title.ifBlank { null }), enqueue = true)
                                },
                                enabled = st.canControl && url.isNotBlank(),
                            ) { Text("+ Queue") }
                        }
                    }

                    // --- continue watching / recent history ------------------------------------------------
                    if (history.isNotEmpty()) {
                        Panel {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Continue Watching & History", style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                                TextButton(onClick = { rt.clearHistory() }) { Text("Clear", color = Palette.TextDim) }
                            }
                            history.take(6).forEach { entry ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(Palette.SurfaceHigh, RoundedCornerShape(10.dp))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            entry.title ?: entry.uri,
                                            color = Palette.Text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = { rt.removeHistory(entry.uri) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(28.dp)) {
                                            Text("✕", color = Palette.TextDim)
                                        }
                                    }
                                    if (entry.lastPositionMs > 0) {
                                        val durText = entry.durationMs?.takeIf { it > 0 }?.let { " / ${formatTime(it)}" }.orEmpty()
                                        Text("Left off at ${formatTime(entry.lastPositionMs)}$durText", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                                        if (entry.progressFraction > 0f) {
                                            LinearProgressIndicator(progress = { entry.progressFraction }, modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (entry.canResume) {
                                            Button(
                                                onClick = {
                                                    rt.castMedia(MediaCast(entry.uri, title = entry.title, mimeType = entry.mimeType, startPositionMs = entry.lastPositionMs))
                                                },
                                                enabled = st.canControl,
                                                modifier = Modifier.weight(1f),
                                            ) { Text("Resume ${formatTime(entry.lastPositionMs)}") }
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                rt.castMedia(MediaCast(entry.uri, title = entry.title, mimeType = entry.mimeType))
                                            },
                                            enabled = st.canControl,
                                            modifier = Modifier.weight(1f),
                                        ) { Text(if (entry.canResume) "Start Over" else "Cast Again") }
                                        if (!entry.pageUrl.isNullOrBlank()) {
                                            TextButton(onClick = {
                                                vm.browserUrl.value = entry.pageUrl
                                                onBrowse()
                                            }) { Text("Page") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (!st.canControl) {
                    Panel { Text("This phone is an observer: it can see what is playing but cannot control the TV. Pair again and choose “Control playback” on the TV to change that.", color = Palette.Warn) }
                }
                st.message?.let { Text(it, color = Palette.Warn, modifier = Modifier.padding(horizontal = 4.dp)) }
                Text("Detached playback is active: if your phone sleeps or temporarily loses Wi-Fi, the TV keeps playing seamlessly.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TabPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = if (selected) Palette.Accent else androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            color = if (selected) Palette.OnAccent else Palette.TextDim,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
        )
    }
}

/**
 * Google TV Remote-style control panel: switch between a full swipe gesture touchpad and a classic D-Pad,
 * plus dedicated Back, Home, Mute, Volume and media transport keys.
 */
@Composable
private fun TvTouchpadAndDpadPanel(
    canControl: Boolean,
    activeMedia: Boolean,
    onKey: (RemoteKey) -> Unit,
    onSendText: (String, Boolean) -> Unit,
    onScrubDeltaMs: (Long) -> Unit,
) {
    var useTouchpad by remember { mutableStateOf(true) }
    var showKeyboard by remember { mutableStateOf(false) }
    var textDraft by remember { mutableStateOf("") }
    var lastGestureLabel by remember { mutableStateOf("Swipe to navigate · Tap for OK · Long-press for Back") }
    val haptics = LocalHapticFeedback.current

    fun trigger(key: RemoteKey, feedback: String) {
        if (!canControl) return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        lastGestureLabel = feedback
        onKey(key)
    }

    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("TV Remote", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = useTouchpad, onClick = { useTouchpad = true }, label = { Text("Touchpad") })
                FilterChip(selected = !useTouchpad, onClick = { useTouchpad = false }, label = { Text("D-Pad") })
                FilterChip(selected = showKeyboard, onClick = { showKeyboard = !showKeyboard }, label = { Text("⌨") })
            }
        }

        if (showKeyboard) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = textDraft,
                    onValueChange = { textDraft = it.take(500) },
                    label = { Text("Type text to send to TV") },
                    singleLine = true,
                    enabled = canControl,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        if (textDraft.isNotEmpty()) {
                            onSendText(textDraft, true)
                            lastGestureLabel = "Sent: ${textDraft.take(20)}"
                            textDraft = ""
                        }
                    },
                    enabled = canControl && textDraft.isNotEmpty(),
                ) { Text("Send") }
            }
        }

        if (useTouchpad) {
            var dragX by remember { mutableFloatStateOf(0f) }
            var dragY by remember { mutableFloatStateOf(0f) }
            var firedInGesture by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Palette.SurfaceHigh)
                    .border(1.5.dp, Palette.Accent.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                    .pointerInput(canControl) {
                        if (!canControl) return@pointerInput
                        detectTapGestures(
                            onTap = {
                                trigger(RemoteKey.CENTER, "● OK / Select")
                            },
                            onLongPress = {
                                trigger(RemoteKey.BACK, "← Back (Long-press)")
                            },
                        )
                    }
                    .pointerInput(canControl) {
                        if (!canControl) return@pointerInput
                        detectDragGestures(
                            onDragStart = {
                                dragX = 0f
                                dragY = 0f
                                firedInGesture = false
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragX += dragAmount.x
                                dragY += dragAmount.y
                                val threshold = 58f
                                if (!firedInGesture && (abs(dragX) >= threshold || abs(dragY) >= threshold)) {
                                    firedInGesture = true
                                    if (abs(dragX) > abs(dragY)) {
                                        if (dragX > 0) trigger(RemoteKey.RIGHT, "▶ Right")
                                        else trigger(RemoteKey.LEFT, "◀ Left")
                                    } else {
                                        if (dragY > 0) trigger(RemoteKey.DOWN, "▼ Down")
                                        else trigger(RemoteKey.UP, "▲ Up")
                                    }
                                    dragX = 0f
                                    dragY = 0f
                                    // Allow continuous swipe steps if the user keeps dragging further
                                    firedInGesture = false
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("▲", color = Palette.TextDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("◀", color = Palette.TextDim)
                        Box(
                            Modifier
                                .size(12.dp)
                                .background(Palette.Accent, CircleShape)
                        )
                        Text("▶", color = Palette.TextDim)
                    }
                    Text("▼", color = Palette.TextDim)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = lastGestureLabel,
                        color = Palette.Text,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (activeMedia) {
                var scrubAccum by remember { mutableFloatStateOf(0f) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Palette.Surface)
                        .border(1.dp, Palette.Outline, RoundedCornerShape(14.dp))
                        .pointerInput(canControl) {
                            if (!canControl) return@pointerInput
                            detectDragGestures(
                                onDragStart = { scrubAccum = 0f },
                                onDrag = { change, amount ->
                                    change.consume()
                                    scrubAccum += amount.x
                                    if (abs(scrubAccum) >= 70f) {
                                        val step = if (scrubAccum > 0) 10_000L else -10_000L
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onScrubDeltaMs(step)
                                        lastGestureLabel = if (step > 0) "Scrub +10 s" else "Scrub −10 s"
                                        scrubAccum = 0f
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⇄  Slide horizontally here to scrub video (±10 s)", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            // Classic D-Pad layout
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = { trigger(RemoteKey.UP, "▲ Up") },
                    enabled = canControl,
                    modifier = Modifier.size(width = 96.dp, height = 56.dp),
                ) { Text("▲") }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = { trigger(RemoteKey.LEFT, "◀ Left") },
                        enabled = canControl,
                        modifier = Modifier.size(width = 88.dp, height = 64.dp),
                    ) { Text("◀") }
                    Button(
                        onClick = { trigger(RemoteKey.CENTER, "● OK") },
                        enabled = canControl,
                        shape = CircleShape,
                        modifier = Modifier.size(84.dp),
                    ) { Text("OK", style = MaterialTheme.typography.titleMedium) }
                    FilledTonalButton(
                        onClick = { trigger(RemoteKey.RIGHT, "▶ Right") },
                        enabled = canControl,
                        modifier = Modifier.size(width = 88.dp, height = 64.dp),
                    ) { Text("▶") }
                }
                FilledTonalButton(
                    onClick = { trigger(RemoteKey.DOWN, "▼ Down") },
                    enabled = canControl,
                    modifier = Modifier.size(width = 96.dp, height = 56.dp),
                ) { Text("▼") }
            }
        }

        // System & Media Remote Row
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { trigger(RemoteKey.BACK, "← Back") }, enabled = canControl, modifier = Modifier.weight(1f)) { Text("← Back") }
            OutlinedButton(onClick = { trigger(RemoteKey.HOME, "⌂ Home") }, enabled = canControl, modifier = Modifier.weight(1f)) { Text("⌂ Home") }
            OutlinedButton(onClick = { trigger(RemoteKey.PLAY_PAUSE, "⏯ Play/Pause") }, enabled = canControl, modifier = Modifier.weight(1f)) { Text("⏯ Play") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { trigger(RemoteKey.REWIND, "⏪ −10 s") }, enabled = canControl, modifier = Modifier.weight(1f)) { Text("⏪ −10s") }
            OutlinedButton(onClick = { trigger(RemoteKey.MUTE, "🔇 Mute") }, enabled = canControl, modifier = Modifier.weight(1f)) { Text("🔇 Mute") }
            OutlinedButton(onClick = { trigger(RemoteKey.FAST_FORWARD, "⏩ +10 s") }, enabled = canControl, modifier = Modifier.weight(1f)) { Text("⏩ +10s") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { trigger(RemoteKey.VOLUME_DOWN, "🔉 Volume −") }, enabled = canControl, modifier = Modifier.weight(1f)) { Text("🔉 Vol −") }
            FilledTonalButton(onClick = { trigger(RemoteKey.VOLUME_UP, "🔊 Volume +") }, enabled = canControl, modifier = Modifier.weight(1f)) { Text("🔊 Vol +") }
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
