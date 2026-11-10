package com.universalcast.sender.app.web

import com.universalcast.playback.MediaHeaders
import com.universalcast.sender.HlsVariant
import com.universalcast.sender.MediaKind
import com.universalcast.sender.MediaSniffer
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** What the phone learned by fetching the start of a candidate with the page's own headers. */
data class ProbeResult(
    val kind: MediaKind?,          // null: not playable (HTML/error page) — hide it
    val variants: List<HlsVariant> = emptyList(),
    val durationSec: Double? = null,
    val sizeBytes: Long? = null,
    val httpStatus: Int? = null,
) {
    /** The server answered but refused (403, 404…): the TV would most likely be refused too. */
    val refused: Boolean get() = httpStatus != null && httpStatus !in 200..299
}

/**
 * Fetches just enough of a stream to know what it is: the whole playlist for HLS (small text), otherwise the first
 * kilobyte with a Range request. Runs on a background thread. The same headers the TV will send are used, so a link
 * that fails here (403) would most likely fail on the TV too.
 */
object StreamProbe {
    private const val MAX_PLAYLIST = 512 * 1024

    fun probe(url: String, headers: Map<String, String>): ProbeResult {
        val (status, contentType, total, body) = fetch(url, headers, ranged = MediaSniffer.classifyUrl(url) != MediaKind.HLS)
        if (status !in 200..299) return ProbeResult(null, httpStatus = status)
        val kind = MediaSniffer.classifyContent(contentType, body) ?: return ProbeResult(null, httpStatus = status)
        if (kind != MediaKind.HLS) return ProbeResult(kind, sizeBytes = total, httpStatus = status)

        val text = String(body, Charsets.UTF_8)
        val variants = MediaSniffer.hlsVariants(text, url)
        val duration = if (variants.isEmpty()) {
            MediaSniffer.hlsDurationSec(text)
        } else {
            // Duration lives in the variant playlists; the smallest one is cheapest to read.
            runCatching {
                val small = variants.last()
                val v = fetch(small.uri, headers, ranged = false)
                if (v.status in 200..299) MediaSniffer.hlsDurationSec(String(v.body, Charsets.UTF_8)) else null
            }.getOrNull()
        }
        return ProbeResult(kind, variants, duration, httpStatus = status)
    }

    private data class Fetched(val status: Int, val contentType: String?, val totalBytes: Long?, val body: ByteArray)

    private fun fetch(url: String, headers: Map<String, String>, ranged: Boolean): Fetched {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.instanceFollowRedirects = true
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (headers[MediaHeaders.USER_AGENT] == null) c.setRequestProperty(MediaHeaders.USER_AGENT, "Mozilla/5.0")
            if (ranged) c.setRequestProperty("Range", "bytes=0-1023")
            val status = c.responseCode
            val total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: c.contentLengthLong.takeIf { it > 0 && status == 200 } // 200: the server ignored Range, so this is the full size
            val limit = if (ranged) 1024 else MAX_PLAYLIST
            val body = if (status in 200..299) (c.inputStream).use { readUpTo(it, limit) } else ByteArray(0)
            return Fetched(status, c.contentType, total, body)
        } finally {
            c.disconnect()
        }
    }

    private fun readUpTo(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
