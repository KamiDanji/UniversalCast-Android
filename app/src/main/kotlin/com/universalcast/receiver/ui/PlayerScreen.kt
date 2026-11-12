package com.universalcast.receiver.ui

import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.universalcast.playback.PlaybackSnapshot
import com.universalcast.playback.PlaybackStatus
import com.universalcast.receiver.platform.Media3PlaybackEngine

/**
 * Physical remote media keys (MEDIA_PLAY_PAUSE / MEDIA_PLAY / MEDIA_PAUSE) toggle playback silently.
 * ASSUMPTION: the phone is the only real remote, but a TV owner reaching for the remote's play/pause key expects it to
 * work. Set to false to make the TV ignore those keys completely (Back still stops playback).
 */
const val ALLOW_REMOTE_MEDIA_KEYS = true

/**
 * The TV side of playback: video, subtitles, a small spinner while loading/buffering, and an error message when
 * playback fails. Nothing else — no title, seek bar, buttons or status text — and nothing focusable; everything is
 * controlled from the phone. Back (handled by the activity) stops playback and returns Home.
 */
@Composable
fun PlayerScreen(engine: Media3PlaybackEngine, snapshot: PlaybackSnapshot) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true // the screen must not sleep during playback
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false          // no on-screen transport UI at all
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER) // our own spinner below
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    player = engine.player
                    // PlayerView's SubtitleView is part of the view itself (above the video surface); with the
                    // controller disabled nothing covers it, and our Compose overlays stay centred and small.
                    subtitleView?.apply { setUserDefaultStyle(); setUserDefaultTextSize() }
                }
            },
            update = { it.player = engine.player },
            modifier = Modifier.fillMaxSize(),
        )

        val busy = snapshot.error == null &&
            (snapshot.status == PlaybackStatus.LOADING || snapshot.status == PlaybackStatus.BUFFERING)
        if (busy) {
            CircularProgressIndicator(
                color = Palette.Text.copy(alpha = 0.85f),
                strokeWidth = 4.dp,
                modifier = Modifier.align(Alignment.Center).size(56.dp),
            )
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
