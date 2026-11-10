package com.universalcast.sender.app

import android.content.Context
import android.os.Build
import com.universalcast.sender.EncryptedFileReceiverStore
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

/** Process-wide owner of the phone's identity, paired TVs, connection and discovery. Survives activity recreation. */
class SenderRuntime(private val app: Context) {
    private val prefs = app.getSharedPreferences("ucp_sender", Context.MODE_PRIVATE)

    val identity = KeystoreSenderIdentity.loadOrCreate()
    val store: ReceiverStore
    val storeOutcome: LoadOutcome
    val controller = SenderController(identity)
    val discovery = NsdDiscovery(app)

    private val _state = MutableStateFlow(controller.state)
    val state: StateFlow<SenderState> = _state.asStateFlow()
    private val _receivers = MutableStateFlow<List<PairedReceiver>>(emptyList())
    val receivers: StateFlow<List<PairedReceiver>> = _receivers.asStateFlow()
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
        controller.addListener { _state.value = it }
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

    fun newPairingSession(qr: String) = PairingSession(identity, deviceName.value, qr)

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
}
