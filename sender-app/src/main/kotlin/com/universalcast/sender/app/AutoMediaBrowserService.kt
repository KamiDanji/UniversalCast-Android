package com.universalcast.sender.app

import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService
import com.universalcast.playback.PlaybackStatus
import com.universalcast.sender.ConnectionPhase
import com.universalcast.sender.MediaCast
import com.universalcast.sender.SenderState
import com.universalcast.sender.app.ui.formatTime

/**
 * Official Android Auto [MediaBrowserService] integration for the UniversalCast Sender app.
 *
 * Allows the car's Android Auto dashboard and steering-wheel controls to:
 * - Connect to any paired TV / Car Head Unit receiver (or launch the Dummy TV Simulator)
 * - Browse and jump to items in the **Up Next Queue**
 * - Browse and resume items from **Continue Watching & History**
 * - Control Play, Pause, Seek, Next, Previous and Stop from the car touchscreen and steering wheel.
 */
class AutoMediaBrowserService : MediaBrowserService() {
    private val runtime get() = (application as SenderApp).runtime
    private var session: MediaSession? = null
    private val stateListener: (SenderState) -> Unit = { s ->
        updateSessionState(s)
        runCatching {
            notifyChildrenChanged(ROOT_ID)
            notifyChildrenChanged(FOLDER_RECEIVERS)
            notifyChildrenChanged(FOLDER_QUEUE)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val ms = MediaSession(this, "UniversalCastAuto")
        ms.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                val snap = runtime.state.value.snapshot
                if (snap.status == PlaybackStatus.PAUSED) runtime.controller.resume() else runtime.controller.play()
            }

            override fun onPause() = runtime.controller.pause()
            override fun onStop() = runtime.controller.stop()
            override fun onSeekTo(pos: Long) = runtime.controller.seek(pos.coerceAtLeast(0L))

            override fun onFastForward() {
                val est = runtime.state.value.estimatedPositionMs(System.currentTimeMillis())
                runtime.controller.seek(est + 10_000L)
            }

            override fun onRewind() {
                val est = runtime.state.value.estimatedPositionMs(System.currentTimeMillis())
                runtime.controller.seek((est - 10_000L).coerceAtLeast(0L))
            }

            override fun onSkipToNext() {
                val snap = runtime.state.value.snapshot
                if (snap.queueIndex + 1 in snap.queue.indices) runtime.controller.queueNext() else onFastForward()
            }

            override fun onSkipToPrevious() {
                val snap = runtime.state.value.snapshot
                if (snap.queueIndex - 1 in snap.queue.indices) runtime.controller.queuePrevious() else onRewind()
            }

            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                val id = mediaId ?: return
                when {
                    id == "action:dummy" -> runtime.controller.startDummyMode()
                    id.startsWith("receiver:") -> {
                        val fp = id.removePrefix("receiver:")
                        val r = runtime.receivers.value.firstOrNull { it.fingerprint == fp } ?: return
                        runtime.connect(r)
                    }
                    id.startsWith("queue:") -> {
                        val itemId = id.removePrefix("queue:")
                        runtime.controller.queueJump(itemId)
                    }
                    id.startsWith("history:") -> {
                        val uri = id.removePrefix("history:")
                        val entry = runtime.history.value.firstOrNull { it.uri == uri } ?: return
                        runtime.castMedia(
                            MediaCast(
                                uri = entry.uri,
                                title = entry.title,
                                mimeType = entry.mimeType,
                                startPositionMs = if (entry.canResume) entry.lastPositionMs else 0L,
                            )
                        )
                    }
                }
            }
        })
        ms.isActive = true
        session = ms
        sessionToken = ms.sessionToken
        runtime.controller.addListener(stateListener)
        updateSessionState(runtime.state.value)
    }

    override fun onDestroy() {
        runCatching { runtime.controller.removeListener(stateListener) }
        runCatching {
            session?.isActive = false
            session?.release()
            session = null
        }
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        val items = ArrayList<MediaBrowser.MediaItem>()
        val st = runtime.state.value
        val snap = st.snapshot

        when (parentId) {
            ROOT_ID -> {
                val connSub = when {
                    st.isDummyMode -> "Connected: Dummy TV Simulator"
                    st.phase == ConnectionPhase.CONNECTED -> "Connected: ${st.receiver?.name ?: "Receiver"}"
                    st.phase == ConnectionPhase.CONNECTING || st.phase == ConnectionPhase.RECONNECTING -> "Connecting to ${st.receiver?.name ?: "Receiver"}…"
                    else -> "Not connected — tap to pick a Car Unit or TV"
                }
                items.add(browsableFolder(FOLDER_RECEIVERS, "Receivers (Car Unit / TV)", connSub))
                items.add(browsableFolder(FOLDER_QUEUE, "Up Next Queue (${snap.queue.size})", if (snap.queue.isEmpty()) "Queue is empty" else "Tap to browse queued items"))
                val hist = runtime.history.value
                items.add(browsableFolder(FOLDER_HISTORY, "Continue Watching & History (${hist.size})", if (hist.isEmpty()) "No recent streams" else "Tap to resume recent streams"))
            }
            FOLDER_RECEIVERS -> {
                for (r in runtime.receivers.value) {
                    val connectedHere = st.phase == ConnectionPhase.CONNECTED && st.receiver?.fingerprint == r.fingerprint && !st.isDummyMode
                    items.add(
                        playableItem(
                            id = "receiver:${r.fingerprint}",
                            title = r.name,
                            subtitle = if (connectedHere) "● Connected (${r.role})" else "Tap to connect (${r.role})",
                        )
                    )
                }
                items.add(
                    playableItem(
                        id = "action:dummy",
                        title = "Dummy TV Simulator (Dev Mode)",
                        subtitle = if (st.isDummyMode) "● Active" else "Tap to simulate a connected receiver",
                    )
                )
            }
            FOLDER_QUEUE -> {
                snap.queue.forEachIndexed { idx, q ->
                    val current = idx == snap.queueIndex
                    items.add(
                        playableItem(
                            id = "queue:${q.id}",
                            title = q.title ?: q.uri,
                            subtitle = if (current) "▶ Now Playing" else "Queue #${idx + 1}",
                        )
                    )
                }
            }
            FOLDER_HISTORY -> {
                for (h in runtime.history.value) {
                    val sub = if (h.canResume) "Resume at ${formatTime(h.lastPositionMs)}" else "Cast from start"
                    items.add(
                        playableItem(
                            id = "history:${h.uri}",
                            title = h.title ?: h.uri,
                            subtitle = sub,
                        )
                    )
                }
            }
        }
        result.sendResult(items)
    }

    private fun browsableFolder(id: String, title: String, subtitle: String): MediaBrowser.MediaItem {
        val desc = MediaDescription.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        return MediaBrowser.MediaItem(desc, MediaBrowser.MediaItem.FLAG_BROWSABLE)
    }

    private fun playableItem(id: String, title: String, subtitle: String): MediaBrowser.MediaItem {
        val desc = MediaDescription.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        return MediaBrowser.MediaItem(desc, MediaBrowser.MediaItem.FLAG_PLAYABLE)
    }

    private fun updateSessionState(s: SenderState) {
        val ms = session ?: return
        val snap = s.snapshot
        val tv = s.receiver?.name ?: "Receiver"
        val activeMedia = s.phase == ConnectionPhase.CONNECTED && snap.status != PlaybackStatus.IDLE

        val pbState = when {
            !activeMedia -> PlaybackState.STATE_PAUSED
            snap.status == PlaybackStatus.PLAYING -> PlaybackState.STATE_PLAYING
            snap.status == PlaybackStatus.PAUSED || snap.status == PlaybackStatus.READY -> PlaybackState.STATE_PAUSED
            snap.status == PlaybackStatus.LOADING || snap.status == PlaybackStatus.BUFFERING -> PlaybackState.STATE_BUFFERING
            snap.status == PlaybackStatus.ERROR -> PlaybackState.STATE_ERROR
            else -> PlaybackState.STATE_STOPPED
        }
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID

        ms.setPlaybackState(
            PlaybackState.Builder()
                .setState(pbState, s.estimatedPositionMs(System.currentTimeMillis()), if (snap.isPlaying) snap.speed else 0f)
                .setActions(actions)
                .build()
        )
        ms.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, if (activeMedia) (snap.title ?: snap.mediaUri ?: "UniversalCast") else "Ready to Cast")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, if (s.phase == ConnectionPhase.CONNECTED) "Connected to $tv" else "Select a receiver in the menu")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, snap.durationMs ?: -1L)
                .build()
        )
    }

    companion object {
        const val ROOT_ID = "root"
        const val FOLDER_RECEIVERS = "folder:receivers"
        const val FOLDER_QUEUE = "folder:queue"
        const val FOLDER_HISTORY = "folder:history"
    }
}
