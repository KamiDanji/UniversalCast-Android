package com.universalcast.receiver.policy

import com.universalcast.playback.PlaybackSnapshot
import com.universalcast.playback.PlaybackStatus
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure detector that compares consecutive [PlaybackSnapshot]s and emits a short 2-second OSD pill message
 * when the phone user adjusts volume, playback speed, tracks, subtitle offset, pause/resume, or seeks.
 */
class PlayerOsdTracker {
    private var prev: PlaybackSnapshot? = null

    fun onSnapshot(next: PlaybackSnapshot): String? {
        val p = prev
        prev = next
        if (next.status == PlaybackStatus.IDLE) return null
        if (p == null || p.status == PlaybackStatus.IDLE) {
            return next.title?.takeIf { it.isNotBlank() }?.let { "▶ $it" }
        }
        if (next.mediaUri != p.mediaUri && next.mediaUri != null) {
            return next.title?.takeIf { it.isNotBlank() }?.let { "▶ $it" }
        }
        if (abs(next.volume - p.volume) >= 0.01f) {
            val pct = (next.volume * 100f).roundToInt().coerceIn(0, 100)
            return if (pct == 0) "Muted" else "Volume $pct%"
        }
        if (abs(next.speed - p.speed) >= 0.01f) {
            val label = if (abs(next.speed - 1.0f) < 0.01f) "1×" else "${next.speed}×"
            return "Speed $label"
        }
        val prevAudio = p.audioTracks.firstOrNull { it.selected }?.id
        val nextAudio = next.audioTracks.firstOrNull { it.selected }
        if (prevAudio != null && nextAudio != null && prevAudio != nextAudio.id) {
            return "Audio: ${nextAudio.label}"
        }
        if (p.subtitlesEnabled != next.subtitlesEnabled && !next.subtitlesEnabled) {
            return "Subtitles: Off"
        }
        val prevSub = if (p.subtitlesEnabled) p.subtitleTracks.firstOrNull { it.selected }?.id else "off"
        val nextSub = if (next.subtitlesEnabled) next.subtitleTracks.firstOrNull { it.selected } else null
        if (nextSub != null && prevSub != null && prevSub != nextSub.id) {
            return "Subtitles: ${nextSub.label}"
        }
        if (next.subtitleOffsetMs != p.subtitleOffsetMs) {
            val sec = next.subtitleOffsetMs / 1000.0
            val sign = if (sec > 0) "+" else ""
            return "Subtitle delay: %s%.1fs".format(java.util.Locale.US, sign, sec)
        }
        if (next.videoScale != p.videoScale) {
            val label = when (next.videoScale) {
                com.universalcast.playback.VideoScaleMode.FIT -> "Fit"
                com.universalcast.playback.VideoScaleMode.ZOOM -> "Zoom / Crop"
                com.universalcast.playback.VideoScaleMode.FILL -> "Stretch 16:9"
            }
            return "Aspect ratio: $label"
        }
        if ((p.sleepTimerRemainingMs == null) != (next.sleepTimerRemainingMs == null)) {
            val rem = next.sleepTimerRemainingMs
            return if (rem == null) "Sleep timer: Off" else "Sleep timer: ${(rem / 60_000L).coerceAtLeast(1L)} min"
        }
        if (p.status == PlaybackStatus.PLAYING && next.status == PlaybackStatus.PAUSED) {
            return "Paused"
        }
        if (p.status == PlaybackStatus.PAUSED && next.status == PlaybackStatus.PLAYING) {
            return "Playing"
        }
        // A position jump larger than normal 5s periodic tick (e.g. ±10s or slider scrub)
        val delta = abs(next.positionMs - p.positionMs)
        if (delta >= 8_500L && (p.status == PlaybackStatus.PLAYING || p.status == PlaybackStatus.PAUSED)) {
            val posStr = formatClock(next.positionMs)
            val durStr = next.durationMs?.takeIf { it > 0 }?.let { " / ${formatClock(it)}" }.orEmpty()
            return "$posStr$durStr"
        }
        return null
    }

    private fun formatClock(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0) / 1000)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
