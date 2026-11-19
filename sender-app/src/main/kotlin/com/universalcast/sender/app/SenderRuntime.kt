package com.universalcast.sender.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import com.universalcast.sender.BookmarkEntry
import com.universalcast.sender.BookmarkStore
import com.universalcast.sender.CastHistoryStore
import com.universalcast.sender.ConnectionPhase
import com.universalcast.sender.EncryptedFileReceiverStore
import com.universalcast.sender.HistoryEntry
import com.universalcast.sender.MediaCast
import com.universalcast.sender.MediaProxyServer
import com.universalcast.sender.PairedReceiver
import com.universalcast.sender.PairingSession
import com.universalcast.sender.ReceiverStore
import com.universalcast.sender.SenderController
import com.universalcast.sender.SenderState
import com.universalcast.storage.LoadOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Process-wide owner of the phone's identity, paired TVs, connection, proxy, history, bookmarks and discovery. Survives activity recreation. */
class SenderRuntime(private val app: Context) {
    private val prefs = app.getSharedPreferences("ucp_sender", Context.MODE_PRIVATE)

    val identity = KeystoreSenderIdentity.loadOrCreate()
    val store: ReceiverStore
    val storeOutcome: LoadOutcome
    val controller = SenderController(identity)
    val discovery = NsdDiscovery(app)
    val filters = com.universalcast.sender.app.web.FilterLists(app)
    val proxy = MediaProxyServer()
    val historyStore = CastHistoryStore(File(app.filesDir, "cast_history.json"))
    val bookmarkStore = BookmarkStore(File(app.filesDir, "bookmarks.json"))

    @Volatile private var serviceRunning = false
    private val _state = MutableStateFlow(controller.state)
    val state: StateFlow<SenderState> = _state.asStateFlow()
    private val _receivers = MutableStateFlow<List<PairedReceiver>>(emptyList())
    val receivers: StateFlow<List<PairedReceiver>> = _receivers.asStateFlow()
    private val _history = MutableStateFlow(historyStore.all())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()
    private val _bookmarks = MutableStateFlow(bookmarkStore.all())
    val bookmarks: StateFlow<List<BookmarkEntry>> = _bookmarks.asStateFlow()
    private val _lastDirectCast = MutableStateFlow<MediaCast?>(null)
    /** Last non-proxied cast, used for 1-tap "Retry via Phone Proxy" if the TV receives HTTP 401/403. */
    val lastDirectCast: StateFlow<MediaCast?> = _lastDirectCast.asStateFlow()
    private val _name = MutableStateFlow(prefs.getString("name", null) ?: defaultName())
    val deviceName: StateFlow<String> = _name.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    /** One-off message for the home screen (e.g. the store was reset). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init {
        val s = EncryptedFileReceiverStore(File(app.filesDir, "tvs.bin"), KeystoreStoreKey.aead())
        store = s
        storeOutcome = s.lastLoadOutcome
        // A regenerated phone key (e.g. Keystore wiped) means no TV knows this phone any more: drop the stale list.
        val known = prefs.getString("sender_id", null)
        if (known != null && known != identity.senderId) {
            s.all().forEach { s.remove(it.fingerprint) }
            _notice.value = "This phone's security key was reset, so you need to pair your TVs again."
        }
        prefs.edit().putString("sender_id", identity.senderId).apply()
        if (storeOutcome == LoadOutcome.CORRUPT_RESET || storeOutcome == LoadOutcome.UNSUPPORTED_VERSION_RESET) {
            _notice.value = "The list of paired TVs could not be read and was reset. Pair your TVs again."
        }
        refresh()
        controller.addListener {
            _state.value = it
            if (historyStore.onSnapshot(it.snapshot)) {
                _history.value = historyStore.all()
            }
            // Keep the connection alive in the background for as long as a TV connection is wanted.
            val active = it.phase == ConnectionPhase.CONNECTED || it.phase == ConnectionPhase.CONNECTING || it.phase == ConnectionPhase.RECONNECTING
            if (active && !serviceRunning) { serviceRunning = true; ConnectionService.start(app) }
            else if (!active) serviceRunning = false
        }
        controller.onAuthenticated = { r ->
            // Authenticated over the pinned key: addresses/port that worked are safe to remember.
            runCatching { store.save(r.copy(lastConnectedAtMs = System.currentTimeMillis())) }
            refresh()
        }
    }

    private fun refresh() { _receivers.value = store.all().sortedByDescending { it.lastConnectedAtMs ?: it.pairedAtMs } }

    fun dismissNotice() { _notice.value = null }

    fun setDeviceName(name: String) {
        val clean = name.filter { !it.isISOControl() }.trim().take(40)
        if (clean.isEmpty()) return
        prefs.edit().putString("name", clean).apply()
        _name.value = clean
    }

    private fun defaultName() = (Build.MODEL ?: "Android phone").take(40)

    fun newPairingSession(qr: String): PairingSession {
        val tempSession = PairingSession(identity, deviceName.value, qr)
        val discovered = discovery.tvs.value.firstOrNull { it.deviceId != null && it.deviceId == tempSession.bootstrap.deviceId }
        return if (discovered != null && discovered.hosts.isNotEmpty()) {
            PairingSession(identity, deviceName.value, qr, preferredHosts = discovered.hosts)
        } else {
            tempSession
        }
    }

    fun onPaired(r: PairedReceiver) {
        store.save(r)
        refresh()
    }

    /** Connects, preferring addresses found via mDNS (same TV = same device id). */
    fun connect(r: PairedReceiver) {
        val d = discovery.tvs.value.firstOrNull { it.deviceId != null && it.deviceId == r.deviceId }
        controller.connect(if (d != null) r.copy(port = d.port) else r, d?.hosts ?: emptyList())
    }

    fun forget(r: PairedReceiver) {
        if (state.value.receiver?.fingerprint == r.fingerprint) controller.disconnect()
        store.remove(r.fingerprint)
        refresh()
    }

    private fun currentTvHost(): String? {
        val r = state.value.receiver ?: return null
        val disc = discovery.tvs.value.firstOrNull { it.deviceId != null && it.deviceId == r.deviceId }
        return disc?.hosts?.firstOrNull() ?: r.addresses.firstOrNull()
    }

    /**
     * Casts or enqueues [cast], optionally routing it through the phone's [MediaProxyServer] (for HTTP 403 / IP-bound
     * streams) and recording it in [CastHistoryStore] for 1-tap resume.
     */
    fun castMedia(
        cast: MediaCast,
        pageUrl: String? = null,
        useProxy: Boolean = false,
        enqueue: Boolean = false,
        playNext: Boolean = false,
    ) {
        historyStore.recordCast(cast.uri, cast.title, cast.mimeType, pageUrl, cast.startPositionMs)
        _history.value = historyStore.all()
        if (!useProxy) {
            _lastDirectCast.value = cast
        }
        val finalCast = if (useProxy) {
            proxy.registerRemote(
                cast = cast,
                targetTvHost = currentTvHost(),
                cookieProvider = { url -> runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull() },
            )
        } else {
            cast
        }
        if (enqueue) {
            controller.enqueue(finalCast, playNext = playNext)
        } else {
            controller.load(finalCast)
        }
    }

    /** Streams a local video or audio file picked on the phone directly to the connected TV. */
    fun castLocalUri(uri: Uri, enqueue: Boolean = false) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val source = ContentUriMediaSource(app, uri)
        val cast = proxy.registerLocal(source, targetTvHost = currentTvHost())
        if (enqueue) {
            controller.enqueue(cast)
        } else {
            controller.load(cast)
        }
    }

    fun removeHistory(uri: String) {
        historyStore.remove(uri)
        _history.value = historyStore.all()
    }

    fun clearHistory() {
        historyStore.clear()
        _history.value = emptyList()
    }

    fun toggleBookmark(url: String, title: String?): Boolean {
        val added = bookmarkStore.toggle(url, title)
        _bookmarks.value = bookmarkStore.all()
        return added
    }

    fun removeBookmark(url: String) {
        bookmarkStore.remove(url)
        _bookmarks.value = bookmarkStore.all()
    }
}
