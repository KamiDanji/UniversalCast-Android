package com.universalcast.sender

import com.universalcast.auth.AuthTranscripts
import com.universalcast.pairing.PairingBootstrap
import com.universalcast.pairing.PairingCrypto
import com.universalcast.pairing.TranscriptInput
import com.universalcast.playback.PlaybackSnapshot
import com.universalcast.security.Bytes
import com.universalcast.security.EcKeys
import com.universalcast.security.Fingerprint
import com.universalcast.security.Hashing
import com.universalcast.security.SecureRandoms
import com.universalcast.security.SenderIdentity
import com.universalcast.security.TlsPinning
import com.universalcast.ucp.Envelope
import com.universalcast.ucp.EnvelopeCodec
import com.universalcast.ucp.ErrorCode
import com.universalcast.ucp.Fields
import com.universalcast.ucp.JsonObject
import com.universalcast.ucp.Method
import com.universalcast.ucp.MessageType
import com.universalcast.ucp.Ucp
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

class UcpClientException(val code: ErrorCode?, message: String) : Exception(message)

/**
 * Reference sender-side UCP client (also used by the test-suite). It pins the receiver's TLS key to the
 * fingerprint it was given, authenticates with challenge-response and verifies the receiver's proof.
 * Sender UIs (QR scanning, key storage in the Android Keystore) are the sender app's responsibility.
 */
class UcpClient(
    private val identity: SenderIdentity,
    private val host: String,
    private val port: Int,
    val receiverFingerprint: String,
) {
    private val tls = TlsPinning.create(receiverFingerprint)
    private val seq = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableFuture<Envelope>>()
    val events = LinkedBlockingQueue<Envelope>()
    val rawMessages = CopyOnWriteArrayList<String>()

    @Volatile var closeCode: Int? = null
        private set
    @Volatile var lastError: Exception? = null
        private set

    /** Invoked once when the connection closes (any reason). Runs on the WebSocket thread; keep it short. */
    @Volatile var onClosed: ((code: Int) -> Unit)? = null
    private var ws: WebSocketClient? = null

    /** SPKI of the TLS certificate the receiver presented (valid after a successful [connect]). */
    val peerSpki: ByteArray? get() = tls.trustManager.peerSpki

    val isOpen: Boolean get() = ws?.isOpen == true

    /** @return true when the TLS handshake (including the fingerprint pin) and WebSocket upgrade succeeded. */
    fun connect(timeoutMs: Long = 5_000, path: String = Ucp.WS_PATH): Boolean {
        val hostPart = if (host.contains(':')) "[$host]" else host
        val client = object : WebSocketClient(URI("wss://$hostPart:$port$path")) {
            override fun onOpen(handshakedata: ServerHandshake?) {}
            override fun onMessage(message: String) {
                rawMessages.add(message)
                val env = try {
                    EnvelopeCodec.decode(message)
                } catch (e: Exception) {
                    return
                }
                when (env.type) {
                    MessageType.RESPONSE -> env.id?.let { pending.remove(it)?.complete(env) }
                    MessageType.EVENT -> events.add(env)
                    else -> {}
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                closeCode = code
                pending.values.forEach { it.completeExceptionally(UcpClientException(null, "Connection closed ($code)")) }
                try {
                    onClosed?.invoke(code)
                } catch (e: Exception) {
                    // listener failures must not break the socket thread
                }
            }

            // The pinned SPKI fingerprint is the only trust root. The library's default (HTTPS hostname
            // verification against a CA-issued name) cannot apply to a self-signed key addressed by IP.
            override fun onSetSSLParameters(sslParameters: javax.net.ssl.SSLParameters?) {
                sslParameters?.endpointIdentificationAlgorithm = null
            }

            override fun onError(ex: Exception) {
                lastError = ex
            }
        }
        client.setSocketFactory(tls.sslContext.socketFactory)
        client.connectionLostTimeout = 0
        ws = client
        return client.connectBlocking(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun close() {
        try {
            ws?.closeBlocking()
        } catch (e: Exception) {
        }
    }

    fun nextSeq(): Long = seq.incrementAndGet()

    /** Sends raw text exactly as given (for protocol-abuse tests). */
    fun sendRaw(text: String) {
        ws!!.send(text)
    }

    fun sendRawBinary(data: ByteArray) {
        ws!!.send(data)
    }

    /** Sends a request and waits for its response. Error responses are returned, not thrown. */
    fun request(
        method: String,
        payload: JsonObject = emptyMap(),
        session: String? = null,
        timeoutMs: Long = 5_000,
        id: String? = null,
        seqOverride: Long? = null,
    ): Envelope {
        val seqNo = seqOverride ?: nextSeq()
        val rid = id ?: "c$seqNo-${Bytes.b64(SecureRandoms.bytes(4))}"
        val fut = CompletableFuture<Envelope>()
        pending[rid] = fut
        ws!!.send(EnvelopeCodec.encode(Envelope.request(rid, seqNo, method, payload, session)))
        try {
            return fut.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending.remove(rid)
            throw UcpClientException(null, "Timed out waiting for response to $method")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? UcpClientException) ?: UcpClientException(null, "Request failed: ${e.cause?.message}")
        }
    }

    /** Request that must succeed. */
    fun call(method: String, payload: JsonObject = emptyMap(), session: String? = null): Envelope {
        val r = request(method, payload, session)
        if (r.ok != true) throw UcpClientException(r.error?.code, r.error?.message ?: "Request failed")
        return r
    }

    fun awaitEvent(name: String, timeoutMs: Long = 5_000): Envelope? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val e = events.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (e.name == name) return e
        }
    }

    class AuthInfo(val role: String)

    /** Full challenge-response login; also verifies the receiver's transcript signature. */
    fun authenticate(): AuthInfo {
        val clientNonce = SecureRandoms.bytes(32)
        val hello = call(
            Method.AUTH_HELLO,
            linkedMapOf("versions" to listOf(Ucp.VERSION), "senderId" to identity.senderId, "clientNonce" to Bytes.b64(clientNonce))
        )
        val h = Fields(hello.payload)
        val challengeId = h.string("challengeId", 64)
        val challenge = h.bytes("challenge", exactLength = 32)
        val version = h.long("version", 1, 1000).toInt()
        if (h.string("receiverFingerprint", 64, 64) != receiverFingerprint.lowercase()) {
            throw UcpClientException(null, "Receiver announced a different fingerprint")
        }
        val signature = identity.sign(
            AuthTranscripts.clientData(receiverFingerprint.lowercase(), identity.senderId, version, challengeId, challenge, clientNonce)
        )
        val resp = call(Method.AUTH_RESPONSE, linkedMapOf("challengeId" to challengeId, "signature" to Bytes.b64(signature)))
        val r = Fields(resp.payload)
        val proof = r.bytes("serverProof", maxLength = 80)
        val spki = peerSpki ?: throw UcpClientException(null, "No TLS peer key recorded")
        val expected = AuthTranscripts.serverData(
            receiverFingerprint.lowercase(), identity.senderId, version, challengeId, challenge, clientNonce, signature
        )
        if (!EcKeys.verify(spki, expected, proof)) throw UcpClientException(null, "Receiver proof is invalid")
        return AuthInfo(r.string("role", 16))
    }

    fun snapshot(): PlaybackSnapshot =
        PlaybackSnapshot.fromJson(Fields(call(Method.PLAYBACK_SNAPSHOT).payload).obj("snapshot"))
}

class PairingResult(val status: String, val role: String?, val reason: String?, val macValid: Boolean)

/** Sender side of pairing, mirroring the receiver's [com.universalcast.pairing.PairingManager]. */
class PairingClient(
    private val identity: SenderIdentity,
    private val bootstrap: PairingBootstrap,
    private val senderName: String,
    host: String = bootstrap.addresses.firstOrNull() ?: throw IllegalArgumentException("No address in pairing code"),
    port: Int = bootstrap.port,
) {
    val client = UcpClient(identity, host, port, bootstrap.fingerprint)
    private val ephemeral = EcKeys.generateKeyPair()
    private var derived: PairingCrypto.Derived? = null

    val sas: String get() = derived?.sas ?: throw IllegalStateException("Pairing has not begun")

    /** Connects (pinned to the QR fingerprint), sends the proof, verifies the receiver and returns the SAS to display. */
    fun begin(): String {
        if (!client.connect()) throw UcpClientException(null, "Could not establish a pinned secure connection")
        val senderEphemeral = ephemeral.public.encoded
        val senderNonce = SecureRandoms.bytes(32)
        val t0 = TranscriptInput(bootstrap.fingerprint, bootstrap.pairingId, identity.publicKeySpki, senderEphemeral, senderNonce)
        val bootMac = PairingCrypto.bootstrapMac(bootstrap.secret, t0)
        val sig = identity.sign(PairingCrypto.senderProofData(t0, bootMac))
        val resp = client.call(
            Method.PAIR_REQUEST,
            linkedMapOf(
                "pairingId" to bootstrap.pairingId,
                "senderName" to senderName,
                "senderPublicKey" to Bytes.b64(identity.publicKeySpki),
                "ephemeralPublicKey" to Bytes.b64(senderEphemeral),
                "senderNonce" to Bytes.b64(senderNonce),
                "bootstrapMac" to Bytes.b64(bootMac),
                "signature" to Bytes.b64(sig),
            )
        )
        val f = Fields(resp.payload)
        val receiverSpki = f.bytes("receiverPublicKey", maxLength = 200)
        if (Fingerprint.ofSpki(receiverSpki) != bootstrap.fingerprint) throw UcpClientException(null, "Receiver key does not match the QR code")
        if (client.peerSpki?.contentEquals(receiverSpki) != true) throw UcpClientException(null, "Receiver key does not match the TLS certificate")
        val receiverEphemeral = f.bytes("ephemeralPublicKey", maxLength = 200)
        val receiverNonce = f.bytes("receiverNonce", exactLength = 32)
        val t = TranscriptInput(
            bootstrap.fingerprint, bootstrap.pairingId, identity.publicKeySpki, senderEphemeral, senderNonce, receiverEphemeral, receiverNonce
        )
        if (!EcKeys.verify(receiverSpki, PairingCrypto.receiverProofData(t), f.bytes("signature", maxLength = 80))) {
            throw UcpClientException(null, "Receiver proof is invalid")
        }
        derived = PairingCrypto.derive(EcKeys.agree(ephemeral.private, receiverEphemeral), t)
        return derived!!.sas
    }

    /** Call after the sender's user confirmed that the SAS shown on both screens matches. */
    fun confirm() {
        val d = derived ?: throw IllegalStateException("Pairing has not begun")
        client.call(Method.PAIR_CONFIRM, mapOf("confirmMac" to Bytes.b64(PairingCrypto.senderConfirmMac(d.confirmKey, d.transcriptHash))))
    }

    fun cancel() {
        client.request(Method.PAIR_CANCEL)
    }

    fun awaitResult(timeoutMs: Long = 10_000): PairingResult? {
        val d = derived ?: throw IllegalStateException("Pairing has not begun")
        val e = client.awaitEvent("pair.result", timeoutMs) ?: return null
        val f = Fields(e.payload)
        val status = f.string("status", 32)
        val role = f.optString("role", 16)
        val macText = f.optString("mac", 100)
        val valid = macText != null &&
            Hashing.constantTimeEquals(PairingCrypto.resultMac(d.confirmKey, d.transcriptHash, status, role ?: ""), Bytes.unb64(macText))
        return PairingResult(status, role, f.optString("reason", 256), valid)
    }
}
