package com.universalcast.sender.app.web

import com.universalcast.playback.MediaHeaders
import com.universalcast.sender.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A stream the page loaded, with the request headers it was loaded with. */
data class MediaCandidate(
    val url: String,
    val guessedKind: MediaKind,
    val pageTitle: String?,
    val pageUrl: String?,
    /** Referer / Origin / User-Agent as the page sent them. Cookies are added only at cast time. */
    val headers: Map<String, String>,
    val seenAtMs: Long,
    val probe: ProbeResult? = null,
) {
    val kind: MediaKind get() = probe?.kind ?: guessedKind
    val host: String get() = runCatching { java.net.URI(url).host }.getOrNull() ?: url
}

/**
 * The streams seen on the current page. Called from WebView's network threads; every candidate is probed once in the
 * background so the list can show quality/duration/size, hide HLS sub-playlists already covered by a master playlist,
 * and drop links that turned out not to be media.
 */
class MediaCandidates(private val scope: CoroutineScope) {
    private val lock = Any()
    private val byUrl = LinkedHashMap<String, MediaCandidate>()
    private val covered = HashSet<String>() // variant playlists of a known master
    private val probeLimit = Semaphore(3)
    private val _items = MutableStateFlow<List<MediaCandidate>>(emptyList())
    val items: StateFlow<List<MediaCandidate>> = _items.asStateFlow()

    fun clear() = synchronized(lock) { byUrl.clear(); covered.clear(); publish() }

    /** [refererFallback]: the player's frame (or the page) — used when WebView doesn't report the request's Referer. */
    fun offer(url: String, kind: MediaKind, pageTitle: String?, refererFallback: String?, requestHeaders: Map<String, String>, userAgent: String?) {
        val headers = forwardable(url, requestHeaders, refererFallback, userAgent)
        synchronized(lock) {
            if (url in byUrl || url in covered) return
            if (byUrl.size >= MAX) byUrl.remove(byUrl.keys.first())
            byUrl[url] = MediaCandidate(url, kind, pageTitle, refererFallback, headers, System.currentTimeMillis())
            publish()
        }
        scope.launch(Dispatchers.IO) {
            val result = probeLimit.withPermit { runCatching { StreamProbe.probe(url, headers) }.getOrNull() }
            synchronized(lock) {
                val c = byUrl[url] ?: return@launch
                if (result == null) return@launch // unreachable from the phone: keep it, the TV may still manage
                val refused = result.httpStatus != null && result.httpStatus !in 200..299
                // Drop what is reachable but not media (HTML, JSON). Keep refused links, marked, so the user sees why.
                if (result.kind == null && !refused) byUrl.remove(url) else byUrl[url] = c.copy(probe = result)
                result.variants.forEach { covered += it.uri; byUrl.remove(it.uri) }
                publish()
            }
        }
    }

    private fun publish() {
        _items.value = byUrl.values
            .filter { it.url !in covered }
            // Most likely "the episode" first: confirmed streams, then longest/biggest, then newest.
            .sortedWith(
                compareByDescending<MediaCandidate> { it.probe?.kind != null }
                    .thenBy { it.probe?.refused == true }
                    .thenByDescending { it.probe?.durationSec ?: 0.0 }
                    .thenByDescending { it.probe?.sizeBytes ?: 0L }
                    .thenByDescending { it.seenAtMs }
            )
    }

    companion object {
        private const val MAX = 40

        /** "https://host[:port]" of [url], or null. */
        fun originOf(url: String): String? = runCatching {
            val u = java.net.URI(url)
            val scheme = u.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
            val host = u.host ?: return null
            if (u.port == -1) "$scheme://$host" else "$scheme://$host:${u.port}"
        }.getOrNull()

        /**
         * The headers the TV should send for [streamUrl]: only those the receiver accepts, taken from the page's request
         * (case-insensitively), completed the way the browser would have sent them. WebView often doesn't report
         * Referer/Origin/User-Agent, and CDNs check them (and may tie the token to the exact User-Agent), so:
         * Referer falls back to the player frame/page, Origin is derived from it for cross-site requests,
         * and User-Agent falls back to the WebView's own.
         */
        fun forwardable(streamUrl: String, requestHeaders: Map<String, String>, refererFallback: String?, userAgent: String?): Map<String, String> {
            fun h(name: String) = requestHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
            val out = LinkedHashMap<String, String>()
            // Same limits as the receiver's MediaHeaders check: an oversized value is dropped, never truncated.
            val referer = (h(MediaHeaders.REFERER) ?: refererFallback)?.takeIf { it.startsWith("http") && it.length <= 2048 }
            referer?.let { out[MediaHeaders.REFERER] = it }
            val origin = h(MediaHeaders.ORIGIN)?.takeIf { it.startsWith("http") }
                ?: referer?.let { originOf(it) }?.takeIf { it != originOf(streamUrl) }
            origin?.takeIf { it.length <= 512 }?.let { out[MediaHeaders.ORIGIN] = it }
            (h(MediaHeaders.USER_AGENT) ?: userAgent)?.takeIf { it.isNotBlank() && it.length <= 512 }?.let { out[MediaHeaders.USER_AGENT] = it }
            return out
        }
    }
}
