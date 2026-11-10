package com.universalcast.pairing

import com.universalcast.identity.PairedSender
import com.universalcast.identity.Role
import com.universalcast.identity.SenderRegistry
import com.universalcast.security.Bytes
import com.universalcast.security.EcKeys
import com.universalcast.security.Fingerprint
import com.universalcast.security.Hashing
import com.universalcast.security.Hkdf
import com.universalcast.security.Hmac
import com.universalcast.security.ReceiverIdentity
import com.universalcast.security.SecureRandoms
import com.universalcast.security.TimeSource
import com.universalcast.security.Transcript
import com.universalcast.ucp.ErrorCode
import com.universalcast.ucp.Fields
import com.universalcast.ucp.JsonObject
import com.universalcast.ucp.StrictJson
import com.universalcast.ucp.Ucp
import com.universalcast.ucp.UcpException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** What a sender learns from the QR code. It is the out-of-band root of trust for the whole pairing. */
class PairingBootstrap(
    val deviceId: String,
    val deviceName: String,
    val fingerprint: String,
    val pairingId: String,
    val secret: ByteArray,
    val expiresAtMs: Long,
    val addresses: List<String>,
    val port: Int,
    val path: String = Ucp.WS_PATH,
) {
    fun toUri(): String {
        val json = StrictJson.stringify(
            linkedMapOf(
                "v" to Ucp.VERSION,
                "id" to deviceId,
                "name" to deviceName,
                "fp" to fingerprint,
                "pid" to pairingId,
                "sec" to Bytes.b64(secret),
                "exp" to expiresAtMs,
                "addr" to addresses,
                "port" to port,
                "path" to path,
            )
        )
        return PREFIX + Bytes.b64(json.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val PREFIX = "ucp://pair?d="

        /** Parses the text a sender scanned from the QR code. @throws IllegalArgumentException when invalid. */
        fun parse(uri: String): PairingBootstrap {
            if (!uri.startsWith(PREFIX) || uri.length > 4096) throw IllegalArgumentException("Not a UniversalCast pairing code")
            try {
                val json = String(Bytes.unb64(uri.substring(PREFIX.length)), Charsets.UTF_8)
                @Suppress("UNCHECKED_CAST")
                val f = Fields(StrictJson.parse(json) as Map<String, Any?>)
                if (f.long("v", 0, 1000) != Ucp.VERSION.toLong()) throw IllegalArgumentException("Unsupported pairing code version")
                val fp = f.string("fp", 64, 64)
                if (!Fingerprint.isWellFormed(fp)) throw IllegalArgumentException("Malformed receiver fingerprint")
                val pid = f.string("pid", 64)
                if (Bytes.unb64(pid).size != 16) throw IllegalArgumentException("Malformed pairing id")
                return PairingBootstrap(
                    deviceId = f.string("id", 64),
                    deviceName = f.string("name", 128),
                    fingerprint = fp,
                    pairingId = pid,
                    secret = f.bytes("sec", exactLength = 32),
                    expiresAtMs = f.long("exp", 0, Long.MAX_VALUE),
                    addresses = f.stringList("addr", 8, 64),
                    port = f.long("port", 1, 65535).toInt(),
                    path = f.string("path", 64),
                )
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("Malformed pairing code")
            }
        }
    }
}

class TranscriptInput(
    val receiverFingerprint: String,
    val pairingId: String,
    val senderSpki: ByteArray,
    val senderEphemeral: ByteArray,
    val senderNonce: ByteArray,
    val receiverEphemeral: ByteArray? = null,
    val receiverNonce: ByteArray? = null,
)

/** All pairing transcripts, MACs and the SAS derivation. Shared by receiver and sender so both sides agree. */
object PairingCrypto {
    class Derived(val sas: String, val confirmKey: ByteArray, val transcriptHash: ByteArray)

    /** Proves the sender saw the QR code (it knows the one-time secret). */
    fun bootstrapMac(secret: ByteArray, t: TranscriptInput): ByteArray = Hmac.sha256(
        secret,
        Transcript("UCP1-PAIR-BOOT").add(t.receiverFingerprint).add(t.pairingId)
            .add(t.senderSpki).add(t.senderEphemeral).add(t.senderNonce).toBytes()
    )

    /** Signed by the sender identity key: proof of possession, bound to this receiver and this pairing. */
    fun senderProofData(t: TranscriptInput, bootstrapMac: ByteArray): ByteArray =
        Transcript("UCP1-PAIR-SENDER-PROOF").add(t.receiverFingerprint).add(t.pairingId)
            .add(t.senderSpki).add(t.senderEphemeral).add(t.senderNonce).add(bootstrapMac).toBytes()

    fun receiverProofData(t: TranscriptInput): ByteArray =
        Transcript("UCP1-PAIR-RECEIVER-PROOF").add(t.receiverFingerprint).add(t.pairingId)
            .add(t.senderSpki).add(t.senderEphemeral).add(t.senderNonce)
            .add(t.receiverEphemeral!!).add(t.receiverNonce!!).toBytes()

    /** HKDF over the ECDH secret, bound to the entire transcript. Yields the 6-digit SAS and a key-confirmation key. */
    fun derive(sharedSecret: ByteArray, t: TranscriptInput): Derived {
        val senderId = Fingerprint.ofSpki(t.senderSpki)
        val transcriptHash = Hashing.sha256(
            Transcript("UCP1-PAIR-TRANSCRIPT").add(t.receiverFingerprint).add(senderId).add(t.pairingId)
                .add(t.senderSpki).add(t.senderEphemeral).add(t.senderNonce)
                .add(t.receiverEphemeral!!).add(t.receiverNonce!!).toBytes()
        )
        val prk = Hkdf.extract(Bytes.concat(t.senderNonce, t.receiverNonce), sharedSecret)
        val sasBytes = Hkdf.expand(prk, Bytes.concat("UCP1-SAS".toByteArray(Charsets.US_ASCII), transcriptHash), 4)
        val value = ((sasBytes[0].toInt() and 0x7F) shl 24) or
            ((sasBytes[1].toInt() and 0xFF) shl 16) or
            ((sasBytes[2].toInt() and 0xFF) shl 8) or
            (sasBytes[3].toInt() and 0xFF)
        val sas = String.format(Locale.ROOT, "%06d", value % 1_000_000)
        val confirmKey = Hkdf.expand(prk, Bytes.concat("UCP1-CONFIRM".toByteArray(Charsets.US_ASCII), transcriptHash), 32)
        return Derived(sas, confirmKey, transcriptHash)
    }

    fun senderConfirmMac(confirmKey: ByteArray, transcriptHash: ByteArray): ByteArray =
        Hmac.sha256(confirmKey, Transcript("UCP1-PAIR-CONFIRM-SENDER").add(transcriptHash).toBytes())

    fun resultMac(confirmKey: ByteArray, transcriptHash: ByteArray, status: String, role: String): ByteArray =
        Hmac.sha256(confirmKey, Transcript("UCP1-PAIR-RESULT").add(transcriptHash).add(status).add(role).toBytes())
}

enum class PairingState { WAITING_FOR_SENDER, AWAITING_VERIFICATION, READY_FOR_APPROVAL, APPROVED, REJECTED, EXPIRED, FAILED }

data class PairingView(
    val state: PairingState,
    val bootstrapUri: String,
    val expiresAtMs: Long,
    val remainingMs: Long,
    val senderName: String?,
    val senderId: String?,
    val sas: String?,
    val senderConfirmed: Boolean,
    val sasVerifiedByUser: Boolean,
    val failureReason: String?,
) {
    val isTerminal: Boolean
        get() = state == PairingState.APPROVED || state == PairingState.REJECTED ||
            state == PairingState.EXPIRED || state == PairingState.FAILED
}

class PairingOutcome(val status: String, val role: Role?, val reason: String?, val mac: ByteArray?)

class PairResponse(val payload: JsonObject, val expiresAtMs: Long)

interface PairingListener {
    fun onPairingChanged(view: PairingView?)
    fun onPairingFinished(connectionId: String, outcome: PairingOutcome)
}

/**
 * Receiver side of pairing. One pairing session at a time, valid for [windowMs] (120 s), single use.
 * Nothing is ever approved automatically: approval requires (1) the sender's cryptographic key confirmation,
 * (2) the user confirming the SAS matches, and (3) the user choosing a role and approving.
 */
class PairingManager(
    private val identity: ReceiverIdentity,
    private val registry: SenderRegistry,
    private val time: TimeSource,
    private val addresses: () -> List<String>,
    private val port: () -> Int,
    private val receiverName: () -> String,
    private val windowMs: Long = Ucp.PAIRING_WINDOW_MS,
) {
    private class Active(val bootstrap: PairingBootstrap, val uri: String) {
        var state = PairingState.WAITING_FOR_SENDER
        var connectionId: String? = null
        var senderSpki: ByteArray? = null
        var senderId: String? = null
        var senderName: String? = null
        var sas: String? = null
        var confirmKey: ByteArray? = null
        var transcriptHash: ByteArray? = null
        var senderConfirmed = false
        var userVerified = false
        var failure: String? = null
        val isTerminal: Boolean
            get() = state == PairingState.APPROVED || state == PairingState.REJECTED ||
                state == PairingState.EXPIRED || state == PairingState.FAILED
    }

    private val listeners = CopyOnWriteArrayList<PairingListener>()
    private var active: Active? = null

    fun addListener(l: PairingListener) {
        listeners.add(l)
    }

    fun removeListener(l: PairingListener) {
        listeners.remove(l)
    }

    private inline fun <T> locked(block: (MutableList<() -> Unit>) -> T): T {
        val events = ArrayList<() -> Unit>()
        try {
            return synchronized(this) { block(events) }
        } finally {
            events.forEach { it() }
        }
    }

    private fun viewLocked(): PairingView? {
        val a = active ?: return null
        val remaining = maxOf(0L, a.bootstrap.expiresAtMs - time.nowMs())
        return PairingView(
            a.state, a.uri, a.bootstrap.expiresAtMs, remaining, a.senderName, a.senderId, a.sas,
            a.senderConfirmed, a.userVerified, a.failure
        )
    }

    @Synchronized
    fun view(): PairingView? = viewLocked()

    private fun queueChanged(ev: MutableList<() -> Unit>) {
        val v = viewLocked()
        ev.add { listeners.forEach { it.onPairingChanged(v) } }
    }

    private fun finish(a: Active, ev: MutableList<() -> Unit>, state: PairingState, status: String, role: Role?, reason: String?) {
        if (a.isTerminal) return
        a.state = state
        a.failure = reason
        val key = a.confirmKey
        val hash = a.transcriptHash
        val mac = if (key != null && hash != null) PairingCrypto.resultMac(key, hash, status, role?.name ?: "") else null
        val outcome = PairingOutcome(status, role, reason, mac)
        val connId = a.connectionId
        val v = viewLocked()
        ev.add {
            listeners.forEach { l ->
                if (connId != null) l.onPairingFinished(connId, outcome)
                l.onPairingChanged(v)
            }
        }
    }

    private fun expireIfNeeded(a: Active, ev: MutableList<() -> Unit>) {
        if (!a.isTerminal && time.nowMs() >= a.bootstrap.expiresAtMs) {
            finish(a, ev, PairingState.EXPIRED, "expired", null, "The 120 second pairing window elapsed")
        }
    }

    /** Opens a new pairing window, superseding any pairing in progress. */
    fun startPairing(): PairingBootstrap = locked { ev ->
        active?.let { finish(it, ev, PairingState.REJECTED, "cancelled", null, "Superseded by a new pairing session") }
        val now = time.nowMs()
        val bootstrap = PairingBootstrap(
            deviceId = identity.deviceId,
            deviceName = receiverName(),
            fingerprint = identity.fingerprint,
            pairingId = Bytes.b64(SecureRandoms.bytes(16)),
            secret = SecureRandoms.bytes(32),
            expiresAtMs = now + windowMs,
            addresses = addresses(),
            port = port(),
        )
        active = Active(bootstrap, bootstrap.toUri())
        queueChanged(ev)
        bootstrap
    }

    /** Handles `pair.request`. Throws [UcpException] on any failure; a bad proof burns the pairing session. */
    fun handleRequest(connectionId: String, f: Fields): PairResponse = locked { ev ->
        val a = active ?: throw UcpException(ErrorCode.PAIRING_NOT_ACTIVE, "No pairing is in progress")
        expireIfNeeded(a, ev)
        if (a.state == PairingState.EXPIRED) throw UcpException(ErrorCode.PAIRING_EXPIRED, "The pairing window has expired")
        if (a.state != PairingState.WAITING_FOR_SENDER) {
            throw UcpException(ErrorCode.PAIRING_NOT_ACTIVE, "This pairing session is no longer accepting requests")
        }
        val pid = f.string("pairingId", Ucp.MAX_ID_LENGTH)
        if (!Hashing.constantTimeEquals(pid.toByteArray(Charsets.UTF_8), a.bootstrap.pairingId.toByteArray(Charsets.UTF_8))) {
            throw UcpException(ErrorCode.PAIRING_NOT_ACTIVE, "Unknown pairing session")
        }
        val senderSpki = f.bytes("senderPublicKey", maxLength = 200)
        val senderEphemeral = f.bytes("ephemeralPublicKey", maxLength = 200)
        val senderNonce = f.bytes("senderNonce", exactLength = 32)
        val bootstrapMac = f.bytes("bootstrapMac", exactLength = 32)
        val signature = f.bytes("signature", maxLength = 80)
        val name = sanitizeName(f.string("senderName", 128))

        val t0 = TranscriptInput(identity.fingerprint, a.bootstrap.pairingId, senderSpki, senderEphemeral, senderNonce)
        if (!Hashing.constantTimeEquals(PairingCrypto.bootstrapMac(a.bootstrap.secret, t0), bootstrapMac)) {
            finish(a, ev, PairingState.FAILED, "failed", null, "Sender did not prove knowledge of the pairing code")
            throw UcpException(ErrorCode.PAIRING_INVALID_PROOF, "Invalid pairing proof")
        }
        if (!EcKeys.verify(senderSpki, PairingCrypto.senderProofData(t0, bootstrapMac), signature)) {
            finish(a, ev, PairingState.FAILED, "failed", null, "Sender identity proof was invalid")
            throw UcpException(ErrorCode.PAIRING_INVALID_PROOF, "Invalid pairing proof")
        }

        val ephemeral = EcKeys.generateKeyPair()
        val receiverEphemeral = ephemeral.public.encoded
        val receiverNonce = SecureRandoms.bytes(32)
        val derived: PairingCrypto.Derived
        val t = TranscriptInput(
            identity.fingerprint, a.bootstrap.pairingId, senderSpki, senderEphemeral, senderNonce, receiverEphemeral, receiverNonce
        )
        try {
            derived = PairingCrypto.derive(EcKeys.agree(ephemeral.private, senderEphemeral), t)
        } catch (e: Exception) {
            finish(a, ev, PairingState.FAILED, "failed", null, "Sender ephemeral key was invalid")
            throw UcpException(ErrorCode.PAIRING_INVALID_PROOF, "Invalid pairing proof")
        }
        val receiverSignature = identity.sign(PairingCrypto.receiverProofData(t))

        a.connectionId = connectionId
        a.senderSpki = senderSpki
        a.senderId = Fingerprint.ofSpki(senderSpki)
        a.senderName = name
        a.sas = derived.sas
        a.confirmKey = derived.confirmKey
        a.transcriptHash = derived.transcriptHash
        a.state = PairingState.AWAITING_VERIFICATION
        queueChanged(ev)

        PairResponse(
            linkedMapOf(
                "receiverPublicKey" to Bytes.b64(identity.publicKeySpki),
                "ephemeralPublicKey" to Bytes.b64(receiverEphemeral),
                "receiverNonce" to Bytes.b64(receiverNonce),
                "signature" to Bytes.b64(receiverSignature),
                "receiverName" to receiverName(),
                "receiverDeviceId" to identity.deviceId,
                "expiresAtMs" to a.bootstrap.expiresAtMs,
            ),
            a.bootstrap.expiresAtMs,
        )
    }

    /** Handles `pair.confirm`: the sender proves it derived the same keys (and therefore the same SAS). */
    fun handleSenderConfirm(connectionId: String, f: Fields) = locked { ev ->
        val a = active ?: throw UcpException(ErrorCode.PAIRING_NOT_ACTIVE, "No pairing is in progress")
        expireIfNeeded(a, ev)
        if (a.state == PairingState.EXPIRED) throw UcpException(ErrorCode.PAIRING_EXPIRED, "The pairing window has expired")
        if (a.connectionId != connectionId || a.state != PairingState.AWAITING_VERIFICATION) {
            throw UcpException(ErrorCode.INVALID_STATE, "Pairing is not awaiting sender confirmation")
        }
        val mac = f.bytes("confirmMac", exactLength = 32)
        val expected = PairingCrypto.senderConfirmMac(a.confirmKey!!, a.transcriptHash!!)
        if (!Hashing.constantTimeEquals(expected, mac)) {
            finish(a, ev, PairingState.FAILED, "failed", null, "Sender key confirmation failed")
            throw UcpException(ErrorCode.PAIRING_INVALID_PROOF, "Invalid confirmation")
        }
        a.senderConfirmed = true
        updateReady(a)
        queueChanged(ev)
    }

    fun handleSenderCancel(connectionId: String) = locked { ev ->
        val a = active
        if (a != null && a.connectionId == connectionId && !a.isTerminal) {
            finish(a, ev, PairingState.FAILED, "failed", null, "The sender cancelled pairing")
        }
    }

    fun onConnectionClosed(connectionId: String) = locked { ev ->
        val a = active
        if (a != null && a.connectionId == connectionId &&
            (a.state == PairingState.AWAITING_VERIFICATION || a.state == PairingState.READY_FOR_APPROVAL)
        ) {
            finish(a, ev, PairingState.FAILED, "failed", null, "The sender disconnected")
        }
    }

    private fun updateReady(a: Active) {
        if (a.state == PairingState.AWAITING_VERIFICATION && a.senderConfirmed && a.userVerified) {
            a.state = PairingState.READY_FOR_APPROVAL
        }
    }

    /** The user compares the SAS shown here with the one on the sender. `false` aborts the pairing. */
    fun userConfirmSas(matches: Boolean): Boolean = locked { ev ->
        val a = active
        if (a == null) {
            false
        } else {
            expireIfNeeded(a, ev)
            if (a.state != PairingState.AWAITING_VERIFICATION) {
                false
            } else if (!matches) {
                finish(a, ev, PairingState.FAILED, "failed", null, "The verification codes did not match")
                true
            } else {
                a.userVerified = true
                updateReady(a)
                queueChanged(ev)
                true
            }
        }
    }

    /** Approves with [role]. Only possible once the sender confirmed and the user verified the SAS. */
    fun approve(role: Role): Boolean = locked { ev ->
        val a = active
        if (a == null) {
            false
        } else {
            expireIfNeeded(a, ev)
            if (a.state != PairingState.READY_FOR_APPROVAL) {
                false
            } else {
                registry.save(PairedSender(a.senderId!!, a.senderSpki!!, a.senderName!!, role, time.nowMs()))
                finish(a, ev, PairingState.APPROVED, "approved", role, null)
                true
            }
        }
    }

    fun reject(): Boolean = locked { ev ->
        val a = active
        if (a == null || a.isTerminal) {
            false
        } else {
            finish(a, ev, PairingState.REJECTED, "rejected", null, "Rejected on the receiver")
            true
        }
    }

    /** Forgets a finished pairing (UI left the screen). A running pairing is not affected. */
    fun dismiss() = locked { ev ->
        val a = active
        if (a != null && a.isTerminal) {
            active = null
            ev.add { listeners.forEach { it.onPairingChanged(null) } }
        }
    }

    /** Called periodically: expires the window and tells a waiting sender. */
    fun tick() = locked { ev ->
        active?.let { expireIfNeeded(it, ev) }
    }

    companion object {
        fun sanitizeName(raw: String): String {
            val cleaned = raw.filter { !it.isISOControl() }.trim().take(48)
            return cleaned.ifEmpty { "Unknown device" }
        }
    }
}
