package com.universalcast.session

import com.universalcast.security.Bytes
import com.universalcast.security.SecureRandoms
import com.universalcast.security.TimeSource
import com.universalcast.ucp.ErrorCode
import com.universalcast.ucp.UcpException
import java.util.concurrent.CopyOnWriteArrayList

data class Session(
    val id: String,
    val ownerConnectionId: String,
    val ownerSenderId: String,
    val createdAtMs: Long,
    val detachedAtMs: Long? = null,
) {
    val isDetached: Boolean get() = detachedAtMs != null
}

enum class SessionEndReason { TERMINATED, REPLACED, CONNECTION_CLOSED, REVOKED, SHUTDOWN }

fun interface SessionListener {
    fun onSessionEnded(session: Session, reason: SessionEndReason)
}

/**
 * The receiver has one playback endpoint, hence at most one active session ("last controller wins").
 * Creating a session while another exists ends the old one with [SessionEndReason.REPLACED].
 * When a controlling connection drops and a grace period is configured, the session enters a detached state
 * so playback continues undisturbed until the controller reconnects, another controller takes over, or the
 * grace period expires.
 */
class SessionManager(private val time: TimeSource) {
    class CreateResult(val session: Session, val replaced: Session?, val resumed: Boolean = false)

    private var current: Session? = null
    private val listeners = CopyOnWriteArrayList<SessionListener>()

    fun addListener(l: SessionListener) {
        listeners.add(l)
    }

    fun removeListener(l: SessionListener) {
        listeners.remove(l)
    }

    fun create(connectionId: String, senderId: String): CreateResult {
        val created = Session(Bytes.b64(SecureRandoms.bytes(16)), connectionId, senderId, time.nowMs())
        val resumed: Boolean
        val old = synchronized(this) {
            val previous = current
            resumed = previous != null && previous.ownerSenderId == senderId && previous.isDetached
            current = created
            previous
        }
        if (old != null) notifyEnded(old, SessionEndReason.REPLACED)
        return CreateResult(created, old, resumed)
    }

    @Synchronized
    fun active(): Session? = current

    /** Returns the session if [sessionId] is the active session and is owned by [connectionId]. */
    fun validate(sessionId: String?, connectionId: String): Session {
        if (sessionId == null) throw UcpException(ErrorCode.SESSION_REQUIRED, "A session id is required")
        val s = active()
        if (s == null || s.id != sessionId || s.ownerConnectionId != connectionId || s.isDetached) {
            throw UcpException(ErrorCode.SESSION_INVALID, "Unknown or foreign session")
        }
        return s
    }

    fun terminate(sessionId: String, connectionId: String): Boolean {
        val ended = synchronized(this) {
            val s = current
            if (s != null && s.id == sessionId && s.ownerConnectionId == connectionId) {
                current = null
                s
            } else null
        }
        if (ended != null) notifyEnded(ended, SessionEndReason.TERMINATED)
        return ended != null
    }

    fun endForConnection(connectionId: String, reason: SessionEndReason): Session? {
        val ended = synchronized(this) {
            val s = current
            if (s != null && s.ownerConnectionId == connectionId) {
                current = null
                s
            } else null
        }
        if (ended != null) notifyEnded(ended, reason)
        return ended
    }

    /**
     * Detaches the session owned by [connectionId] if [graceMs] > 0 (keeping playback running),
     * or ends it immediately if [graceMs] <= 0.
     */
    fun detachOrEndForConnection(connectionId: String, graceMs: Long): Session? {
        if (graceMs <= 0) return endForConnection(connectionId, SessionEndReason.CONNECTION_CLOSED)
        return synchronized(this) {
            val s = current
            if (s != null && s.ownerConnectionId == connectionId && !s.isDetached) {
                val detached = s.copy(detachedAtMs = time.nowMs())
                current = detached
                detached
            } else null
        }
    }

    /** Ends any active or detached session owned by [senderId] (used on revocation). */
    fun endForSender(senderId: String, reason: SessionEndReason): Session? {
        val ended = synchronized(this) {
            val s = current
            if (s != null && s.ownerSenderId == senderId) {
                current = null
                s
            } else null
        }
        if (ended != null) notifyEnded(ended, reason)
        return ended
    }

    /** Ends a detached session if it has been detached for at least [graceMs]. */
    fun sweepDetached(graceMs: Long): Session? {
        if (graceMs <= 0) return null
        val now = time.nowMs()
        val ended = synchronized(this) {
            val s = current
            val detachedAt = s?.detachedAtMs
            if (s != null && detachedAt != null && now - detachedAt >= graceMs) {
                current = null
                s
            } else null
        }
        if (ended != null) notifyEnded(ended, SessionEndReason.CONNECTION_CLOSED)
        return ended
    }

    fun endAll(reason: SessionEndReason) {
        val ended = synchronized(this) {
            val s = current
            current = null
            s
        }
        if (ended != null) notifyEnded(ended, reason)
    }

    private fun notifyEnded(s: Session, reason: SessionEndReason) {
        listeners.forEach { it.onSessionEnded(s, reason) }
    }
}
