package com.universalcast.receiver

import com.universalcast.network.NetworkAddresses
import android.content.Context
import android.net.nsd.NsdManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.universalcast.config.NetworkConfig
import com.universalcast.config.OperatingMode
import com.universalcast.core.DiagnosticsLog
import com.universalcast.core.ReceiverCore
import com.universalcast.identity.PairedSender
import com.universalcast.identity.SenderRegistry
import com.universalcast.network.ConnectedSender
import com.universalcast.pairing.PairingListener
import com.universalcast.pairing.PairingOutcome
import com.universalcast.pairing.PairingView
import com.universalcast.playback.PlaybackSnapshot
import com.universalcast.receiver.platform.KeystoreIdentityProvider
import com.universalcast.receiver.platform.Media3PlaybackEngine
import com.universalcast.receiver.platform.NsdAdvertiser
import com.universalcast.receiver.policy.CastStartDetector
import com.universalcast.security.ReceiverIdentity
import com.universalcast.storage.EncryptedFileSenderRegistry
import com.universalcast.storage.LoadOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Process-wide holder of the receiver and the observable state the UI renders. */
class ReceiverRuntime(private val app: Context) {
    val log = DiagnosticsLog()
    private val prefs = app.getSharedPreferences(ReceiverSettings.FILE, Context.MODE_PRIVATE)
    val settings = ReceiverSettings(app)
    private val main = Handler(Looper.getMainLooper())

    private val provider = KeystoreIdentityProvider()
    val identityResult = provider.loadOrCreate()
    val identity: ReceiverIdentity = identityResult.identity
    val registry: SenderRegistry
    val registryOutcome: LoadOutcome
    val engine = Media3PlaybackEngine(app)

    private val advertiser = NsdAdvertiser(app.getSystemService(Context.NSD_SERVICE) as NsdManager, log)

    val config = NetworkConfig(
        mode = if (BuildConfig.OPERATING_MODE == "production") OperatingMode.PRODUCTION else OperatingMode.DEVELOPMENT,
        port = BuildConfig.LISTEN_PORT,
    )

    val core: ReceiverCore

    private val _pairing = MutableStateFlow<PairingView?>(null)
    val pairing: StateFlow<PairingView?> = _pairing.asStateFlow()
    private val _connected = MutableStateFlow<List<ConnectedSender>>(emptyList())
    val connected: StateFlow<List<ConnectedSender>> = _connected.asStateFlow()
    private val _senders = MutableStateFlow<List<PairedSender>>(emptyList())
    val senders: StateFlow<List<PairedSender>> = _senders.asStateFlow()
    private val _playback = MutableStateFlow(PlaybackSnapshot())
    val playback: StateFlow<PlaybackSnapshot> = _playback.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _startError = MutableStateFlow<String?>(null)
    val startError: StateFlow<String?> = _startError.asStateFlow()
    private val _name = MutableStateFlow(readName())
    val receiverName: StateFlow<String> = _name.asStateFlow()

    private val castDetector = CastStartDetector()

    /**
     * Invoked (on the playback-events thread) when a phone starts casting: IDLE → playing/loading with a media URI,
     * or a new URI over a running playback. The foreground service uses it to bring the player to the front.
     */
    @Volatile
    var onCastStarted: (() -> Unit)? = null

    init {
        if (identityResult.regenerated) log.warn("Receiver identity key was (re)generated; previous pairings are void")
        registry = EncryptedFileSenderRegistry(File(app.filesDir, "senders.bin"), provider.storeAead(), identity.fingerprint)
        registryOutcome = (registry as EncryptedFileSenderRegistry).lastLoadOutcome
        when (registryOutcome) {
            LoadOutcome.CORRUPT_RESET -> log.error("Paired-device store was unreadable and was reset")
            LoadOutcome.UNSUPPORTED_VERSION_RESET -> log.error("Paired-device store is from a newer app version; it was set aside")
            LoadOutcome.IDENTITY_CHANGED_RESET -> log.warn("Paired devices dropped because the receiver identity changed")
            LoadOutcome.MIGRATED -> log.info("Paired-device store migrated")
            else -> {}
        }
        val extraAddresses = BuildConfig.EXTRA_PAIRING_ADDRESSES.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        core = ReceiverCore(
            identity, registry, engine, config, advertiser,
            addressProvider = { (extraAddresses + NetworkAddresses.local()).distinct() },
            receiverName = { _name.value }, log = log,
        )
        core.pairing.addListener(object : PairingListener {
            override fun onPairingChanged(view: PairingView?) { _pairing.value = view }
            override fun onPairingFinished(connectionId: String, outcome: PairingOutcome) {
                refreshSenders()
                log.info("Pairing finished: ${outcome.status}")
            }
        })
        core.addConnectionsListener { list -> _connected.value = list; refreshSenders() }
        engine.addListener { snap ->
            _playback.value = snap
            if (castDetector.onSnapshot(snap)) runCatching { onCastStarted?.invoke() }.onFailure { log.warn("Auto-open failed: ${it.javaClass.simpleName}") }
        }
        refreshSenders()
        scheduleTick()
    }

    private fun scheduleTick() {
        main.postDelayed({
            engine.tick()
            _pairing.value = core.pairing.view() // keeps the countdown fresh
            scheduleTick()
        }, 500)
    }

    fun refreshSenders() { _senders.value = registry.all().sortedByDescending { it.pairedAtMs } }

    /** Called by the foreground service. */
    @Synchronized
    fun startReceiver() {
        try {
            core.start()
            _running.value = true
            _startError.value = null
        } catch (e: Exception) {
            _running.value = false
            _startError.value = e.message ?: e.javaClass.simpleName
            log.error("Receiver failed to start", e)
        }
    }

    @Synchronized
    fun stopReceiver() {
        core.stop()
        _running.value = false
    }

    fun revoke(senderId: String) {
        core.revokeSender(senderId)
        refreshSenders()
    }

    fun setName(name: String) {
        val clean = name.filter { !it.isISOControl() }.trim().take(40)
        if (clean.isEmpty()) return
        prefs.edit().putString("name", clean).apply()
        _name.value = clean
        if (core.isRunning) core.advertise() // advertise() is a no-op for identical ads; a new name restarts it
    }

    private fun readName(): String =
        prefs.getString("name", null) ?: "${Build.MODEL ?: "TV"} (UniversalCast)".take(40)
}
