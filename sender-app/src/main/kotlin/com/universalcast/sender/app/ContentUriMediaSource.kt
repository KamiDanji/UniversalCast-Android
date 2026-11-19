package com.universalcast.sender.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.universalcast.sender.LocalMediaSource
import com.universalcast.sender.MediaSniffer
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * [LocalMediaSource] backed by an Android `content://` URI chosen via the system file picker.
 * Opens a fresh [InputStream] per HTTP request so the TV's ExoPlayer can perform random-access byte-range seeks.
 */
class ContentUriMediaSource(
    context: Context,
    val uri: Uri,
) : LocalMediaSource {
    private val app = context.applicationContext
    override val name: String
    override val sizeBytes: Long?
    override val mimeType: String

    init {
        var displayName: String? = null
        var length: Long? = null
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) displayName = c.getString(nameIdx)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) length = c.getLong(sizeIdx).takeIf { it >= 0 }
                }
            }
        }
        if (length == null) {
            length = runCatching {
                app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    afd.length.takeIf { it >= 0 }
                }
            }.getOrNull()
        }
        name = displayName?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Local Media"
        sizeBytes = length
        val resolverMime = runCatching { app.contentResolver.getType(uri) }.getOrNull()
        mimeType = resolverMime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MediaSniffer.classify(name).mimeType
            ?: "video/mp4"
    }

    override fun openStream(): InputStream =
        app.contentResolver.openInputStream(uri) ?: ByteArrayInputStream(ByteArray(0))
}
