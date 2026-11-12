package com.universalcast.sender

import java.net.URI

/** What kind of resource a URL points to, as far as casting is concerned. */
enum class MediaKind(val mimeType: String?, val label: String, val castable: Boolean) {
    HLS("application/x-mpegURL", "HLS stream", true),
    DASH("application/dash+xml", "DASH stream", true),
    PROGRESSIVE(null, "Video file", true),
    AUDIO(null, "Audio file", true),
    SUBTITLE(null, "Subtitles", false),
    /** Segments, keys, images, scripts, pages…: never offered. */
    IGNORE(null, "Not media", false),
}

/** One quality level of an HLS master playlist. */
data class HlsVariant(val uri: String, val bandwidth: Long?, val width: Int?, val height: Int?) {
    val label: String
        get() = buildString {
            append(if (height != null) "${height}p" else "Unknown resolution")
            if (bandwidth != null) append(" · %.1f Mbit/s".format(java.util.Locale.ROOT, bandwidth / 1_000_000.0))
        }
}

/**
 * Pure URL classification for the cast browser. Every request a page makes passes through [classify]
 * (on WebView network threads), so it must be fast, allocation-light and never throw.
 */
object MediaSniffer {
    private val PROGRESSIVE_EXT = setOf("mp4", "m4v", "webm", "mkv", "mov")
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")
    private val SUBTITLE_EXT = mapOf(
        "vtt" to "text/vtt", "webvtt" to "text/vtt", "srt" to "application/x-subrip",
        "ass" to "text/x-ssa", "ssa" to "text/x-ssa", "ttml" to "application/ttml+xml", "dfxp" to "application/ttml+xml",
    )

    /** Stream pieces and page resources: never something to cast on their own, whatever the query says. */
    private val IGNORE_EXT = setOf(
        "ts", "m4s", "m4f", "cmfv", "cmfa", "aac-seg", "key", "bin",
        "jpg", "jpeg", "png", "gif", "webp", "avif", "svg", "ico", "bmp",
        "js", "mjs", "css", "json", "map", "html", "htm", "php-seg", "woff", "woff2", "ttf", "otf", "wasm", "txt", "xml",
    )

    /** `seg-12.mp4`, `chunk_003.mp4`, `frag7.m4v`, `video_segment12.mp4`, `init.mp4`, `init-v1.mp4`… */
    private val SEGMENT_NAME = Regex("""(^|.*[-_.])(seg|segment|chunk|frag|fragment)[-_]?\d+([-_.].*)?$|^init([-_.].*)?$|.*[-_]init$""")

    private val HLS_HINT = Regex("""m3u8""")
    private val DASH_IN_QUERY = Regex("""\.mpd(\W|$)|[=&]mpd(\W|$)|format=dash""")

    /** Classifies [url]. Non-http(s) URLs (blob:, data:, file:…) and anything unparseable are [MediaKind.IGNORE]. */
    fun classify(url: String): MediaKind {
        if (url.length > 4096) return MediaKind.IGNORE
        val u = runCatching { URI(url) }.getOrNull() ?: return MediaKind.IGNORE
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https" || u.host.isNullOrEmpty()) return MediaKind.IGNORE
        val path = (u.rawPath ?: "").lowercase()
        val file = path.substringAfterLast('/')
        val ext = file.substringAfterLast('.', "").takeIf { '.' in file && it.isNotEmpty() && it.length <= 6 }
        if (ext != null) {
            if (ext in IGNORE_EXT) return MediaKind.IGNORE
            when (ext) {
                "m3u8" -> return MediaKind.HLS
                "mpd" -> return MediaKind.DASH
            }
            if (ext in SUBTITLE_EXT) return MediaKind.SUBTITLE
            if (ext in PROGRESSIVE_EXT) {
                return if (SEGMENT_NAME.matches(file.removeSuffix(".$ext"))) MediaKind.IGNORE else MediaKind.PROGRESSIVE
            }
            if (ext in AUDIO_EXT) return MediaKind.AUDIO
        }
        // Extension-less or proxied playlists: ".../hls/m3u8/abc", "...?file=ep1.m3u8", "...?type=m3u8".
        val query = u.rawQuery?.lowercase() ?: ""
        if (HLS_HINT.containsMatchIn(path) || HLS_HINT.containsMatchIn(query)) return MediaKind.HLS
        if (DASH_IN_QUERY.containsMatchIn(query) || path.contains(".mpd/")) return MediaKind.DASH
        return MediaKind.IGNORE
    }

    /** MIME type of a subtitle URL from its extension (null if unknown). */
    fun subtitleMimeType(url: String): String? {
        val path = runCatching { URI(url).rawPath }.getOrNull()?.lowercase() ?: return null
        return SUBTITLE_EXT[path.substringAfterLast('/').substringAfterLast('.', "")]
    }

    private val LANG_TOKENS: Map<String, String> = buildMap {
        fun add(code: String, vararg words: String) { put(code, code); words.forEach { put(it, code) } }
        add("en", "eng", "english")
        add("es", "spa", "esp", "spanish", "espanol")
        add("fr", "fra", "fre", "french")
        add("de", "ger", "deu", "german")
        add("pt", "por", "portuguese")
        add("it", "ita", "italian")
        add("nl", "nld", "dut", "dutch")
        add("ja", "jpn", "jp", "japanese")
        add("ar", "ara", "arabic")
        add("ru", "rus", "russian")
        add("pl", "pol", "polish")
        add("tr", "tur", "turkish")
        add("id", "ind", "indonesian")
    }

    /**
     * Guesses a subtitle language from the URL: `ep1_en.vtt`, `ep1.eng.srt`, `/subs/en/ep1.vtt`, `english.vtt`.
     * The file name wins over directories. Returns a two-letter code or null.
     */
    fun guessLanguage(url: String): String? {
        val path = runCatching { URI(url).rawPath }.getOrNull()?.lowercase() ?: return null
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        val fileTokens = segments.last().substringBeforeLast('.').split('-', '_', '.', ' ', '+')
        fileTokens.asReversed().firstNotNullOfOrNull { LANG_TOKENS[it] }?.let { return it }
        // Directories: only exact language names, nearest first (avoids random two-letter path parts far up).
        return segments.dropLast(1).asReversed().take(2).firstNotNullOfOrNull { LANG_TOKENS[it] }
    }

    fun looksEnglish(language: String?, label: String?): Boolean =
        language?.lowercase()?.let { it == "en" || it.startsWith("en-") || it == "eng" } == true ||
            label?.lowercase()?.let { it.contains("english") || it == "en" } == true
}

/** Whether an HLS URL is a master (list of qualities) or a media playlist (one quality), once known. */
enum class HlsRole { UNKNOWN, MASTER, MEDIA }

/** A stream the page loaded, with the request headers the page used for it. */
data class StreamCandidate(
    val url: String,
    val kind: MediaKind,
    /** Headers to forward (already filtered to the receiver's allowlist, no Cookie: that is read at cast time). */
    val headers: Map<String, String>,
    val pageUrl: String?,
    val pageTitle: String?,
    val seenOrder: Long,
    val hlsRole: HlsRole = HlsRole.UNKNOWN,
    /** Set when this is a variant playlist of a known master; such candidates are hidden. */
    val variantOf: String? = null,
    val probe: ProbeResult? = null,
) {
    val host: String get() = runCatching { URI(url).host }.getOrNull() ?: url
    val isProtected: Boolean get() = probe?.isProtected == true
}

/** A subtitle file the page loaded (or that a master playlist announced). */
data class SubtitleCandidate(
    val url: String,
    val mimeType: String,
    val language: String?,
    val label: String?,
    val seenOrder: Long,
)

/**
 * The cast candidates of the current page: deduplicated by URL, at most [maxStreams] streams (oldest dropped),
 * ranked HLS master > HLS (media/unknown) > DASH > progressive > audio, newest first within a rank. Variant playlists
 * whose master is known are hidden. Thread-safe: fed from WebView network threads, read from the UI.
 */
class CandidateList(private val maxStreams: Int = 40, private val maxSubtitles: Int = 40) {
    private val lock = Any()
    private val streams = LinkedHashMap<String, StreamCandidate>()
    private val subtitles = LinkedHashMap<String, SubtitleCandidate>()
    private val variantMaster = HashMap<String, String>()
    private var order = 0L

    /** Version counter; changes whenever the visible lists may have changed. */
    @Volatile var version = 0L
        private set

    fun clear() = synchronized(lock) {
        streams.clear(); subtitles.clear(); variantMaster.clear(); version++
    }

    /**
     * Records a request the page made. Returns the kind it was classified as. Streams and subtitles are kept;
     * everything else is ignored. [headers] must already be the forwardable set (see [CastHeaders]).
     */
    fun offer(url: String, headers: Map<String, String>, pageUrl: String?, pageTitle: String?): MediaKind {
        val kind = MediaSniffer.classify(url)
        when {
            kind == MediaKind.SUBTITLE -> addSubtitle(url, MediaSniffer.subtitleMimeType(url) ?: return kind, MediaSniffer.guessLanguage(url), null)
            kind.castable -> synchronized(lock) {
                if (url in streams) return kind
                while (streams.size >= maxStreams) streams.remove(streams.keys.first())
                val role = if (kind == MediaKind.HLS && url.substringBefore('?').lowercase().contains("master")) HlsRole.MASTER else HlsRole.UNKNOWN
                streams[url] = StreamCandidate(url, kind, headers, pageUrl, pageTitle, ++order, role, variantMaster[url])
                version++
            }
            else -> {}
        }
        return kind
    }

    fun addSubtitle(url: String, mimeType: String, language: String?, label: String?) = synchronized(lock) {
        if (url in subtitles) return@synchronized
        while (subtitles.size >= maxSubtitles) subtitles.remove(subtitles.keys.first())
        subtitles[url] = SubtitleCandidate(url, mimeType, language ?: MediaSniffer.guessLanguage(url), label, ++order)
        version++
    }

    /**
     * Attaches a probe result; a master playlist hides its variants. Its own subtitle renditions (HLS subtitle
     * playlists) are NOT added as side-loaded files: the TV's player reads them from the master itself.
     */
    fun applyProbe(url: String, result: ProbeResult) = synchronized(lock) {
        val c = streams[url] ?: return@synchronized
        val kind = result.kind?.takeIf { it.castable } ?: c.kind
        val role = when {
            kind != MediaKind.HLS -> HlsRole.UNKNOWN
            result.hlsMaster -> HlsRole.MASTER
            result.kind == MediaKind.HLS -> HlsRole.MEDIA
            else -> c.hlsRole
        }
        streams[url] = c.copy(kind = kind, hlsRole = role, probe = result)
        if (role == HlsRole.MASTER) {
            for (v in result.variants) {
                if (v.uri == url) continue
                variantMaster[v.uri] = url
                streams[v.uri]?.let { streams[v.uri] = it.copy(variantOf = url) }
            }
        }
        version++
    }

    /** Visible streams, best first. */
    fun streams(): List<StreamCandidate> = synchronized(lock) {
        streams.values.filter { it.variantOf == null }
            .sortedWith(compareBy<StreamCandidate> { rank(it) }.thenByDescending { it.seenOrder })
    }

    fun find(url: String): StreamCandidate? = synchronized(lock) { streams[url] }

    /** Subtitles, English-looking first, then newest. */
    fun subtitles(): List<SubtitleCandidate> = synchronized(lock) {
        subtitles.values.sortedWith(
            compareByDescending<SubtitleCandidate> { MediaSniffer.looksEnglish(it.language, it.label) }.thenByDescending { it.seenOrder }
        )
    }

    private fun rank(c: StreamCandidate): Int = when (c.kind) {
        MediaKind.HLS -> if (c.hlsRole == HlsRole.MASTER) 0 else 1
        MediaKind.DASH -> 2
        MediaKind.PROGRESSIVE -> 3
        MediaKind.AUDIO -> 4
        else -> 5
    }
}
