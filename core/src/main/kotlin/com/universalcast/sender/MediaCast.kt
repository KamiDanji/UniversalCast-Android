package com.universalcast.sender

import com.universalcast.playback.MediaHeaders
import com.universalcast.playback.SubtitleInfo
import java.net.URI

/**
 * Everything a `media.load` can carry. [headers] must satisfy [MediaHeaders] (build them with [CastHeaders]);
 * [subtitles] at most 8 (see [com.universalcast.playback.SubtitleValidator]).
 */
data class MediaCast(
    val uri: String,
    val title: String? = null,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleInfo> = emptyList(),
    val autoplay: Boolean = true,
) {
    override fun toString() = "MediaCast(uri=$uri, title=$title, mimeType=$mimeType, headers=${headers.keys}, subtitles=${subtitles.size}, autoplay=$autoplay)"
}

/**
 * Builds the request headers the TV should send for a stream detected in the browser, the way the page's own player
 * sent them. WebView often doesn't report Referer/Origin/User-Agent for a request, and CDNs check them (some tie
 * their token to the exact User-Agent), so:
 * - Referer: the request's Referer if present, else the page URL;
 * - Origin: the request's Origin if present, else derived from the Referer when the stream is on another origin
 *   (that is when a browser's player would send one);
 * - User-Agent: the request's, else the WebView's own;
 * - Accept-Language: the request's, if present;
 * - Cookie: the browser cookie-store value for the media URL (the caller reads it at cast time).
 * Every value that would fail the receiver's [MediaHeaders] rules is dropped (never truncated), so the result is
 * always accepted.
 */
object CastHeaders {
    fun build(
        streamUrl: String,
        requestHeaders: Map<String, String>,
        pageUrl: String?,
        userAgent: String?,
        cookie: String? = null,
    ): Map<String, String> {
        fun h(name: String) = requestHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }
        val wanted = LinkedHashMap<String, String?>()
        val referer = (h(MediaHeaders.REFERER) ?: pageUrl)?.takeIf { isHttpUrl(it) }
        wanted[MediaHeaders.REFERER] = referer
        wanted[MediaHeaders.ORIGIN] = h(MediaHeaders.ORIGIN)?.takeIf { isHttpUrl(it) }
            ?: referer?.let { originOf(it) }?.takeIf { it != originOf(streamUrl) }
        wanted[MediaHeaders.USER_AGENT] = h(MediaHeaders.USER_AGENT) ?: userAgent?.trim()
        wanted[MediaHeaders.ACCEPT_LANGUAGE] = h(MediaHeaders.ACCEPT_LANGUAGE)
        wanted[MediaHeaders.COOKIE] = cookie?.trim()
        val out = LinkedHashMap<String, String>()
        var total = 0
        for ((k, v) in wanted) {
            if (v.isNullOrEmpty() || v.length > MediaHeaders.MAX_VALUE_LENGTH || !MediaHeaders.isVisibleAscii(v)) continue
            if (total + k.length + v.length > MediaHeaders.MAX_TOTAL_BYTES) continue
            total += k.length + v.length
            out[k] = v
        }
        return out
    }

    /** The request headers worth keeping on a candidate (no Cookie: that is read only when casting). */
    fun forwardable(streamUrl: String, requestHeaders: Map<String, String>, pageUrl: String?, userAgent: String?) =
        build(streamUrl, requestHeaders, pageUrl, userAgent, cookie = null)

    /** "scheme://host[:port]" of [url], or null. */
    fun originOf(url: String): String? = runCatching {
        val u = URI(url)
        val scheme = u.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = u.host?.lowercase() ?: return null
        if (u.port == -1) "$scheme://$host" else "$scheme://$host:${u.port}"
    }.getOrNull()

    private fun isHttpUrl(s: String) = runCatching { URI(s).scheme?.lowercase() }.getOrNull().let { it == "http" || it == "https" }
}
