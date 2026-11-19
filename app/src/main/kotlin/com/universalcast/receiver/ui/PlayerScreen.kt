package com.universalcast.receiver.ui

import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.universalcast.playback.PlaybackSnapshot
import com.universalcast.playback.PlaybackStatus
import com.universalcast.playback.SubtitleStyle
import com.universalcast.playback.VideoScaleMode
import com.universalcast.receiver.platform.Media3PlaybackEngine
import kotlinx.coroutines.delay

/**
 * Physical remote media keys (MEDIA_PLAY_PAUSE / MEDIA_PLAY / MEDIA_PAUSE) toggle playback silently.
 * ASSUMPTION: the phone is the only real remote, but a TV owner reaching for the remote's play/pause key expects it to
 * work. Set to false to make the TV ignore those keys completely (Back still stops playback).
 */
const val ALLOW_REMOTE_MEDIA_KEYS = true

/**
 * The TV side of playback: video, customizable subtitles, a small spinner while loading/buffering, an optional
 * 2-second floating OSD status pill in the top-right corner, and an error message when playback fails.
 * Nothing focusable; everything is controlled from the phone. Back (handled by the activity) stops playback.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    engine: Media3PlaybackEngine,
    snapshot: PlaybackSnapshot,
    showOsd: Boolean = true,
    osdToast: Pair<String, Long>? = null,
) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true // the screen must not sleep during playback
        onDispose { view.keepScreenOn = false }
    }

    var visibleToast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(osdToast, showOsd) {
        if (!showOsd || osdToast == null) {
            visibleToast = null
            return@LaunchedEffect
        }
        val age = System.currentTimeMillis() - osdToast.second
        if (age < 2_200L) {
            visibleToast = osdToast.first
            delay((2_200L - age).coerceAtLeast(300L))
            visibleToast = null
        }
    }

    val subStyle = snapshot.subtitleStyle
    val resizeMode = when (snapshot.videoScale) {
        VideoScaleMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoScaleMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoScaleMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
    val photo by engine.currentPhoto.collectAsState()
    var touchOverlayAtMs by remember { mutableLongStateOf(0L) }
    var showTouchControls by remember { mutableStateOf(false) }

    LaunchedEffect(touchOverlayAtMs) {
        if (touchOverlayAtMs > 0L) {
            showTouchControls = true
            delay(3_800L)
            showTouchControls = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                touchOverlayAtMs = if (showTouchControls) 0L.also { showTouchControls = false } else System.currentTimeMillis()
            }
    ) {
        if (snapshot.mediaKind == "IMAGE" && photo != null) {
            val scale = when (snapshot.videoScale) {
                VideoScaleMode.ZOOM -> ContentScale.Crop
                VideoScaleMode.FILL -> ContentScale.FillBounds
                VideoScaleMode.FIT -> ContentScale.Fit
            }
            Image(
                bitmap = photo!!.asImageBitmap(),
                contentDescription = snapshot.title,
                contentScale = scale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false          // no on-screen transport UI at all
                        isFocusable = false
                        isFocusableInTouchMode = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER) // our own spinner below
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        player = engine.player
                        this.resizeMode = resizeMode
                        subtitleView?.let { applySubtitleStyle(it, subStyle) }
                    }
                },
                update = { pv ->
                    pv.player = engine.player
                    pv.resizeMode = resizeMode
                    pv.subtitleView?.let { applySubtitleStyle(it, subStyle) }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (snapshot.mediaKind == "AUDIO" && snapshot.error == null) {
            AudioVisualizerOverlay(snapshot = snapshot, modifier = Modifier.align(Alignment.Center))
        }

        val busy = snapshot.error == null &&
            (snapshot.status == PlaybackStatus.LOADING || snapshot.status == PlaybackStatus.BUFFERING)
        if (busy) {
            CircularProgressIndicator(
                color = Palette.Text.copy(alpha = 0.85f),
                strokeWidth = 4.dp,
                modifier = Modifier.align(Alignment.Center).size(56.dp),
            )
        }

        AnimatedVisibility(
            visible = showOsd && visibleToast != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(32.dp),
        ) {
            Box(
                Modifier
                    .background(Color(0xCC0D131F), RoundedCornerShape(999.dp))
                    .border(1.dp, Palette.Accent.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(
                    text = visibleToast.orEmpty(),
                    color = Palette.Text,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        AnimatedVisibility(
            visible = showTouchControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
        ) {
            Row(
                Modifier
                    .background(Color(0xD90E1523), RoundedCornerShape(22.dp))
                    .border(1.dp, Palette.Outline, RoundedCornerShape(22.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { engine.stop() }) {
                    Text("← Stop", color = Palette.Danger)
                }
                if (snapshot.queue.size > 1) {
                    FilledTonalButton(
                        onClick = { engine.skipPrevious(); touchOverlayAtMs = System.currentTimeMillis() },
                        enabled = snapshot.queueIndex > 0,
                    ) { Text("⏮") }
                }
                FilledTonalButton(onClick = { engine.seekTo((snapshot.positionMs - 10_000L).coerceAtLeast(0L)); touchOverlayAtMs = System.currentTimeMillis() }) {
                    Text("−10 s")
                }
                Button(onClick = {
                    if (snapshot.isPlaying) engine.pause() else engine.play()
                    touchOverlayAtMs = System.currentTimeMillis()
                }) {
                    Text(if (snapshot.isPlaying) "Pause" else "Play")
                }
                FilledTonalButton(onClick = { engine.seekTo(snapshot.positionMs + 10_000L); touchOverlayAtMs = System.currentTimeMillis() }) {
                    Text("+10 s")
                }
                if (snapshot.queue.size > 1) {
                    FilledTonalButton(
                        onClick = { engine.skipNext(); touchOverlayAtMs = System.currentTimeMillis() },
                        enabled = snapshot.queueIndex + 1 < snapshot.queue.size,
                    ) { Text("⏭") }
                }
            }
        }

        snapshot.error?.let { e ->
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 760.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Playback problem", color = Palette.Danger, style = MaterialTheme.typography.titleLarge)
                Text(e.message, color = Palette.Text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                Text("Try again from your phone, or press Back to leave.", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AudioVisualizerOverlay(snapshot: PlaybackSnapshot, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "eq")
    val bars = listOf(520, 380, 640, 440, 580, 350, 490).mapIndexed { i, dur ->
        inf.animateFloat(
            initialValue = 0.2f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(tween(dur, delayMillis = i * 70, easing = LinearEasing), RepeatMode.Reverse),
            label = "bar$i",
        )
    }
    Column(
        modifier = modifier
            .widthIn(min = 420.dp, max = 640.dp)
            .background(Color(0xD9121A28), RoundedCornerShape(28.dp))
            .border(1.dp, Palette.Outline, RoundedCornerShape(28.dp))
            .padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier.height(64.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEach { anim ->
                val frac = if (snapshot.isPlaying) anim.value else 0.18f
                Box(
                    Modifier
                        .width(12.dp)
                        .fillMaxHeight(frac)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Palette.Accent)
                )
            }
        }
        Text(
            text = snapshot.title ?: snapshot.mediaUri ?: "Audio Stream",
            color = Palette.Text,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        val dur = snapshot.durationMs
        if (dur != null && dur > 0) {
            val progress = (snapshot.positionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(snapshot.positionMs), color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
                Text(formatTime(dur), color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Text(formatTime(snapshot.positionMs), color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
        }
        if (snapshot.queue.size > 1 && snapshot.queueIndex + 1 in snapshot.queue.indices) {
            val nextTitle = snapshot.queue[snapshot.queueIndex + 1].title ?: snapshot.queue[snapshot.queueIndex + 1].uri
            Text("Up next: $nextTitle", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
private fun applySubtitleStyle(sv: SubtitleView, style: SubtitleStyle) {
    val bgAlpha = (style.backgroundOpacity.coerceIn(0f, 1f) * 255).toInt()
    val bgColor = AndroidColor.argb(bgAlpha, 0, 0, 0)
    val captionStyle = CaptionStyleCompat(
        AndroidColor.WHITE,
        bgColor,
        AndroidColor.TRANSPARENT,
        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
        AndroidColor.BLACK,
        Typeface.DEFAULT_BOLD,
    )
    sv.setStyle(captionStyle)
    sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.fontScale.coerceIn(0.5f, 2.5f))
    sv.setBottomPaddingFraction(style.bottomPaddingFraction.coerceIn(0f, 0.35f))
}

/**
 * Remote-key policy while the player is shown. Returns true if the key was consumed.
 * D-pad and every other key are swallowed (they would otherwise move focus to nothing or leak to the home UI);
 * Back is NOT handled here, the activity's BackHandler stops playback.
 */
fun handlePlayerKey(event: KeyEvent, snapshot: PlaybackSnapshot, engine: Media3PlaybackEngine): Boolean {
    val code = event.keyCode
    if (code == KeyEvent.KEYCODE_BACK) return false
    // Volume / mute keys belong to the TV (system volume), never to us.
    if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) return false
    // Power, home and other system keys are never delivered to apps anyway; returning false keeps it that way.
    if (code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_POWER) return false
    val media = code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE
    if (media) {
        if (ALLOW_REMOTE_MEDIA_KEYS && event.action == KeyEvent.ACTION_UP) {
            when (PlayerKeyPolicy.action(code, snapshot.isPlaying)) {
                PlayerKeyPolicy.Action.PLAY -> runCatching { engine.play() }
                PlayerKeyPolicy.Action.PAUSE -> runCatching { engine.pause() }
                PlayerKeyPolicy.Action.NONE -> {}
            }
        }
        return true
    }
    if (ALLOW_REMOTE_MEDIA_KEYS && event.action == KeyEvent.ACTION_UP) {
        when (code) {
            KeyEvent.KEYCODE_MEDIA_STOP -> { runCatching { engine.stop() }; return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { runCatching { if (!engine.skipNext()) engine.seekTo(snapshot.positionMs + 10_000L) }; return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { runCatching { if (!engine.skipPrevious()) engine.seekTo((snapshot.positionMs - 10_000L).coerceAtLeast(0L)) }; return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { runCatching { engine.seekTo(snapshot.positionMs + 10_000L) }; return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { runCatching { engine.seekTo((snapshot.positionMs - 10_000L).coerceAtLeast(0L)) }; return true }
        }
    }
    return true // D-pad, OK/center, numbers, …: do nothing
}

/** Pure mapping of a media key to an action, testable on the JVM. */
object PlayerKeyPolicy {
    enum class Action { PLAY, PAUSE, NONE }

    fun action(keyCode: Int, isPlaying: Boolean): Action = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (isPlaying) Action.PAUSE else Action.PLAY
        KeyEvent.KEYCODE_MEDIA_PLAY -> if (isPlaying) Action.NONE else Action.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> if (isPlaying) Action.PAUSE else Action.NONE
        else -> Action.NONE
    }
}
