package com.universalcast.updater

import org.json.JSONObject

/** A release that can be installed: the APK asset chosen for this app out of a GitHub release. */
data class ReleaseInfo(
    val version: String,
    val tag: String,
    val notes: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** Lower-case hex SHA-256 from GitHub's asset digest, when GitHub provides one. */
    val sha256: String?,
)

/** Dotted numeric version ("v1.2.3", "1.2", "1.2.3-debug"). Anything after a '-' or '+' is ignored. */
object Versions {
    fun parse(raw: String): List<Int>? {
        val core = raw.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        val parts = core.split('.').map { it.toIntOrNull() ?: return null }
        return if (parts.any { it < 0 }) null else parts
    }

    /** True only if [candidate] is strictly newer than [current]. Unparseable versions are never newer. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

object ReleaseParser {
    /**
     * Reads a GitHub "latest release" response and picks the `.apk` asset whose name starts with [assetPrefix]
     * (case-insensitive). Returns null for drafts/prereleases, or when there is no matching APK.
     */
    fun parse(json: String, assetPrefix: String): ReleaseInfo? {
        val root = JSONObject(json)
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
        val tag = root.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true) || !name.startsWith(assetPrefix, ignoreCase = true)) continue
            if (a.optString("state", "uploaded") != "uploaded") continue
            val url = a.optString("browser_download_url").takeIf { it.isNotBlank() } ?: continue
            val digest = a.optString("digest", "").lowercase().takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            return ReleaseInfo(
                version = tag.removePrefix("v").removePrefix("V"),
                tag = tag,
                notes = root.optString("body").trim(),
                assetName = name,
                downloadUrl = url,
                sizeBytes = a.optLong("size", -1),
                sha256 = digest,
            )
        }
        return null
    }
}

/** Only these hosts may serve an update (all over https), including after redirects. */
object TrustedHosts {
    fun allowed(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "github.com" || h == "api.github.com" || h.endsWith(".githubusercontent.com")
    }
}
