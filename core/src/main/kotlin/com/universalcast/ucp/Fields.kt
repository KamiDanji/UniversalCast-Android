package com.universalcast.ucp

import java.util.Base64

/** Typed, bounds-checked accessor over a decoded JSON object. Every failure is an INVALID_PAYLOAD [UcpException]. */
class Fields(val raw: Map<String, Any?>) {
    private fun bad(key: String, why: String): Nothing =
        throw UcpException(ErrorCode.INVALID_PAYLOAD, "Field '$key' $why")

    fun has(key: String) = raw.containsKey(key) && raw[key] != null

    fun optString(key: String, maxLength: Int = 2048, minLength: Int = 1): String? {
        val v = raw[key] ?: return null
        if (v !is String) bad(key, "must be a string")
        if (v.length < minLength || v.length > maxLength) bad(key, "has invalid length")
        return v
    }

    fun string(key: String, maxLength: Int = 2048, minLength: Int = 1): String =
        optString(key, maxLength, minLength) ?: bad(key, "is required")

    fun optLong(key: String, min: Long, max: Long): Long? {
        val v = raw[key] ?: return null
        if (v !is Long) bad(key, "must be an integer")
        if (v < min || v > max) bad(key, "is out of range")
        return v
    }

    fun long(key: String, min: Long, max: Long): Long = optLong(key, min, max) ?: bad(key, "is required")

    fun optDouble(key: String, min: Double, max: Double): Double? {
        val v = raw[key] ?: return null
        val d = when (v) {
            is Long -> v.toDouble()
            is Double -> v
            else -> bad(key, "must be a number")
        }
        if (!d.isFinite() || d < min || d > max) bad(key, "is out of range")
        return d
    }

    fun double(key: String, min: Double, max: Double): Double = optDouble(key, min, max) ?: bad(key, "is required")

    fun optBool(key: String): Boolean? {
        val v = raw[key] ?: return null
        if (v !is Boolean) bad(key, "must be a boolean")
        return v
    }

    fun bool(key: String): Boolean = optBool(key) ?: bad(key, "is required")

    /** Base64url (no padding) binary field with exact or bounded length. */
    fun bytes(key: String, exactLength: Int? = null, maxLength: Int = 1024): ByteArray {
        val s = string(key, maxLength = maxLength * 2 + 4)
        val decoded = try {
            Base64.getUrlDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            bad(key, "is not valid base64url")
        }
        if (exactLength != null && decoded.size != exactLength) bad(key, "must be $exactLength bytes")
        if (decoded.isEmpty() || decoded.size > maxLength) bad(key, "has invalid size")
        return decoded
    }

    fun optObj(key: String): Fields? {
        val v = raw[key] ?: return null
        @Suppress("UNCHECKED_CAST")
        val m = v as? Map<String, Any?> ?: bad(key, "must be an object")
        return Fields(m)
    }

    fun obj(key: String): Fields = optObj(key) ?: bad(key, "is required")

    fun longList(key: String, maxItems: Int, min: Long, max: Long): List<Long> {
        val v = raw[key] ?: bad(key, "is required")
        if (v !is List<*>) bad(key, "must be an array")
        if (v.isEmpty() || v.size > maxItems) bad(key, "has invalid item count")
        return v.map { item ->
            if (item !is Long || item < min || item > max) bad(key, "contains an invalid item")
            item
        }
    }

    fun stringList(key: String, maxItems: Int, maxItemLength: Int): List<String> {
        val v = raw[key] ?: bad(key, "is required")
        if (v !is List<*>) bad(key, "must be an array")
        if (v.size > maxItems) bad(key, "has too many items")
        return v.map { item ->
            if (item !is String || item.isEmpty() || item.length > maxItemLength) bad(key, "contains an invalid item")
            item
        }
    }
}
