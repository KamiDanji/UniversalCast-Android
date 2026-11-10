package com.universalcast.receiver.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.universalcast.pairing.PairingBootstrap
import com.universalcast.identity.Role
import com.universalcast.receiver.UniversalCastApp

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val runtime = (app as UniversalCastApp).runtime
    val core get() = runtime.core

    /** Opens a new 120 s pairing window; returns the QR payload or an error message. */
    fun startPairing(): Result<PairingBootstrap> = runCatching {
        check(core.isRunning) { "The receiver is not running" }
        core.startPairing()
    }

    fun cancelPairing() { core.pairing.reject() }
    fun sasMatches(matches: Boolean) { core.pairing.userConfirmSas(matches) }
    fun approve(role: Role) { core.pairing.approve(role) }
    fun revoke(senderId: String) = runtime.revoke(senderId)
}
