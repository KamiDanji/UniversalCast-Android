package com.universalcast.receiver.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.universalcast.playback.PlaybackStatus
import com.universalcast.receiver.platform.Media3PlaybackEngine
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(engine: Media3PlaybackEngine, snapshot: com.universalcast.playback.PlaybackSnapshot, onStop: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true // screen must not sleep during playback
        onDispose { view.keepScreenOn = false }
    }
    var overlayVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableStateOf(System.currentTimeMillis()) }
    var livePosition by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            livePosition = engine.player.currentPosition
            if (System.currentTimeMillis() - lastInteraction > 5000 && snapshot.isPlaying) overlayVisible = false
            delay(500)
        }
    }
    fun touch() { lastInteraction = System.currentTimeMillis(); overlayVisible = true }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = false; layoutParams = ViewGroup.LayoutParams(-1, -1); player = engine.player } },
            update = { it.player = engine.player },
            modifier = Modifier.fillMaxSize(),
        )
        val busy = snapshot.status == PlaybackStatus.LOADING || snapshot.status == PlaybackStatus.BUFFERING
        if (busy) Text("Buffering…", color = Palette.Text, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.Center).background(Color(0x99000000)).padding(20.dp))
        snapshot.error?.let { e ->
            Column(Modifier.align(Alignment.Center).background(Color(0xCC000000)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Playback problem", color = Palette.Danger, style = MaterialTheme.typography.titleLarge)
                Text(e.message, color = Palette.Text, style = MaterialTheme.typography.bodyLarge)
                Text("The sender can try again. Press Back to leave.", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (overlayVisible || snapshot.status == PlaybackStatus.PAUSED || snapshot.error != null) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                    .padding(horizontal = 56.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(snapshot.title ?: snapshot.mediaUri.orEmpty(), color = Palette.Text, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                val dur = snapshot.durationMs
                val pos = livePosition
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(formatTime(pos), color = Palette.Text, style = MaterialTheme.typography.bodyLarge)
                    Box(Modifier.weight(1f).height(8.dp).background(Palette.SurfaceHigh)) {
                        val f = if (dur != null && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                        val b = if (dur != null && dur > 0) (snapshot.bufferedPositionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f
                        Box(Modifier.fillMaxHeight().fillMaxWidth(b).background(Palette.TextDim.copy(alpha = 0.4f)))
                        Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Palette.Accent))
                    }
                    Text(if (dur == null) "LIVE" else formatTime(dur), color = Palette.Text, style = MaterialTheme.typography.bodyLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvButton("−10 s", { touch(); engine.seekTo((engine.player.currentPosition - 10_000).coerceAtLeast(0)) }, primary = false)
                    TvButton(if (snapshot.isPlaying) "Pause" else "Play", { touch(); if (snapshot.isPlaying) engine.pause() else engine.play() })
                    TvButton("+10 s", { touch(); engine.seekTo(engine.player.currentPosition + 10_000) }, primary = false)
                    TvButton("Stop", { onStop() }, danger = true)
                    Spacer(Modifier.weight(1f))
                    Text("${snapshot.status.name.lowercase()} · ${"%.2f".format(snapshot.speed)}×", color = Palette.TextDim, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
