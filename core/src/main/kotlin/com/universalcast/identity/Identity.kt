package com.universalcast.identity

import com.universalcast.security.Bytes
import com.universalcast.security.EcKeys
import com.universalcast.security.Fingerprint
import com.universalcast.ucp.Fields
import com.universalcast.ucp.Method
import com.universalcast.ucp.StrictJson

enum class Permission {
    PING, READ_SNAPSHOT, CREATE_SESSION, END_SESSION, LOAD_MEDIA, PLAY, PAUSE, RESUME, SEEK, STOP, SET_SPEED, SET_VOLUME
}

enum class Role(val permissions: Set<Permission>) {
    CONTROLLER(Permission.values().toSet()),
    OBSERVER(setOf(Permission.PING, Permission.READ_SNAPSHOT));

    fun allows(permission: Permission) = permission in permissions
}

object Permissions {
    /** The permission a method requires, or null if the method does not exist. Enforced by the receiver. */
    fun requiredFor(method: String): Permission? = when (method) {
        Method.PING -> Permission.PING
        Method.CAPABILITIES_GET, Method.PLAYBACK_SNAPSHOT -> Permission.READ_SNAPSHOT
        Method.SESSION_CREATE -> Permission.CREATE_SESSION
        Method.SESSION_TERMINATE -> Permission.END_SESSION
        Method.MEDIA_LOAD -> Permission.LOAD_MEDIA
        Method.PLAYBACK_PLAY -> Permission.PLAY
        Method.PLAYBACK_PAUSE -> Permission.PAUSE
        Method.PLAYBACK_RESUME -> Permission.RESUME
        Method.PLAYBACK_STOP -> Permission.STOP
        Method.PLAYBACK_SEEK -> Permission.SEEK
        Method.PLAYBACK_SET_SPEED -> Permission.SET_SPEED
        Method.PLAYBACK_SET_VOLUME -> Permission.SET_VOLUME
        else -> null
    }
}

class PairedSender(
    val senderId: String,
    val publicKeySpki: ByteArray,
    val name: String,
    val role: Role,
    val pairedAtMs: Long,
    val lastConnectedAtMs: Long? = null,
    val revokedAtMs: Long? = null,
) {
    val isRevoked: Boolean get() = revokedAtMs != null

    fun copy(
        name: String = this.name,
        role: Role = this.role,
        lastConnectedAtMs: Long? = this.lastConnectedAtMs,
        revokedAtMs: Long? = this.revokedAtMs,
    ) = PairedSender(senderId, publicKeySpki, name, role, pairedAtMs, lastConnectedAtMs, revokedAtMs)
}

/** Persistent store of paired sender identities, their roles and revocations. Must be thread-safe. */
interface SenderRegistry {
    fun find(senderId: String): PairedSender?
    fun all(): List<PairedSender>
    /** Inserts or replaces (a fresh pairing of a revoked sender replaces its revoked record). */
    fun save(sender: PairedSender)
    /** Marks the sender revoked. Returns false if unknown. The revocation must be persisted before returning. */
    fun revoke(senderId: String, atMs: Long): Boolean
    fun markConnected(senderId: String, atMs: Long)
}

class InMemorySenderRegistry : SenderRegistry {
    private val map = LinkedHashMap<String, PairedSender>()

    @Synchronized override fun find(senderId: String) = map[senderId]
    @Synchronized override fun all() = map.values.toList()
    @Synchronized override fun save(sender: PairedSender) {
        map[sender.senderId] = sender
    }

    @Synchronized override fun revoke(senderId: String, atMs: Long): Boolean {
        val s = map[senderId] ?: return false
        if (!s.isRevoked) map[senderId] = s.copy(revokedAtMs = atMs)
        return true
    }

    @Synchronized override fun markConnected(senderId: String, atMs: Long) {
        map[senderId]?.let { map[senderId] = it.copy(lastConnectedAtMs = atMs) }
    }
}

/** Serialization of a [PairedSender] record, used by persistent registries. Decoding re-validates everything. */
object PairedSenderJson {
    fun encode(s: PairedSender): String = StrictJson.stringify(
        linkedMapOf(
            "senderId" to s.senderId,
            "publicKey" to Bytes.b64(s.publicKeySpki),
            "name" to s.name,
            "role" to s.role.name,
            "pairedAtMs" to s.pairedAtMs,
            "lastConnectedAtMs" to s.lastConnectedAtMs,
            "revokedAtMs" to s.revokedAtMs,
        )
    )

    /** Returns null for any corrupted, tampered or inconsistent record. */
    fun decode(text: String): PairedSender? = try {
        @Suppress("UNCHECKED_CAST")
        val f = Fields(StrictJson.parse(text) as Map<String, Any?>)
        val spki = f.bytes("publicKey", maxLength = 200)
        EcKeys.decodePublicKey(spki)
        val id = f.string("senderId", 64, 64)
        if (id != Fingerprint.ofSpki(spki)) {
            null
        } else {
            PairedSender(
                senderId = id,
                publicKeySpki = spki,
                name = f.string("name", 64),
                role = Role.valueOf(f.string("role", 16)),
                pairedAtMs = f.long("pairedAtMs", 0, Long.MAX_VALUE),
                lastConnectedAtMs = f.optLong("lastConnectedAtMs", 0, Long.MAX_VALUE),
                revokedAtMs = f.optLong("revokedAtMs", 0, Long.MAX_VALUE),
            )
        }
    } catch (e: Exception) {
        null
    }
}
