package com.universalcast.sender

import com.universalcast.playback.PlaybackSnapshot
import com.universalcast.playback.PlaybackStatus
import com.universalcast.ucp.Fields
import com.universalcast.ucp.StrictJson
import java.io.File

/**
 * A previously cast media item with its last known playback position for 1-tap resume.
 * Request headers and cookies are deliberately NEVER persisted here.
 */
data class HistoryEntry(
    val uri: String,
    val title: String? = null,
    val mimeType: String? = null,
    val pageUrl: String? = null,
    val lastPositionMs: Long = 0,
    val durationMs: Long? = null,
    val updatedAtMs: Long = 0,
) {
    val canResume: Boolean
        get() = lastPositionMs >= 5_000L && (durationMs == null || durationMs <= 0L || lastPositionMs < durationMs - 10_000L)

    val progressFraction: Float
        get() = if (durationMs != null && durationMs > 0) (lastPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "uri" to uri,
        "title" to title,
        "mimeType" to mimeType,
        "pageUrl" to pageUrl,
        "lastPositionMs" to lastPositionMs,
        "durationMs" to durationMs,
        "updatedAtMs" to updatedAtMs,
    )

    companion object {
        fun fromJson(f: Fields): HistoryEntry? = runCatching {
            HistoryEntry(
                uri = f.string("uri", 2048),
                title = f.optString("title", 256, 0),
                mimeType = f.optString("mimeType", 128, 0),
                pageUrl = f.optString("pageUrl", 2048, 0),
                lastPositionMs = f.optLong("lastPositionMs", 0, Long.MAX_VALUE) ?: 0L,
                durationMs = f.optLong("durationMs", 0, Long.MAX_VALUE),
                updatedAtMs = f.optLong("updatedAtMs", 0, Long.MAX_VALUE) ?: 0L,
            )
        }.getOrNull()
    }
}

/** Thread-safe persistent store of recently cast streams and their playback positions. */
class CastHistoryStore(
    private val file: File? = null,
    private val maxEntries: Int = 25,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries = LinkedHashMap<String, HistoryEntry>()

    init {
        loadFromDisk()
    }

    @Synchronized
    fun all(): List<HistoryEntry> = entries.values.sortedByDescending { it.updatedAtMs }

    @Synchronized
    fun recordCast(uri: String, title: String? = null, mimeType: String? = null, pageUrl: String? = null, startPositionMs: Long = 0) {
        if (uri.isBlank() || isLocalProxyUri(uri)) return
        val existing = entries[uri]
        val now = clock()
        val entry = HistoryEntry(
            uri = uri,
            title = title?.takeIf { it.isNotBlank() } ?: existing?.title,
            mimeType = mimeType ?: existing?.mimeType,
            pageUrl = pageUrl?.takeIf { it.isNotBlank() } ?: existing?.pageUrl,
            lastPositionMs = if (startPositionMs > 0) startPositionMs else (existing?.lastPositionMs ?: 0L),
            durationMs = existing?.durationMs,
            updatedAtMs = now,
        )
        entries.remove(uri)
        entries[uri] = entry
        trimAndSave()
    }

    /** Updates the playback position of the currently playing item from a receiver [PlaybackSnapshot]. */
    @Synchronized
    fun onSnapshot(snapshot: PlaybackSnapshot): Boolean {
        val uri = snapshot.mediaUri ?: return false
        if (isLocalProxyUri(uri)) return false
        if (snapshot.status != PlaybackStatus.PLAYING && snapshot.status != PlaybackStatus.PAUSED && snapshot.status != PlaybackStatus.ENDED) {
            return false
        }
        val existing = entries[uri]
        val pos = if (snapshot.status == PlaybackStatus.ENDED && snapshot.durationMs != null) {
            snapshot.durationMs
        } else {
            snapshot.positionMs
        }
        if (existing != null && kotlin.math.abs(existing.lastPositionMs - pos) < 3_000 && existing.durationMs == snapshot.durationMs) {
            return false
        }
        val updated = HistoryEntry(
            uri = uri,
            title = snapshot.title?.takeIf { it.isNotBlank() } ?: existing?.title,
            mimeType = existing?.mimeType,
            pageUrl = existing?.pageUrl,
            lastPositionMs = pos.coerceAtLeast(0),
            durationMs = snapshot.durationMs ?: existing?.durationMs,
            updatedAtMs = clock(),
        )
        entries[uri] = updated
        trimAndSave()
        return true
    }

    @Synchronized
    fun remove(uri: String): Boolean {
        val removed = entries.remove(uri) != null
        if (removed) saveToDisk()
        return removed
    }

    @Synchronized
    fun clear() {
        entries.clear()
        saveToDisk()
    }

    private fun isLocalProxyUri(uri: String): Boolean =
        uri.contains("/local/") || uri.contains("/remote/")

    private fun trimAndSave() {
        while (entries.size > maxEntries) {
            val oldest = entries.values.minByOrNull { it.updatedAtMs }?.uri ?: break
            entries.remove(oldest)
        }
        saveToDisk()
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadFromDisk() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val parsed = StrictJson.parse(f.readText(Charsets.UTF_8)) as? Map<String, Any?> ?: return
            val list = parsed["items"] as? List<*> ?: return
            for (raw in list) {
                val m = raw as? Map<String, Any?> ?: continue
                val entry = HistoryEntry.fromJson(Fields(m)) ?: continue
                entries[entry.uri] = entry
            }
        }
    }

    private fun saveToDisk() {
        val f = file ?: return
        runCatching {
            val json = StrictJson.stringify(linkedMapOf("items" to entries.values.map { it.toJson() }))
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }
}

/** A user-saved website bookmark in the Cast Browser. */
data class BookmarkEntry(
    val url: String,
    val title: String,
    val addedAtMs: Long = 0,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "url" to url,
        "title" to title,
        "addedAtMs" to addedAtMs,
    )

    companion object {
        fun fromJson(f: Fields): BookmarkEntry? = runCatching {
            BookmarkEntry(
                url = f.string("url", 2048),
                title = f.string("title", 256, 0),
                addedAtMs = f.optLong("addedAtMs", 0, Long.MAX_VALUE) ?: 0L,
            )
        }.getOrNull()
    }
}

/** Thread-safe persistent store of browser bookmarks. */
class BookmarkStore(
    private val file: File? = null,
    private val maxBookmarks: Int = 50,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val bookmarks = LinkedHashMap<String, BookmarkEntry>()

    init {
        loadFromDisk()
    }

    @Synchronized
    fun all(): List<BookmarkEntry> = bookmarks.values.sortedByDescending { it.addedAtMs }

    @Synchronized
    fun isBookmarked(url: String): Boolean = normalizeKey(url) in bookmarks

    @Synchronized
    fun toggle(url: String, title: String?): Boolean {
        val key = normalizeKey(url)
        if (key.isEmpty()) return false
        return if (key in bookmarks) {
            bookmarks.remove(key)
            saveToDisk()
            false
        } else {
            add(url, title)
            true
        }
    }

    @Synchronized
    fun add(url: String, title: String?) {
        val key = normalizeKey(url)
        if (key.isEmpty()) return
        val displayTitle = title?.trim()?.takeIf { it.isNotEmpty() }?.take(120)
            ?: runCatching { java.net.URI(key).host }.getOrNull()
            ?: key.take(80)
        bookmarks.remove(key)
        bookmarks[key] = BookmarkEntry(url = key, title = displayTitle, addedAtMs = clock())
        while (bookmarks.size > maxBookmarks) {
            val oldest = bookmarks.values.minByOrNull { it.addedAtMs }?.url ?: break
            bookmarks.remove(oldest)
        }
        saveToDisk()
    }

    @Synchronized
    fun remove(url: String): Boolean {
        val removed = bookmarks.remove(normalizeKey(url)) != null
        if (removed) saveToDisk()
        return removed
    }

    private fun normalizeKey(url: String): String = url.trim()

    @Suppress("UNCHECKED_CAST")
    private fun loadFromDisk() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val parsed = StrictJson.parse(f.readText(Charsets.UTF_8)) as? Map<String, Any?> ?: return
            val list = parsed["bookmarks"] as? List<*> ?: return
            for (raw in list) {
                val m = raw as? Map<String, Any?> ?: continue
                val entry = BookmarkEntry.fromJson(Fields(m)) ?: continue
                bookmarks[entry.url] = entry
            }
        }
    }

    private fun saveToDisk() {
        val f = file ?: return
        runCatching {
            val json = StrictJson.stringify(linkedMapOf("bookmarks" to bookmarks.values.map { it.toJson() }))
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }
}
