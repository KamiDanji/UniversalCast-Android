package com.universalcast.sender

import com.universalcast.playback.MediaHeaders
import com.universalcast.playback.SubtitleInfo
import com.universalcast.playback.SubtitleValidator
import com.universalcast.ucp.StrictJson

/** What the "Scan page" script found in the page's main document. */
data class PageScan(val title: String?, val media: List<String>, val tracks: List<Track>) {
    data class Track(val src: String, val language: String?, val label: String?)

    companion object {
        /**
         * Script for `WebView.evaluateJavascript`: the `currentSrc`/`src` of every video/audio/source element (blob: URLs
         * are skipped: they only exist inside the page), every `<track src>` and the document title.
         * Returns one JSON string; it reads the DOM and changes nothing.
         */
        const val SCRIPT = "(function(){try{var o={title:String(document.title||''),media:[],tracks:[]};" +
            "document.querySelectorAll('video,audio,source').forEach(function(e){var s=e.currentSrc||e.src;" +
            "if(s&&String(s).indexOf('blob:')!==0&&o.media.length<50)o.media.push(String(s));});" +
            "document.querySelectorAll('track').forEach(function(t){if(t.src&&o.tracks.length<20)" +
            "o.tracks.push({src:String(t.src),lang:String(t.srclang||''),label:String(t.label||'')});});" +
            "return JSON.stringify(o);}catch(e){return '{}';}})()"

        /** Pauses every video/audio element of the page (the phone must not keep playing once the TV does). */
        const val PAUSE_SCRIPT = "(function(){document.querySelectorAll('video,audio').forEach(function(m){try{m.pause();}catch(e){}});})()"

        /**
         * Parses what `evaluateJavascript` hands back for [SCRIPT]: a JSON-encoded string that itself contains JSON.
         * Never throws; garbage gives an empty result. Only http(s) URLs are kept.
         */
        fun parse(evaluateResult: String?): PageScan {
            val empty = PageScan(null, emptyList(), emptyList())
            if (evaluateResult == null || evaluateResult.length > 512 * 1024) return empty
            val inner = runCatching { StrictJson.parse(evaluateResult) }.getOrNull() as? String ?: return empty
            val o = runCatching { StrictJson.parse(inner) }.getOrNull() as? Map<*, *> ?: return empty
            fun http(s: Any?) = (s as? String)?.takeIf { (it.startsWith("https://") || it.startsWith("http://")) && it.length <= 2048 }
            val media = (o["media"] as? List<*>).orEmpty().mapNotNull { http(it) }.distinct()
            val tracks = (o["tracks"] as? List<*>).orEmpty().mapNotNull { t ->
                val m = t as? Map<*, *> ?: return@mapNotNull null
                Track(http(m["src"]) ?: return@mapNotNull null, (m["lang"] as? String)?.takeIf { it.isNotBlank() }, (m["label"] as? String)?.takeIf { it.isNotBlank() })
            }.distinctBy { it.src }
            return PageScan((o["title"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(200), media, tracks)
        }
    }
}

/** Turns what the browser found into a `media.load` the receiver accepts. */
object BrowserCast {
    /**
     * [cookie] is the browser cookie-store value for the stream URL (read at cast time); it is attached only if it
     * fits the receiver's header rules. Subtitles that the receiver would reject are skipped, at most 8 are sent.
     */
    fun build(
        stream: StreamCandidate,
        subtitles: List<SubtitleCandidate>,
        title: String?,
        cookie: String?,
        autoplay: Boolean = true,
    ): MediaCast {
        val headers = LinkedHashMap(stream.headers)
        cookie?.trim()?.takeIf { it.isNotEmpty() }?.let { c ->
            val candidate = headers + (MediaHeaders.COOKIE to c)
            if (runCatching { MediaHeaders.validate(candidate) }.isSuccess) headers[MediaHeaders.COOKIE] = c
        }
        val subs = subtitles.mapNotNull { s ->
            val info = SubtitleInfo(s.url, s.mimeType, s.language?.takeIf { it.length <= 16 }, s.label?.take(SubtitleValidator.MAX_LABEL))
            info.takeIf { runCatching { SubtitleValidator.validate(listOf(toWire(it))) }.isSuccess }
        }.take(SubtitleValidator.MAX_TRACKS)
        return MediaCast(
            uri = stream.url,
            title = (title ?: stream.pageTitle)?.trim()?.takeIf { it.isNotEmpty() }?.take(200),
            mimeType = stream.kind.mimeType,
            headers = headers,
            subtitles = subs,
            autoplay = autoplay,
        )
    }

    /** Wire form of one subtitle entry in `media.load`. */
    fun toWire(s: SubtitleInfo): Map<String, Any?> = linkedMapOf<String, Any?>("uri" to s.uri, "mimeType" to s.mimeType).apply {
        s.language?.let { put("language", it) }
        s.label?.let { put("label", it) }
    }
}
