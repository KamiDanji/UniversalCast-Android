package com.universalcast.sender

import com.universalcast.playback.MediaHeaders
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** An HLS subtitle rendition announced by a master playlist (`#EXT-X-MEDIA:TYPE=SUBTITLES`). */
data class HlsSubtitleTrack(val uri: String, val language: String?, val name: String?)

enum class ProbeOutcome {
    /** The content was fetched and recognised. */
    OK,
    /** Fetched, but it is an HTML page, not a stream. */
    WEB_PAGE,
    /** Fetched, but the content is not recognisable as media. */
    NOT_MEDIA,
    /** The server answered with a non-2xx status. */
    HTTP_ERROR,
    /** Network failure or timeout: the stream may still work on the TV ("unverified"). */
    FAILED,
}

/** What the phone learned about a candidate by fetching its beginning with the page's headers. */
data class ProbeResult(
    val outcome: ProbeOutcome,
    val kind: MediaKind? = null,
    val httpStatus: Int? = null,
    val hlsMaster: Boolean = false,
    val variants: List<HlsVariant> = emptyList(),
    val subtitleTracks: List<HlsSubtitleTrack> = emptyList(),
    val durationSec: Double? = null,
    /** True for a live stream (HLS without #EXT-X-ENDLIST, dynamic DASH). Null if unknown. */
    val live: Boolean? = null,
    /** DRM (Widevine/FairPlay/PlayReady, SAMPLE-AES): cannot be cast by any method here. */
    val isProtected: Boolean = false,
    val sizeBytes: Long? = null,
) {
    /** False if the probe could not confirm the stream; it stays castable but is shown as "unverified". */
    val verified: Boolean get() = outcome == ProbeOutcome.OK

    /** A short user-facing explanation for anything but a clean result. */
    val problem: String?
        get() = when (outcome) {
            ProbeOutcome.OK -> if (isProtected) "DRM-protected: this stream can't be cast." else null
            ProbeOutcome.WEB_PAGE -> "This is a web page, not a stream."
            ProbeOutcome.NOT_MEDIA -> "The server didn't return a playable stream."
            ProbeOutcome.HTTP_ERROR -> "The server refused it (HTTP $httpStatus); the TV will probably be refused too."
            ProbeOutcome.FAILED -> "Couldn't check this stream from the phone (unverified)."
        }
}

/**
 * Fetches the start of a candidate stream with the headers the TV will send, using only [HttpURLConnection].
 * At most [maxBytes] are read per request, with [timeoutMs] connect/read timeouts. Redirects (also http↔https) are
 * followed manually, up to 5 hops; the Cookie header is only sent to the original host, never to a redirect target.
 * Content decides, not the URL: `#EXTM3U` → HLS, `<MPD` → DASH, HTML → web page. A failure is never thrown.
 * Note: fetching consumes one request; one-time signed URLs may stop working afterwards.
 */
class StreamProbe(
    private val timeoutMs: Int = 5_000,
    private val maxBytes: Int = 256 * 1024,
) {
    fun probe(url: String, headers: Map<String, String>): ProbeResult {
        val first = try {
            fetch(url, headers, ranged = MediaSniffer.classify(url).let { it == MediaKind.PROGRESSIVE || it == MediaKind.AUDIO })
        } catch (e: Exception) {
            return ProbeResult(ProbeOutcome.FAILED)
        }
        if (first.status !in 200..299) return ProbeResult(ProbeOutcome.HTTP_ERROR, httpStatus = first.status)
        val text = first.text()
        val head = text.trimStart('﻿', ' ', '\r', '\n', '\t')
        return when {
            head.startsWith("#EXTM3U") -> hls(first, head, headers)
            head.startsWith("<?xml") && head.contains("<MPD") || head.startsWith("<MPD") -> dash(first, head)
            isHtml(head) -> ProbeResult(ProbeOutcome.WEB_PAGE, httpStatus = first.status)
            else -> binary(first)
        }
    }

    // ---- HLS ---------------------------------------------------------------------------------------------

    private fun hls(f: Fetched, text: String, headers: Map<String, String>): ProbeResult {
        if (!text.contains("#EXT-X-STREAM-INF")) {
            return ProbeResult(
                ProbeOutcome.OK, MediaKind.HLS, f.status,
                durationSec = HlsParser.durationSec(text), live = !text.contains("#EXT-X-ENDLIST"), isProtected = HlsParser.isProtected(text),
            )
        }
        val variants = HlsParser.variants(text, f.finalUrl)
        val subs = HlsParser.subtitleTracks(text, f.finalUrl)
        var protected = HlsParser.isProtected(text) // #EXT-X-SESSION-KEY
        var duration: Double? = null
        var live: Boolean? = null
        // Keys and segments live in the variant playlists; the smallest one is cheapest to read.
        variants.lastOrNull()?.let { v ->
            runCatching {
                val m = fetch(v.uri, headers, ranged = false)
                if (m.status in 200..299) {
                    val t = m.text()
                    if (t.trimStart().startsWith("#EXTM3U")) {
                        duration = HlsParser.durationSec(t)
                        live = !t.contains("#EXT-X-ENDLIST")
                        protected = protected || HlsParser.isProtected(t)
                    }
                }
            }
        }
        return ProbeResult(
            ProbeOutcome.OK, MediaKind.HLS, f.status, hlsMaster = true, variants = variants, subtitleTracks = subs,
            durationSec = duration, live = live, isProtected = protected,
        )
    }

    // ---- DASH --------------------------------------------------------------------------------------------

    private fun dash(f: Fetched, text: String): ProbeResult {
        val live = Regex("""<MPD[^>]*\btype\s*=\s*"dynamic"""").containsMatchIn(text)
        val duration = Regex("""mediaPresentationDuration\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)?.let { isoDurationSec(it) }
        return ProbeResult(
            ProbeOutcome.OK, MediaKind.DASH, f.status, durationSec = duration, live = live,
            isProtected = Regex("""<(\w+:)?ContentProtection\b""").containsMatchIn(text),
        )
    }

    // ---- progressive -------------------------------------------------------------------------------------

    private fun binary(f: Fetched): ProbeResult {
        val b = f.body
        val ct = f.contentType?.substringBefore(';')?.trim()?.lowercase()
        val sniffed = when {
            b.size >= 8 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte() ->
                if (ct?.startsWith("audio/") == true) MediaKind.AUDIO else MediaKind.PROGRESSIVE
            b.size >= 4 && (b[0].toInt() and 0xFF) == 0x1A && (b[1].toInt() and 0xFF) == 0x45 && (b[2].toInt() and 0xFF) == 0xDF && (b[3].toInt() and 0xFF) == 0xA3 -> MediaKind.PROGRESSIVE
            b.size >= 3 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte() -> MediaKind.AUDIO
            b.size >= 4 && String(b, 0, 4, Charsets.ISO_8859_1) in setOf("OggS", "fLaC", "RIFF") -> MediaKind.AUDIO
            ct == null -> null
            ct.contains("mpegurl") -> MediaKind.HLS
            ct == "application/dash+xml" -> MediaKind.DASH
            ct.startsWith("video/") -> MediaKind.PROGRESSIVE
            ct.startsWith("audio/") -> MediaKind.AUDIO
            else -> null
        } ?: return ProbeResult(ProbeOutcome.NOT_MEDIA, httpStatus = f.status)
        return ProbeResult(ProbeOutcome.OK, sniffed, f.status, sizeBytes = f.totalBytes)
    }

    // ---- HTTP --------------------------------------------------------------------------------------------

    private class Fetched(val status: Int, val contentType: String?, val totalBytes: Long?, val body: ByteArray, val finalUrl: String) {
        fun text() = String(body, Charsets.UTF_8)
    }

    private fun fetch(url: String, headers: Map<String, String>, ranged: Boolean): Fetched {
        val originalHost = URI(url).host?.lowercase()
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val u = URI(current)
            val scheme = u.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") throw IOException("Unsupported redirect scheme")
            val c = URL(current).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.useCaches = false
                for ((k, v) in headers) {
                    // Cookies are scoped to the stream's own host, exactly like on the TV.
                    if (k.equals(MediaHeaders.COOKIE, ignoreCase = true) && u.host?.lowercase() != originalHost) continue
                    c.setRequestProperty(k, v)
                }
                if (headers.keys.none { it.equals(MediaHeaders.USER_AGENT, ignoreCase = true) }) c.setRequestProperty(MediaHeaders.USER_AGENT, "Mozilla/5.0")
                if (ranged) c.setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
                val status = c.responseCode
                if (status in 300..399 && status != 304) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without Location")
                    current = u.resolve(loc.trim()).toString()
                    return@repeat
                }
                val total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: c.contentLengthLong.takeIf { it > 0 && status == 200 }
                val body = if (status in 200..299) c.inputStream.use { readUpTo(it, maxBytes) } else ByteArray(0)
                return Fetched(status, c.contentType, total, body, current)
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun isHtml(head: String): Boolean {
        val h = head.take(64).lowercase()
        return h.startsWith("<!doctype html") || h.startsWith("<html") || h.startsWith("<head") || h.startsWith("<body")
    }

    companion object {
        const val MAX_REDIRECTS = 5

        /** `PT1H2M3.5S` → seconds; null when unparseable. */
        fun isoDurationSec(s: String): Double? {
            val m = Regex("""^P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$""").matchEntire(s.trim()) ?: return null
            val (d, h, mi, se) = m.destructured
            return (d.toDoubleOrNull() ?: 0.0) * 86400 + (h.toDoubleOrNull() ?: 0.0) * 3600 + (mi.toDoubleOrNull() ?: 0.0) * 60 + (se.toDoubleOrNull() ?: 0.0)
        }
    }
}

/** Pure HLS playlist parsing (RFC 8216 subset). */
object HlsParser {
    private const val WIDEVINE = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
    private const val FAIRPLAY = "com.apple.streamingkeydelivery"
    private const val PLAYREADY = "com.microsoft.playready"

    /** Quality levels of a master playlist, highest first, resolved against [baseUrl]; only http(s) URIs. */
    fun variants(playlist: String, baseUrl: String): List<HlsVariant> {
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return emptyList()
        val lines = playlist.lineSequence().map { it.trim() }.toList()
        val out = ArrayList<HlsVariant>()
        for ((i, line) in lines.withIndex()) {
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val attrs = attributes(line.removePrefix("#EXT-X-STREAM-INF:"))
            val uriLine = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val resolved = resolve(base, uriLine) ?: continue
            val res = attrs["RESOLUTION"]?.split('x', 'X')
            out += HlsVariant(resolved, attrs["BANDWIDTH"]?.toLongOrNull(), res?.getOrNull(0)?.toIntOrNull(), res?.getOrNull(1)?.toIntOrNull())
        }
        return out.distinctBy { it.uri }.sortedWith(compareByDescending<HlsVariant> { it.height ?: 0 }.thenByDescending { it.bandwidth ?: 0 })
    }

    /** `#EXT-X-MEDIA:TYPE=SUBTITLES` renditions with a URI. */
    fun subtitleTracks(playlist: String, baseUrl: String): List<HlsSubtitleTrack> {
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return emptyList()
        return playlist.lineSequence().map { it.trim() }.filter { it.startsWith("#EXT-X-MEDIA:") }.mapNotNull { line ->
            val a = attributes(line.removePrefix("#EXT-X-MEDIA:"))
            if (a["TYPE"] != "SUBTITLES") return@mapNotNull null
            val uri = a["URI"]?.let { resolve(base, it) } ?: return@mapNotNull null
            HlsSubtitleTrack(uri, a["LANGUAGE"], a["NAME"])
        }.distinctBy { it.uri }.toList()
    }

    /** Sum of `#EXTINF` durations in seconds, or null if there are none. */
    fun durationSec(playlist: String): Double? {
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

    /**
     * DRM: SAMPLE-AES(-CTR) encryption or a FairPlay / Widevine / PlayReady key format in `#EXT-X-KEY` or
     * `#EXT-X-SESSION-KEY`. Plain AES-128 (a clear key fetched over HTTP) is not DRM: ExoPlayer plays it.
     */
    fun isProtected(playlist: String): Boolean = playlist.lineSequence().map { it.trim() }.any { line ->
        if (!line.startsWith("#EXT-X-KEY:") && !line.startsWith("#EXT-X-SESSION-KEY:")) return@any false
        val a = attributes(line.substringAfter(':'))
        val method = a["METHOD"]?.uppercase()
        val format = a["KEYFORMAT"]?.lowercase()
        method == "SAMPLE-AES" || method == "SAMPLE-AES-CTR" ||
            format == FAIRPLAY || format == WIDEVINE || format == PLAYREADY ||
            a["URI"]?.lowercase()?.startsWith("skd://") == true
    }

    private fun resolve(base: URI, ref: String): String? {
        val r = runCatching { base.resolve(ref.trim()).toString() }.getOrNull() ?: return null
        return r.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /** `KEY=value,KEY="quoted, value"` → map. */
    fun attributes(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""").findAll(s).forEach { m -> out[m.groupValues[1]] = m.groupValues[2].trim('"') }
        return out
    }
}
