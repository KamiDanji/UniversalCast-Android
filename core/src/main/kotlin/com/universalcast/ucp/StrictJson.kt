package com.universalcast.ucp

/** Thrown when text is not valid JSON under the strict rules of [StrictJson]. */
class JsonException(message: String) : Exception(message)

/**
 * A small strict RFC 8259 JSON reader/writer.
 *
 * The protocol layer treats every inbound byte as hostile. This parser rejects duplicate object keys, trailing
 * data, unescaped control characters, excessive nesting and out-of-range integers, and behaves identically on
 * the JVM and on Android (the platform org.json does not).
 *
 * Values map to: Map<String, Any?> (object), List<Any?> (array), String, Long (integers), Double, Boolean, null.
 */
object StrictJson {
    const val DEFAULT_MAX_DEPTH = 12
    private const val MAX_MEMBERS = 512

    fun parse(text: String, maxDepth: Int = DEFAULT_MAX_DEPTH): Any? {
        val parser = Parser(text, maxDepth)
        parser.skipWhitespace()
        val value = parser.readValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonException("Unexpected data after JSON value at ${parser.position}")
        return value
    }

    fun stringify(value: Any?): String {
        val sb = StringBuilder()
        write(sb, value)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int -> sb.append(v)
            is Long -> sb.append(v)
            is Short -> sb.append(v.toInt())
            is Byte -> sb.append(v.toInt())
            is Double -> {
                require(v.isFinite()) { "Non-finite number cannot be encoded" }
                sb.append(v.toString())
            }
            is Float -> {
                require(v.isFinite()) { "Non-finite number cannot be encoded" }
                sb.append(v.toDouble().toString())
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    require(k is String) { "JSON object keys must be strings" }
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k)
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("Unsupported JSON value type: ${v::class.java.name}")
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                ch < ' ' -> sb.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String, private val maxDepth: Int) {
        var position = 0

        fun atEnd() = position >= s.length

        fun skipWhitespace() {
            while (position < s.length) {
                val c = s[position]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') position++ else break
            }
        }

        fun readValue(depth: Int): Any? {
            if (depth > maxDepth) throw JsonException("Nesting too deep")
            if (atEnd()) throw JsonException("Unexpected end of input")
            val c = s[position]
            return when {
                c == '{' -> readObject(depth)
                c == '[' -> readArray(depth)
                c == '"' -> readString()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c in '0'..'9' -> readNumber()
                else -> throw JsonException("Unexpected character at $position")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, position)) throw JsonException("Invalid literal at $position")
            position += word.length
            return value
        }

        private fun readObject(depth: Int): Any? {
            position++
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && s[position] == '}') {
                position++
                return map
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || s[position] != '"') throw JsonException("Expected object key at $position")
                val key = readString()
                skipWhitespace()
                if (atEnd() || s[position] != ':') throw JsonException("Expected ':' at $position")
                position++
                skipWhitespace()
                val value = readValue(depth + 1)
                if (map.containsKey(key)) throw JsonException("Duplicate key '$key'")
                if (map.size >= MAX_MEMBERS) throw JsonException("Too many object members")
                map[key] = value
                skipWhitespace()
                if (atEnd()) throw JsonException("Unterminated object")
                when (s[position]) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return map
                    }
                    else -> throw JsonException("Expected ',' or '}' at $position")
                }
            }
        }

        private fun readArray(depth: Int): Any? {
            position++
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && s[position] == ']') {
                position++
                return list
            }
            while (true) {
                skipWhitespace()
                list.add(readValue(depth + 1))
                if (list.size > MAX_MEMBERS) throw JsonException("Too many array items")
                skipWhitespace()
                if (atEnd()) throw JsonException("Unterminated array")
                when (s[position]) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return list
                    }
                    else -> throw JsonException("Expected ',' or ']' at $position")
                }
            }
        }

        private fun readString(): String {
            position++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("Unterminated string")
                val c = s[position++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) throw JsonException("Unterminated escape")
                        when (val e = s[position++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (position + 4 > s.length) throw JsonException("Bad unicode escape")
                                var code = 0
                                for (i in 0 until 4) {
                                    val d = Character.digit(s[position + i], 16)
                                    if (d < 0) throw JsonException("Bad unicode escape")
                                    code = code * 16 + d
                                }
                                position += 4
                                sb.append(code.toChar())
                            }
                            else -> throw JsonException("Invalid escape '\\$e'")
                        }
                    }
                    c < ' ' -> throw JsonException("Unescaped control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = position
            if (s[position] == '-') position++
            if (atEnd()) throw JsonException("Invalid number")
            if (s[position] == '0') {
                position++
            } else if (s[position] in '1'..'9') {
                while (!atEnd() && s[position] in '0'..'9') position++
            } else {
                throw JsonException("Invalid number at $position")
            }
            var integral = true
            if (!atEnd() && s[position] == '.') {
                integral = false
                position++
                val digitsStart = position
                while (!atEnd() && s[position] in '0'..'9') position++
                if (position == digitsStart) throw JsonException("Invalid fraction")
            }
            if (!atEnd() && (s[position] == 'e' || s[position] == 'E')) {
                integral = false
                position++
                if (!atEnd() && (s[position] == '+' || s[position] == '-')) position++
                val digitsStart = position
                while (!atEnd() && s[position] in '0'..'9') position++
                if (position == digitsStart) throw JsonException("Invalid exponent")
            }
            val text = s.substring(start, position)
            if (integral) {
                return text.toLongOrNull() ?: throw JsonException("Integer out of range")
            }
            val d = text.toDouble()
            if (!d.isFinite()) throw JsonException("Number out of range")
            return d
        }
    }
}
