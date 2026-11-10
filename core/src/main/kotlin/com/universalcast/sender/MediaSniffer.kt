package com.universalcast.sender

import java.net.URI

/** What kind of media a URL points to. */
enum class MediaKind(val mimeType: String?, val label: String) {
    HLS("application/x-mpegURL", "HLS stream"),
    DASH("application/dash+xml", "DASH stream"),
    VIDEO(null, "Video file"),
    AUDIO(null, "Audio file"),
}

/** One quality level of an HLS master playlist. */
data class HlsVariant(val uri: String, val bandwidth: Long?, val width: Int?, val height: Int?) {
    val label: String
        get() = buildString {
            append(if (height != null) "${height}p" else "Unknown resolution")
            if (bandwidth != null) append(" · %.1f Mbit/s".format(bandwidth / 1_000_000.0))
        }
}

/**
 * Recognises casting candidates among the requests a web page makes, and inspects what they return.
 *
 * Detection is by URL first (cheap, works for every request the page makes), then confirmed by content: the first
 * bytes of an HLS playlist are always `#EXTM3U`, and a DASH manifest is an `<MPD` document.
 */
object MediaSniffer {
    private val VIDEO_EXT = setOf("mp4", "m4v", "webm", "mkv", "mov", "ogv")
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav")

    /**
     * Segment and chunk formats: they are pieces of a stream, never something to cast on their own. Excluded even when
     * a URL also mentions ".mp4" somewhere (e.g. `.../seg-1.m4s?f=x.mp4`).
     */
    private val SEGMENT_EXT = setOf("ts", "m4s", "m4f", "cmfv", "cmfa", "key", "vtt", "srt", "jpg", "jpeg", "png", "webp", "gif", "js", "css", "json")

    /** Classifies [url] by its path (and, for signed/proxied links, its query), or null if it isn't media. */
    fun classifyUrl(url: String): MediaKind? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val pathExt = extension(u.rawPath)
        if (pathExt in SEGMENT_EXT) return null
        fromExtension(pathExt)?.let { return it }
        // Proxies and CDNs often hide the real file in the query: ".../play?file=ep1.m3u8" or "...?type=m3u8".
        val q = u.rawQuery?.lowercase() ?: return null
        return when {
            Regex("""\.m3u8(\b|$)|[=&]m3u8(\b|$)|format=hls""").containsMatchIn(q) -> MediaKind.HLS
            Regex("""\.mpd(\b|$)|format=dash""").containsMatchIn(q) -> MediaKind.DASH
            else -> null
        }
    }

    private fun extension(path: String?): String? {
        val last = path?.substringAfterLast('/')?.takeIf { '.' in it } ?: return null
        return last.substringAfterLast('.').lowercase().takeIf { it.isNotEmpty() && it.length <= 5 }
    }

    private fun fromExtension(ext: String?) = when (ext) {
        "m3u8", "m3u" -> MediaKind.HLS
        "mpd" -> MediaKind.DASH
        in VIDEO_EXT -> MediaKind.VIDEO
        in AUDIO_EXT -> MediaKind.AUDIO
        else -> null
    }

    /**
     * Decides what a response actually is from its Content-Type and first bytes. Content beats the URL: plenty of
     * players serve HLS from extension-less URLs, and a ".mp4" link sometimes returns an HTML error page.
     * Returns null for something that is not playable (HTML, JSON, an error page…).
     */
    fun classifyContent(contentType: String?, head: ByteArray): MediaKind? {
        val text = String(head, 0, minOf(head.size, 512), Charsets.ISO_8859_1).trimStart('﻿', 'ï', '»', '¿', ' ', '\r', '\n', '\t')
        if (text.startsWith("#EXTM3U")) return MediaKind.HLS
        if (text.startsWith("<?xml") || text.startsWith("<MPD")) return if (text.contains("<MPD")) MediaKind.DASH else null
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase()
        return when {
            ct == null -> null
            ct.contains("mpegurl") -> MediaKind.HLS
            ct == "application/dash+xml" -> MediaKind.DASH
            ct.startsWith("video/") -> MediaKind.VIDEO
            ct.startsWith("audio/") -> MediaKind.AUDIO
            else -> null
        }
    }

    /**
     * Parses an HLS *master* playlist into its quality levels, highest first, with URIs resolved against [baseUrl].
     * Returns an empty list for a media playlist (one quality only) or anything unparseable.
     */
    fun hlsVariants(playlist: String, baseUrl: String): List<HlsVariant> {
        if (!playlist.trimStart().startsWith("#EXTM3U")) return emptyList()
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return emptyList()
        val lines = playlist.lineSequence().map { it.trim() }.toList()
        val out = ArrayList<HlsVariant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attrs = attributes(line.removePrefix("#EXT-X-STREAM-INF:"))
                val uriLine = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                if (uriLine != null) {
                    val resolved = runCatching { base.resolve(uriLine).toString() }.getOrNull()
                    if (resolved != null && (resolved.startsWith("http://") || resolved.startsWith("https://"))) {
                        val res = attrs["RESOLUTION"]?.split('x', 'X')
                        out += HlsVariant(
                            uri = resolved,
                            bandwidth = attrs["BANDWIDTH"]?.toLongOrNull(),
                            width = res?.getOrNull(0)?.toIntOrNull(),
                            height = res?.getOrNull(1)?.toIntOrNull(),
                        )
                    }
                }
            }
            i++
        }
        return out.distinctBy { it.uri }.sortedWith(compareByDescending<HlsVariant> { it.height ?: 0 }.thenByDescending { it.bandwidth ?: 0 })
    }

    /**
     * Total duration in seconds of an HLS *media* playlist (sum of `#EXTINF`), or null if it is a master playlist,
     * a live stream (no `#EXT-X-ENDLIST`) or not a playlist at all. Used to tell an episode from a 30-second ad.
     */
    fun hlsDurationSec(playlist: String): Double? {
        if (!playlist.trimStart().startsWith("#EXTM3U") || !playlist.contains("#EXT-X-ENDLIST")) return null
        var total = 0.0
        var any = false
        for (line in playlist.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("#EXTINF:")) {
                t.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { total += it; any = true }
            }
        }
        return if (any) total else null
    }

    /** `KEY=value,KEY="quoted, value"` → map. */
    private fun attributes(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""").findAll(s).forEach { m ->
            out[m.groupValues[1]] = m.groupValues[2].trim('"')
        }
        return out
    }
}
