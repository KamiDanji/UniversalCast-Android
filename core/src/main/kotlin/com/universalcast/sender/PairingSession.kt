package com.universalcast.sender

import com.universalcast.pairing.PairingBootstrap
import com.universalcast.security.SenderIdentity

sealed class PairOutcome {
    class Approved(val receiver: PairedReceiver) : PairOutcome()
    object Rejected : PairOutcome()
    object Expired : PairOutcome()
    class Failed(val reason: String) : PairOutcome()
}

/**
 * One pairing attempt from the phone's side. All methods block: call them off the main thread.
 *
 * Order: [begin] (shows the 6-digit code) → user compares codes on both screens → [confirm] (or [cancel]) →
 * [awaitApproval] (the TV user must still approve and picks the role).
 */
class PairingSession(
    private val identity: SenderIdentity,
    private val senderName: String,
    qrText: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** @throws IllegalArgumentException when the text is not a UniversalCast pairing code. */
    val bootstrap: PairingBootstrap = PairingBootstrap.parse(qrText.trim())

    val receiverName: String get() = bootstrap.deviceName

    private var active: PairingClient? = null

    /** Connects (pinned to the QR's fingerprint), proves knowledge of the QR secret and returns the SAS to display. */
    fun begin(): String {
        require(bootstrap.addresses.isNotEmpty()) { "The pairing code contains no address" }
        var last: Exception? = null
        for (addr in bootstrap.addresses.take(4)) {
            val pc = PairingClient(identity, bootstrap, senderName.take(64), addr, bootstrap.port)
            try {
                val sas = pc.begin()
                active = pc
                return sas
            } catch (e: UcpClientException) {
                pc.client.close()
                // The receiver answered with a protocol error (expired, wrong proof…): other addresses won't help.
                if (e.code != null) throw e
                last = e
            } catch (e: Exception) {
                pc.client.close()
                last = e
            }
        }
        throw UcpClientException(null, "Could not reach the TV (${last?.message ?: "no route"}). Is this phone on the same Wi-Fi?")
    }

    fun confirm() = (active ?: error("Pairing has not begun")).confirm()

    fun cancel() {
        try {
            active?.cancel()
        } catch (e: Exception) {
        } finally {
            close()
        }
    }

    fun awaitApproval(timeoutMs: Long = 130_000): PairOutcome {
        val pc = active ?: error("Pairing has not begun")
        try {
            val r = pc.awaitResult(timeoutMs) ?: return PairOutcome.Failed("The TV did not answer in time.")
            return when (r.status) {
                "approved" -> if (!r.macValid) {
                    PairOutcome.Failed("The TV's approval could not be verified. Nothing was saved.")
                } else {
                    PairOutcome.Approved(
                        PairedReceiver(
                            fingerprint = bootstrap.fingerprint, deviceId = bootstrap.deviceId, name = bootstrap.deviceName,
                            addresses = bootstrap.addresses, port = bootstrap.port, path = bootstrap.path,
                            role = r.role ?: "OBSERVER", pairedAtMs = clock(),
                        )
                    )
                }
                "rejected" -> PairOutcome.Rejected
                "expired" -> PairOutcome.Expired
                else -> PairOutcome.Failed(r.reason ?: "Pairing failed.")
            }
        } finally {
            close()
        }
    }

    fun close() {
        try {
            active?.client?.close()
        } catch (e: Exception) {
        }
    }
}
