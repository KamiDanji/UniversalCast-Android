package com.universalcast.session

import com.universalcast.security.Bytes
import com.universalcast.security.SecureRandoms
import com.universalcast.security.TimeSource
import com.universalcast.ucp.ErrorCode
import com.universalcast.ucp.UcpException
import java.util.concurrent.CopyOnWriteArrayList

data class Session(val id: String, val ownerConnectionId: String, val ownerSenderId: String, val createdAtMs: Long)

enum class SessionEndReason { TERMINATED, REPLACED, CONNECTION_CLOSED, REVOKED, SHUTDOWN }

fun interface SessionListener {
    fun onSessionEnded(session: Session, reason: SessionEndReason)
}

/**
 * The receiver has one playback endpoint, hence at most one active session ("last controller wins").
 * Creating a session while another exists ends the old one with [SessionEndReason.REPLACED].
 */
class SessionManager(private val time: TimeSource) {
    class CreateResult(val session: Session, val replaced: Session?)

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
        val old = synchronized(this) {
            val previous = current
            current = created
            previous
        }
        if (old != null) notifyEnded(old, SessionEndReason.REPLACED)
        return CreateResult(created, old)
    }

    @Synchronized
    fun active(): Session? = current

    /** Returns the session if [sessionId] is the active session and is owned by [connectionId]. */
    fun validate(sessionId: String?, connectionId: String): Session {
        if (sessionId == null) throw UcpException(ErrorCode.SESSION_REQUIRED, "A session id is required")
        val s = active()
        if (s == null || s.id != sessionId || s.ownerConnectionId != connectionId) {
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
