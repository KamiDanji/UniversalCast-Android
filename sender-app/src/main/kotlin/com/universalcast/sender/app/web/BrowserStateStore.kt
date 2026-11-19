package com.universalcast.sender.app.web

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.webkit.WebView
import java.io.File

/**
 * Persists the cast browser between app launches: the page, the whole back/forward list with per-page scroll position
 * (WebView's own saved state) and whether the browser was the screen the app was last on.
 *
 * Everything lives in the app's private storage (backups are disabled) and is only ever read back by this app. If a
 * WebView update makes the saved state unreadable, [load] still returns the last URL so the page can simply be reloaded.
 */
object BrowserStateStore {
    private const val PREFS = "universalcast_browser"
    private const val FILE = "browser_state.bin"
    private const val MAX_STATE_BYTES = 2 * 1024 * 1024

    class Saved(val url: String, val state: Bundle?)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    /** Saves [view]'s current page and history. Cheap enough to call after each finished page and when the app stops. */
    fun save(ctx: Context, view: WebView) {
        val url = view.url ?: return
        if (url == "about:blank" || !(url.startsWith("http://", true) || url.startsWith("https://", true))) return
        runCatching {
            val b = Bundle()
            view.saveState(b) ?: return
            val p = Parcel.obtain()
            try {
                p.writeBundle(b)
                val bytes = p.marshall()
                if (bytes.size <= MAX_STATE_BYTES) {
                    val tmp = File(file(ctx).parentFile, "$FILE.tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(file(ctx))) { file(ctx).delete(); tmp.renameTo(file(ctx)) }
                }
            } finally { p.recycle() }
            prefs(ctx).edit().putString("url", url).apply()
        }
    }

    fun load(ctx: Context): Saved? {
        val url = prefs(ctx).getString("url", null)?.takeIf { it.isNotBlank() } ?: return null
        val bundle = runCatching {
            val bytes = file(ctx).takeIf { it.exists() }?.readBytes() ?: return@runCatching null
            val p = Parcel.obtain()
            try {
                p.unmarshall(bytes, 0, bytes.size)
                p.setDataPosition(0)
                p.readBundle(WebView::class.java.classLoader)
            } finally { p.recycle() }
        }.getOrNull()
        return Saved(url, bundle)
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
        prefs(ctx).edit().remove("url").putBoolean("was_open", false).apply()
    }

    /** True when the app was last left on the browser screen (restored on the next cold start). */
    fun wasOpen(ctx: Context): Boolean = prefs(ctx).getBoolean("was_open", false) && prefs(ctx).getString("url", null) != null

    fun setOpen(ctx: Context, open: Boolean) { prefs(ctx).edit().putBoolean("was_open", open).apply() }
}
