package com.universalcast.auth

import com.universalcast.identity.PairedSender
import com.universalcast.security.Bytes
import com.universalcast.security.EcKeys
import com.universalcast.security.SecureRandoms
import com.universalcast.security.TimeSource
import com.universalcast.security.Transcript
import com.universalcast.ucp.Ucp

class Challenge(
    val id: String,
    val bytes: ByteArray,
    val clientNonce: ByteArray,
    val senderId: String,
    val version: Int,
    val connectionId: String,
    val expiresAtMs: Long,
)

/** Exact byte layouts that are signed during authentication. Both sides must build them identically. */
object AuthTranscripts {
    fun clientData(
        receiverFingerprint: String, senderId: String, version: Int,
        challengeId: String, challenge: ByteArray, clientNonce: ByteArray,
    ): ByteArray = Transcript("UCP1-AUTH-CLIENT")
        .add(receiverFingerprint).add(senderId).add(version.toLong())
        .add(challengeId).add(challenge).add(clientNonce).toBytes()

    fun serverData(
        receiverFingerprint: String, senderId: String, version: Int,
        challengeId: String, challenge: ByteArray, clientNonce: ByteArray, clientSignature: ByteArray,
    ): ByteArray = Transcript("UCP1-AUTH-SERVER")
        .add(receiverFingerprint).add(senderId).add(version.toLong())
        .add(challengeId).add(challenge).add(clientNonce).add(clientSignature).toBytes()
}

enum class AuthFailure { UNKNOWN_CHALLENGE, REPLAYED, WRONG_CONNECTION, EXPIRED, SENDER_MISMATCH, REVOKED, BAD_SIGNATURE }

class AuthResult(val failure: AuthFailure?, val challenge: Challenge?) {
    val success: Boolean get() = failure == null
}

/**
 * Issues fresh single-use challenges and verifies the sender's signature over a transcript that binds
 * the receiver fingerprint, sender id, protocol version, challenge and client nonce.
 *
 * A challenge is removed the moment a verification is attempted, whether or not it succeeds, so it can never
 * be answered twice. Consumed ids are remembered (bounded) so that reuse is reported as a replay.
 */
class Authenticator(
    private val receiverFingerprint: String,
    private val time: TimeSource,
    private val challengeTtlMs: Long = Ucp.CHALLENGE_TTL_MS,
) {
    private val issued = HashMap<String, Challenge>()
    private val consumed = LinkedHashSet<String>()

    @Synchronized
    fun issue(connectionId: String, senderId: String, clientNonce: ByteArray, version: Int): Challenge {
        val now = time.nowMs()
        issued.values.removeAll { it.expiresAtMs <= now }
        val c = Challenge(
            id = Bytes.b64(SecureRandoms.bytes(16)),
            bytes = SecureRandoms.bytes(32),
            clientNonce = clientNonce,
            senderId = senderId,
            version = version,
            connectionId = connectionId,
            expiresAtMs = now + challengeTtlMs,
        )
        issued[c.id] = c
        return c
    }

    @Synchronized
    fun wasConsumed(challengeId: String): Boolean = challengeId in consumed

    @Synchronized
    fun verify(connectionId: String, challengeId: String, signature: ByteArray, sender: PairedSender?): AuthResult {
        val c = issued.remove(challengeId)
        if (c == null) {
            return AuthResult(if (challengeId in consumed) AuthFailure.REPLAYED else AuthFailure.UNKNOWN_CHALLENGE, null)
        }
        consumed.add(challengeId)
        if (consumed.size > 2048) consumed.remove(consumed.first())

        if (c.connectionId != connectionId) return AuthResult(AuthFailure.WRONG_CONNECTION, null)
        if (time.nowMs() >= c.expiresAtMs) return AuthResult(AuthFailure.EXPIRED, null)
        if (sender == null || sender.senderId != c.senderId) return AuthResult(AuthFailure.SENDER_MISMATCH, null)
        if (sender.isRevoked) return AuthResult(AuthFailure.REVOKED, null)
        val data = AuthTranscripts.clientData(receiverFingerprint, c.senderId, c.version, c.id, c.bytes, c.clientNonce)
        if (!EcKeys.verify(sender.publicKeySpki, data, signature)) return AuthResult(AuthFailure.BAD_SIGNATURE, null)
        return AuthResult(null, c)
    }

    @Synchronized
    fun forgetConnection(connectionId: String) {
        issued.values.removeAll { it.connectionId == connectionId }
    }
}
