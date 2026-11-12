package com.universalcast.sender.app.web

import android.content.Context
import com.universalcast.sender.FilterEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The browser's uBlock-style filter lists. Cached in app storage, refreshed in the background when older than
 * [MAX_AGE_MS]; the cached copy is used right away, so the blocker works offline and never delays a page.
 * Only the list files are downloaded (plain GET over HTTPS, no identifiers sent).
 */
class FilterLists(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "filters").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _engine = MutableStateFlow<FilterEngine?>(null)
    /** Null until the lists are loaded (the built-in domain list covers that gap). */
    val engine: StateFlow<FilterEngine?> = _engine.asStateFlow()

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            rebuild()
            var changed = false
            for ((name, url) in LISTS) {
                val f = File(dir, name)
                if (f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) continue
                if (download(url, f)) changed = true
            }
            if (changed) rebuild()
        }
    }

    private fun rebuild() {
        val texts = LISTS.mapNotNull { (name, _) -> File(dir, name).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() } }
        if (texts.isNotEmpty()) _engine.value = FilterEngine.parse(texts)
    }

    private fun download(url: String, to: File): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        if (c.responseCode != 200) false
        else {
            val tmp = File(dir, to.name + ".tmp")
            c.inputStream.use { i ->
                var total = 0L
                tmp.outputStream().use { o ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw java.io.IOException("list too large")
                        o.write(buf, 0, n)
                    }
                }
            }
            tmp.renameTo(to) || (to.delete() && tmp.renameTo(to))
        }
    } catch (_: Exception) { false }

    private companion object {
        const val MAX_AGE_MS = 3L * 24 * 3600 * 1000
        const val MAX_BYTES = 12L * 1024 * 1024
        val LISTS = listOf(
            "easylist.txt" to "https://easylist.to/easylist/easylist.txt",
            "ublock-filters.txt" to "https://ublockorigin.github.io/uAssets/filters/filters.txt",
            "ublock-badware.txt" to "https://ublockorigin.github.io/uAssets/filters/badware.txt",
            "ublock-unbreak.txt" to "https://ublockorigin.github.io/uAssets/filters/unbreak.txt",
            "peter-lowe.txt" to "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext",
        )
    }
}
