package com.universalcast.ucp

/** Constants of UniversalCast Protocol (UCP) 1.0. */
object Ucp {
    /** Wire protocol version carried in every envelope. UCP 1.0 == 1. */
    const val VERSION = 1
    val SUPPORTED_VERSIONS: List<Int> = listOf(1)

    /** Hard limit for one inbound/outbound text message, in UTF-8 bytes. */
    const val MAX_MESSAGE_BYTES = 64 * 1024
    const val MAX_ID_LENGTH = 64
    const val WS_PATH = "/ucp"
    const val SERVICE_TYPE = "_universalcast._tcp"
    const val SERVICE_TYPE_FQDN = "_universalcast._tcp.local."

    const val PAIRING_WINDOW_MS = 120_000L
    const val AUTH_TIMEOUT_MS = 10_000L
    const val CHALLENGE_TTL_MS = 10_000L
    const val DUPLICATE_CACHE_SIZE = 256
    const val MAX_MALFORMED_MESSAGES = 5
    const val MAX_UNAUTHENTICATED_CONNECTIONS = 16

    // Application close codes (RFC 6455 allows 4000-4999 for private use).
    const val CLOSE_NORMAL = 1000
    const val CLOSE_POLICY = 1008
    const val CLOSE_TOO_LARGE = 1009
    const val CLOSE_TRY_LATER = 1013
    const val CLOSE_AUTH_FAILED = 4001
    const val CLOSE_AUTH_TIMEOUT = 4002
    const val CLOSE_REVOKED = 4003
    const val CLOSE_PROTOCOL_ERROR = 4004
    const val CLOSE_SHUTDOWN = 4005
}

enum class MessageType(val wire: String) {
    REQUEST("request"), RESPONSE("response"), EVENT("event");

    companion object {
        fun fromWire(s: String): MessageType? = values().firstOrNull { it.wire == s }
    }
}

enum class ErrorCode {
    MALFORMED_MESSAGE, UNSUPPORTED_VERSION, INVALID_PAYLOAD, UNKNOWN_METHOD,
    UNAUTHENTICATED, AUTH_FAILED, AUTH_TIMEOUT, REPLAY_DETECTED, FORBIDDEN,
    SEQUENCE_ERROR, INVALID_STATE, SESSION_REQUIRED, SESSION_INVALID,
    UNSUPPORTED_MEDIA, PLAYBACK_ERROR,
    PAIRING_NOT_ACTIVE, PAIRING_EXPIRED, PAIRING_INVALID_PROOF, PAIRING_REJECTED,
    MESSAGE_TOO_LARGE, TOO_MANY_CONNECTIONS, INTERNAL;

    companion object {
        fun fromWire(s: String): ErrorCode? = values().firstOrNull { it.name == s }
    }
}

object Method {
    const val AUTH_HELLO = "auth.hello"
    const val AUTH_RESPONSE = "auth.response"
    const val PAIR_REQUEST = "pair.request"
    const val PAIR_CONFIRM = "pair.confirm"
    const val PAIR_CANCEL = "pair.cancel"
    const val PING = "ping"
    const val CAPABILITIES_GET = "capabilities.get"
    const val SESSION_CREATE = "session.create"
    const val SESSION_TERMINATE = "session.terminate"
    const val MEDIA_LOAD = "media.load"
    const val PLAYBACK_PLAY = "playback.play"
    const val PLAYBACK_PAUSE = "playback.pause"
    const val PLAYBACK_RESUME = "playback.resume"
    const val PLAYBACK_STOP = "playback.stop"
    const val PLAYBACK_SEEK = "playback.seek"
    const val PLAYBACK_SET_SPEED = "playback.setSpeed"
    const val PLAYBACK_SET_VOLUME = "playback.setVolume"
    const val PLAYBACK_SNAPSHOT = "playback.snapshot"
}

object EventName {
    const val PLAYBACK_STATE_CHANGED = "playback.stateChanged"
    const val SESSION_TERMINATED = "session.terminated"
    const val PAIR_RESULT = "pair.result"
}

/** A protocol-level failure that maps directly to an error response. */
class UcpException(val code: ErrorCode, message: String, val closeCode: Int? = null) : RuntimeException(message)
