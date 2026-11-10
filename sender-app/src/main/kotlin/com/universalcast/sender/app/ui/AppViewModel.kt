package com.universalcast.sender.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.universalcast.sender.PairOutcome
import com.universalcast.sender.PairedReceiver
import com.universalcast.sender.PairingSession
import com.universalcast.sender.UcpClientException
import com.universalcast.sender.app.SenderApp
import com.universalcast.ucp.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PairStage {
    object Idle : PairStage
    object Connecting : PairStage
    class Compare(val sas: String, val tvName: String) : PairStage
    class Waiting(val tvName: String) : PairStage
    class Done(val receiver: PairedReceiver) : PairStage
    class Error(val message: String) : PairStage
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val rt = (app as SenderApp).runtime

    /** A link shared into the app (or pasted), waiting to be cast. */
    private val _pendingUrl = MutableStateFlow<String?>(null)
    val pendingUrl: StateFlow<String?> = _pendingUrl.asStateFlow()
    fun setPendingUrl(url: String?) { _pendingUrl.value = url }

    /** Page open in the cast browser (restored when coming back to it); a shared web page lands here too. */
    val browserUrl = MutableStateFlow<String?>(null)

    /** Set when a shared web page should open the browser straight away. */
    val openBrowserRequest = MutableStateFlow(false)

    private val _stage = MutableStateFlow<PairStage>(PairStage.Idle)
    val stage: StateFlow<PairStage> = _stage.asStateFlow()
    private var session: PairingSession? = null
    private var job: Job? = null

    fun resetPairing() {
        job?.cancel()
        val s = session
        session = null
        if (s != null) viewModelScope.launch(Dispatchers.IO) { runCatching { s.cancel() } }
        _stage.value = PairStage.Idle
    }

    fun startPairing(qr: String) {
        resetPairing()
        _stage.value = PairStage.Connecting
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = rt.newPairingSession(qr)
                session = s
                val sas = s.begin()
                _stage.value = PairStage.Compare(sas, s.receiverName)
            } catch (e: IllegalArgumentException) {
                _stage.value = PairStage.Error("That is not a UniversalCast pairing code. On the TV choose “Pair a new device” and scan the QR code shown there.")
            } catch (e: Exception) {
                _stage.value = PairStage.Error(friendly(e))
            }
        }
    }

    fun codesMatch() {
        val s = session ?: return
        val tv = s.receiverName
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                s.confirm()
                _stage.value = PairStage.Waiting(tv)
                when (val o = s.awaitApproval()) {
                    is PairOutcome.Approved -> { rt.onPaired(o.receiver); _stage.value = PairStage.Done(o.receiver) }
                    PairOutcome.Rejected -> _stage.value = PairStage.Error("The TV rejected this phone.")
                    PairOutcome.Expired -> _stage.value = PairStage.Error("The pairing window expired. Start again on the TV.")
                    is PairOutcome.Failed -> _stage.value = PairStage.Error(o.reason)
                }
            } catch (e: Exception) {
                _stage.value = PairStage.Error(friendly(e))
            } finally {
                s.close()
            }
        }
    }

    fun codesDiffer() {
        val s = session ?: return
        job = viewModelScope.launch(Dispatchers.IO) {
            runCatching { s.cancel() }
            _stage.value = PairStage.Error("Pairing cancelled because the codes did not match. If this happens again, someone on your network may be interfering. Do not pair.")
        }
    }

    private fun friendly(e: Exception): String = when ((e as? UcpClientException)?.code) {
        ErrorCode.PAIRING_EXPIRED, ErrorCode.PAIRING_NOT_ACTIVE -> "That QR code has expired. Choose “Pair a new device” on the TV again."
        ErrorCode.PAIRING_INVALID_PROOF -> "The TV did not accept this pairing code. Show a fresh QR code and try again."
        else -> e.message ?: "Pairing failed."
    }

    override fun onCleared() {
        runCatching { session?.close() }
    }
}
