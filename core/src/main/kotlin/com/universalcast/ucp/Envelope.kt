package com.universalcast.ucp

typealias JsonObject = Map<String, Any?>

data class UcpError(val code: ErrorCode, val message: String)

/**
 * One UCP message. Wire form (JSON object):
 *  request : {"version":1,"type":"request","id":"r1","seq":5,"session":"...","method":"playback.seek","payload":{...}}
 *  response: {"version":1,"type":"response","id":"r1","seq":12,"ok":true,"payload":{...}}
 *            {"version":1,"type":"response","id":"r1","seq":12,"ok":false,"error":{"code":"FORBIDDEN","message":"..."}}
 *  event   : {"version":1,"type":"event","seq":13,"event":"playback.stateChanged","payload":{...}}
 *
 * Client request sequence numbers start at 1 and must strictly increase per connection.
 * Server sequence numbers (responses and events) are a separate strictly increasing counter.
 */
data class Envelope(
    val type: MessageType,
    val id: String?,
    val seq: Long,
    val session: String? = null,
    val name: String? = null,
    val payload: JsonObject = emptyMap(),
    val ok: Boolean? = null,
    val error: UcpError? = null,
    val version: Int = Ucp.VERSION,
) {
    companion object {
        fun request(id: String, seq: Long, method: String, payload: JsonObject = emptyMap(), session: String? = null) =
            Envelope(MessageType.REQUEST, id, seq, session, method, payload)

        fun response(id: String, seq: Long, payload: JsonObject = emptyMap()) =
            Envelope(MessageType.RESPONSE, id, seq, ok = true, payload = payload)

        fun errorResponse(id: String?, seq: Long, error: UcpError) =
            Envelope(MessageType.RESPONSE, id, seq, ok = false, error = error)

        fun event(seq: Long, event: String, payload: JsonObject = emptyMap()) =
            Envelope(MessageType.EVENT, null, seq, name = event, payload = payload)
    }
}

object EnvelopeCodec {
    private val ID_REGEX = Regex("^[A-Za-z0-9._:-]{1,${Ucp.MAX_ID_LENGTH}}$")

    fun isValidId(id: String) = ID_REGEX.matches(id)

    fun encode(env: Envelope): String {
        val m = LinkedHashMap<String, Any?>()
        m["version"] = env.version
        m["type"] = env.type.wire
        env.id?.let { m["id"] = it }
        m["seq"] = env.seq
        env.session?.let { m["session"] = it }
        when (env.type) {
            MessageType.REQUEST -> m["method"] = env.name
            MessageType.EVENT -> m["event"] = env.name
            MessageType.RESPONSE -> m["ok"] = env.ok == true
        }
        if (env.type == MessageType.RESPONSE && env.ok != true) {
            val e = env.error ?: UcpError(ErrorCode.INTERNAL, "Unspecified error")
            m["error"] = mapOf("code" to e.code.name, "message" to e.message)
        } else {
            m["payload"] = env.payload
        }
        return StrictJson.stringify(m)
    }

    /** Parses and validates an inbound message. Throws [UcpException] (never anything else) on bad input. */
    fun decode(text: String): Envelope {
        val root = try {
            StrictJson.parse(text)
        } catch (e: JsonException) {
            throw UcpException(ErrorCode.MALFORMED_MESSAGE, "Invalid JSON: ${e.message}")
        }
        @Suppress("UNCHECKED_CAST")
        val obj = root as? Map<String, Any?> ?: throw UcpException(ErrorCode.MALFORMED_MESSAGE, "Message must be a JSON object")
        val f = Fields(obj)

        val version = f.malformedIfInvalid { optLong("version", 0, Long.MAX_VALUE) }
            ?: throw UcpException(ErrorCode.MALFORMED_MESSAGE, "Missing 'version'")
        if (version != Ucp.VERSION.toLong()) {
            throw UcpException(ErrorCode.UNSUPPORTED_VERSION, "Unsupported protocol version $version")
        }
        val typeText = f.malformedIfInvalid { optString("type", 16) } ?: throw UcpException(ErrorCode.MALFORMED_MESSAGE, "Missing 'type'")
        val type = MessageType.fromWire(typeText) ?: throw UcpException(ErrorCode.MALFORMED_MESSAGE, "Unknown message type")
        val seq = f.malformedIfInvalid { optLong("seq", 0, Long.MAX_VALUE) } ?: throw UcpException(ErrorCode.MALFORMED_MESSAGE, "Missing 'seq'")

        val id = f.malformedIfInvalid { optString("id", Ucp.MAX_ID_LENGTH) }
        if (id != null && !isValidId(id)) throw UcpException(ErrorCode.MALFORMED_MESSAGE, "Invalid 'id'")
        val session = f.malformedIfInvalid { optString("session", Ucp.MAX_ID_LENGTH) }
        if (session != null && !isValidId(session)) throw UcpException(ErrorCode.MALFORMED_MESSAGE, "Invalid 'session'")

        val payload: JsonObject = f.malformedIfInvalid { optObj("payload") }?.raw ?: emptyMap()

        return when (type) {
            MessageType.REQUEST -> {
                if (id == null) throw UcpException(ErrorCode.MALFORMED_MESSAGE, "Request requires 'id'")
                if (seq < 1) throw UcpException(ErrorCode.MALFORMED_MESSAGE, "Request 'seq' must be >= 1")
                val method = f.malformedIfInvalid { optString("method", 64) }
                    ?: throw UcpException(ErrorCode.MALFORMED_MESSAGE, "Request requires 'method'")
                Envelope(type, id, seq, session, method, payload)
            }
            MessageType.EVENT -> {
                val ev = f.malformedIfInvalid { optString("event", 64) }
                    ?: throw UcpException(ErrorCode.MALFORMED_MESSAGE, "Event requires 'event'")
                Envelope(type, null, seq, session, ev, payload)
            }
            MessageType.RESPONSE -> {
                if (id == null) throw UcpException(ErrorCode.MALFORMED_MESSAGE, "Response requires 'id'")
                val ok = f.malformedIfInvalid { optBool("ok") } ?: throw UcpException(ErrorCode.MALFORMED_MESSAGE, "Response requires 'ok'")
                if (ok) {
                    Envelope(type, id, seq, session, null, payload, true, null)
                } else {
                    val e = f.malformedIfInvalid { optObj("error") }
                        ?: throw UcpException(ErrorCode.MALFORMED_MESSAGE, "Error response requires 'error'")
                    val codeText = e.malformedIfInvalid { optString("code", 64) } ?: ""
                    val msg = e.malformedIfInvalid { optString("message", 512) } ?: ""
                    Envelope(type, id, seq, session, null, emptyMap(), false, UcpError(ErrorCode.fromWire(codeText) ?: ErrorCode.INTERNAL, msg))
                }
            }
        }
    }

    /** Envelope-level field problems are MALFORMED_MESSAGE, not INVALID_PAYLOAD. */
    private inline fun <T> Fields.malformedIfInvalid(block: Fields.() -> T): T =
        try {
            block()
        } catch (e: UcpException) {
            throw UcpException(ErrorCode.MALFORMED_MESSAGE, e.message ?: "Malformed message")
        }
}
