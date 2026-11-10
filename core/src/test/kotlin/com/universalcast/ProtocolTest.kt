package com.universalcast

import com.universalcast.ucp.*
import org.junit.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrictJsonTest {
    @Test fun parsesBasicDocument() {
        val v = StrictJson.parse("""{"a":1,"b":[true,null,"x\u00e9\n"],"c":{"d":-2.5e1}}""") as Map<*, *>
        assertEquals(1L, v["a"])
        assertEquals(listOf(true, null, "xé\n"), v["b"])
        assertEquals(-25.0, (v["c"] as Map<*, *>)["d"])
    }

    @Test fun rejectsDuplicateKeys() {
        assertFailsWith<JsonException> { StrictJson.parse("""{"a":1,"a":2}""") }
    }

    @Test fun rejectsTrailingDataAndGarbage() {
        for (bad in listOf("{} x", "{\"a\":1}}", "", "   ", "{", "[1,]", "{\"a\":}", "'a'", "{a:1}", "01", "1.", "-", "+1", "NaN", "\"\u0001\"", "\"\\x\"", "[\"a\" \"b\"]", "tru")) {
            assertFailsWith<JsonException>("should reject: $bad") { StrictJson.parse(bad) }
        }
    }

    @Test fun enforcesDepthLimit() {
        val deep = "[".repeat(40) + "]".repeat(40)
        assertFailsWith<JsonException> { StrictJson.parse(deep) }
        StrictJson.parse("[".repeat(10) + "]".repeat(10))
    }

    @Test fun integerOverflowIsRejected() {
        assertFailsWith<JsonException> { StrictJson.parse("99999999999999999999") }
    }

    @Test fun stringifyRoundTrips() {
        val original = linkedMapOf<String, Any?>("s" to "q\"\\\n\t\u0001é", "n" to 5L, "d" to 1.5, "l" to listOf(1L, null, false), "o" to mapOf("k" to "v"))
        assertEquals(original, StrictJson.parse(StrictJson.stringify(original)))
    }
}

class EnvelopeTest {
    private val validRequest = """{"version":1,"type":"request","id":"r1","seq":1,"method":"ping","payload":{"nonce":"x"}}"""

    @Test fun decodesRequest() {
        val e = EnvelopeCodec.decode(validRequest)
        assertEquals(MessageType.REQUEST, e.type)
        assertEquals("r1", e.id)
        assertEquals("ping", e.name)
        assertEquals(1L, e.seq)
        assertEquals("x", e.payload["nonce"])
    }

    @Test fun roundTripsAllTypes() {
        val req = Envelope.request("a-1", 7, "playback.seek", mapOf("positionMs" to 5000L), "sess1")
        assertEquals(req, EnvelopeCodec.decode(EnvelopeCodec.encode(req)))
        val ok = Envelope.response("a-1", 3, mapOf("x" to 1L))
        assertEquals(ok, EnvelopeCodec.decode(EnvelopeCodec.encode(ok)))
        val err = Envelope.errorResponse("a-1", 4, UcpError(ErrorCode.FORBIDDEN, "no"))
        assertEquals(err, EnvelopeCodec.decode(EnvelopeCodec.encode(err)))
        val ev = Envelope.event(9, "playback.stateChanged", mapOf("k" to "v"))
        assertEquals(ev, EnvelopeCodec.decode(EnvelopeCodec.encode(ev)))
    }

    private fun code(text: String): ErrorCode = assertFailsWith<UcpException> { EnvelopeCodec.decode(text) }.code

    @Test fun validationFailures() {
        assertEquals(ErrorCode.MALFORMED_MESSAGE, code("not json"))
        assertEquals(ErrorCode.MALFORMED_MESSAGE, code("[1,2]"))
        assertEquals(ErrorCode.MALFORMED_MESSAGE, code("""{"type":"request","id":"a","seq":1,"method":"ping"}"""))
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, code("""{"version":2,"type":"request","id":"a","seq":1,"method":"ping"}"""))
        assertEquals(ErrorCode.MALFORMED_MESSAGE, code("""{"version":"1","type":"request","id":"a","seq":1,"method":"ping"}"""))
        assertEquals(ErrorCode.MALFORMED_MESSAGE, code("""{"version":1,"type":"bogus","id":"a","seq":1,"method":"ping"}"""))
        assertEquals(ErrorCode.MALFORMED_MESSAGE, code("""{"version":1,"type":"request","seq":1,"method":"ping"}"""))
        assertEquals(ErrorCode.MALFORMED_MESSAGE, code("""{"version":1,"type":"request","id":"a","seq":0,"method":"ping"}"""))
        assertEquals(ErrorCode.MALFORMED_MESSAGE, code("""{"version":1,"type":"request","id":"a","seq":-4,"method":"ping"}"""))
        assertEquals(ErrorCode.MALFORMED_MESSAGE, code("""{"version":1,"type":"request","id":"a b","seq":1,"method":"ping"}"""))
        assertEquals(ErrorCode.MALFORMED_MESSAGE, code("""{"version":1,"type":"request","id":"${"x".repeat(65)}","seq":1,"method":"ping"}"""))
        assertEquals(ErrorCode.MALFORMED_MESSAGE, code("""{"version":1,"type":"request","id":"a","seq":1}"""))
        assertEquals(ErrorCode.MALFORMED_MESSAGE, code("""{"version":1,"type":"request","id":"a","seq":1,"method":"ping","payload":[1]}"""))
        assertEquals(ErrorCode.MALFORMED_MESSAGE, code("""{"version":1,"type":"request","id":"a","seq":1.5,"method":"ping"}"""))
        assertEquals(ErrorCode.MALFORMED_MESSAGE, code("""{"version":1,"type":"response","id":"a","seq":1}"""))
        assertEquals(ErrorCode.MALFORMED_MESSAGE, code("""{"version":1,"type":"response","id":"a","seq":1,"ok":false}"""))
    }

    @Test fun randomGarbageNeverEscapesAsAnotherException() {
        val rnd = Random(42)
        val alphabet = "{}[]\":,0123456789abcdefnrtu\\ -.eE\n\u0000é"
        repeat(5000) {
            val text = buildString { repeat(rnd.nextInt(80)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            try {
                EnvelopeCodec.decode(text)
            } catch (e: UcpException) {
            }
        }
        val base = """{"version":1,"type":"request","id":"r1","seq":1,"method":"ping","payload":{"nonce":"x"}}"""
        for (i in base.indices) for (c in listOf('"', '{', '}', ',', ':', '1', 'x', '\u0000')) {
            val mutated = base.substring(0, i) + c + base.substring(i + 1)
            try {
                EnvelopeCodec.decode(mutated)
            } catch (e: UcpException) {
            }
        }
    }

    @Test fun fieldAccessorsValidate() {
        val f = Fields(mapOf("s" to "abc", "n" to 5L, "d" to 2.5, "b" to true, "raw" to "AAEC", "l" to listOf(1L, 2L)))
        assertEquals("abc", f.string("s"))
        assertEquals(5L, f.long("n", 0, 10))
        assertEquals(2.5, f.double("d", 0.0, 3.0))
        assertEquals(5.0, f.double("n", 0.0, 9.0))
        assertTrue(f.bool("b"))
        assertEquals(3, f.bytes("raw").size)
        assertEquals(listOf(1L, 2L), f.longList("l", 4, 0, 5))
        assertNull(f.optString("missing"))
        for (bad in listOf<() -> Any?>(
            { f.string("n") }, { f.long("n", 6, 10) }, { f.long("s", 0, 1) }, { f.double("d", 3.0, 4.0) }, { f.bool("s") },
            { f.bytes("s", exactLength = 99) }, { f.string("missing") }, { f.longList("l", 1, 0, 5) }, { f.obj("s") },
        )) {
            assertEquals(ErrorCode.INVALID_PAYLOAD, assertFailsWith<UcpException> { bad() }.code)
        }
    }
}
