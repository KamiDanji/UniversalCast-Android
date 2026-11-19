package com.universalcast.sender

import com.universalcast.network.NetworkAddresses
import com.universalcast.playback.MediaHeaders
import com.universalcast.playback.SubtitleInfo
import com.universalcast.security.Bytes
import com.universalcast.security.SecureRandoms
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Abstraction over a local file or Android `content://` URI to stream to the TV. */
interface LocalMediaSource {
    val name: String
    val mimeType: String
    val sizeBytes: Long?
    fun openStream(): InputStream
}

class FileMediaSource(
    private val file: File,
    override val mimeType: String = MediaSniffer.classify(file.name).mimeType ?: "video/mp4",
) : LocalMediaSource {
    override val name: String get() = file.name
    override val sizeBytes: Long get() = file.length()
    override fun openStream(): InputStream = FileInputStream(file)
}

class ByteArrayMediaSource(
    override val name: String,
    override val mimeType: String,
    private val data: ByteArray,
) : LocalMediaSource {
    override val sizeBytes: Long get() = data.size.toLong()
    override fun openStream(): InputStream = ByteArrayInputStream(data)
}

/**
 * Capability-token-authenticated HTTP media proxy running on the sender (phone).
 *
 * Serves two purposes:
 * 1. **Cast Local Media** (`/local/<token>/<name>`): streams phone-hosted video/audio files to the TV with
 *    full HTTP `Range` (`206 Partial Content`) support for instant seeking.
 * 2. **Proxy Locked Web Streams** (`/remote/<token>/stream` and `/remote/<token>/ref?u=<b64url>`): fetches HLS
 *    playlists, segments, keys, subtitles and progressive streams on the phone using the WebView's IP, headers
 *    and cookies, rewriting HLS manifests on the fly so IP-bound / 403-protected streams play smoothly on the TV.
 */
class MediaProxyServer(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) {
    private data class RemoteEntry(
        val token: String,
        val rootUrl: String,
        val rootHost: String?,
        val mimeType: String?,
        val headers: Map<String, String>,
        val cookieProvider: ((String) -> String?)?,
    )

    private val localEntries = ConcurrentHashMap<String, LocalMediaSource>()
    private val remoteEntries = ConcurrentHashMap<String, RemoteEntry>()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "ucp-media-proxy").also { it.isDaemon = true }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    val port: Int get() = serverSocket?.localPort ?: 0
    val isRunning: Boolean get() = serverSocket?.isClosed == false

    @Synchronized
    fun ensureStarted(): Int {
        serverSocket?.takeIf { !it.isClosed }?.let { return it.localPort }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("0.0.0.0", 0))
        serverSocket = ss
        executor.execute { acceptLoop(ss) }
        return ss.localPort
    }

    @Synchronized
    fun stop() {
        localEntries.clear()
        remoteEntries.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    /** Registers a local file/content stream and returns a [MediaCast] pointing to this phone's proxy URL. */
    fun registerLocal(source: LocalMediaSource, targetTvHost: String? = null, title: String? = null): MediaCast {
        val listenPort = ensureStarted()
        val token = Bytes.b64(SecureRandoms.bytes(18))
        localEntries[token] = source
        val host = formatHost(resolveLocalHostFor(targetTvHost))
        val safeName = URLEncoder.encode(source.name.ifBlank { "media" }, "UTF-8").replace("+", "%20")
        val uri = "http://$host:$listenPort/local/$token/$safeName"
        return MediaCast(
            uri = uri,
            title = (title ?: source.name).takeIf { it.isNotBlank() },
            mimeType = source.mimeType,
        )
    }

    /**
     * Wraps a website [cast] so the TV fetches the stream (and any side-loaded subtitles or HLS segments)
     * through this phone instead of directly from the CDN.
     */
    fun registerRemote(
        cast: MediaCast,
        targetTvHost: String? = null,
        cookieProvider: ((String) -> String?)? = null,
    ): MediaCast {
        val listenPort = ensureStarted()
        val token = Bytes.b64(SecureRandoms.bytes(18))
        val rootHost = runCatching { URI(cast.uri).host?.lowercase() }.getOrNull()
        remoteEntries[token] = RemoteEntry(
            token = token,
            rootUrl = cast.uri,
            rootHost = rootHost,
            mimeType = cast.mimeType,
            headers = cast.headers,
            cookieProvider = cookieProvider,
        )
        val host = formatHost(resolveLocalHostFor(targetTvHost))
        val base = "http://$host:$listenPort/remote/$token"
        val proxiedSubs = cast.subtitles.map { sub ->
            val encoded = Bytes.b64(sub.uri.toByteArray(Charsets.UTF_8))
            sub.copy(uri = "$base/ref?u=$encoded")
        }
        val ext = when {
            cast.mimeType.equals("application/x-mpegURL", ignoreCase = true) ||
                cast.mimeType.equals("application/vnd.apple.mpegurl", ignoreCase = true) ||
                MediaSniffer.classify(cast.uri) == MediaKind.HLS -> "stream.m3u8"
            else -> "stream"
        }
        return MediaCast(
            uri = "$base/$ext",
            title = cast.title,
            mimeType = cast.mimeType,
            headers = emptyMap(), // Headers stay on the phone; the TV talks plain HTTP to the phone proxy
            subtitles = proxiedSubs,
            autoplay = cast.autoplay,
            startPositionMs = cast.startPositionMs,
        )
    }

    /** Determines the phone's LAN IP that routes to [targetHost]. */
    fun resolveLocalHostFor(targetHost: String?): String {
        val cleanTarget = targetHost?.trim()?.removePrefix("[")?.removeSuffix("]")
        if (cleanTarget == "127.0.0.1" || cleanTarget == "::1" || cleanTarget == "localhost") {
            return "127.0.0.1"
        }
        if (!cleanTarget.isNullOrEmpty()) {
            runCatching {
                DatagramSocket().use { ds ->
                    ds.connect(InetAddress.getByName(cleanTarget), 8765)
                    val addr = ds.localAddress?.hostAddress?.substringBefore('%')
                    if (!addr.isNullOrEmpty() && addr != "0.0.0.0" && addr != "::") return addr
                }
            }
        }
        val locals = NetworkAddresses.local()
        return locals.firstOrNull { !it.contains(':') && it != "127.0.0.1" }
            ?: locals.firstOrNull()
            ?: "127.0.0.1"
    }

    private fun formatHost(h: String): String = if (h.contains(':') && !h.startsWith("[")) "[$h]" else h

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (_: Exception) {
                break
            }
            executor.execute { handleClient(client) }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = readTimeoutMs
            val input = BufferedInputStream(s.getInputStream())
            val output = s.getOutputStream()
            val reqLine = readAsciiLine(input) ?: return
            val parts = reqLine.split(' ')
            if (parts.size < 2) {
                writeStatus(output, 400, "Bad Request")
                return
            }
            val method = parts[0].uppercase()
            val rawTarget = parts[1]
            val reqHeaders = LinkedHashMap<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    reqHeaders[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }
            if (method != "GET" && method != "HEAD") {
                writeStatus(output, 405, "Method Not Allowed")
                return
            }
            val headOnly = method == "HEAD"
            val path = rawTarget.substringBefore('?')
            val query = rawTarget.substringAfter('?', "")
            val segments = path.trim('/').split('/')
            when {
                segments.size >= 2 && segments[0] == "local" -> {
                    val token = segments[1]
                    val source = localEntries[token]
                    if (source == null) {
                        writeStatus(output, 403, "Forbidden")
                        return
                    }
                    serveLocal(source, reqHeaders["range"], headOnly, output)
                }
                segments.size >= 3 && segments[0] == "remote" -> {
                    val token = segments[1]
                    val entry = remoteEntries[token]
                    if (entry == null) {
                        writeStatus(output, 403, "Forbidden")
                        return
                    }
                    val action = segments[2]
                    val targetUrl = when {
                        action.startsWith("stream") -> entry.rootUrl
                        action == "ref" -> decodeQueryUrl(query)
                        else -> null
                    }
                    if (targetUrl == null || !isValidHttpUrl(targetUrl)) {
                        writeStatus(output, 400, "Bad Request")
                        return
                    }
                    val hostHeader = reqHeaders["host"] ?: "127.0.0.1:$port"
                    serveRemote(entry, targetUrl, hostHeader, reqHeaders["range"], headOnly, output)
                }
                else -> writeStatus(output, 404, "Not Found")
            }
        }
    }

    private fun serveLocal(source: LocalMediaSource, rangeHeader: String?, headOnly: Boolean, out: OutputStream) {
        val total = source.sizeBytes?.takeIf { it >= 0 }
        val range = if (total != null && rangeHeader != null) parseRange(rangeHeader, total) else null
        if (rangeHeader != null && total != null && range == null) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            return
        }
        source.openStream().use { stream ->
            if (range != null && total != null) {
                val (start, end) = range
                val length = end - start + 1
                skipFully(stream, start)
                val headers = buildString {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Type: ${source.mimeType}\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Range: bytes $start-$end/$total\r\n")
                    append("Content-Length: $length\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(headers.toByteArray(Charsets.US_ASCII))
                if (!headOnly) copyBounded(stream, out, length)
            } else {
                val headers = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: ${source.mimeType}\r\n")
                    if (total != null) {
                        append("Accept-Ranges: bytes\r\n")
                        append("Content-Length: $total\r\n")
                    }
                    append("Connection: close\r\n\r\n")
                }
                out.write(headers.toByteArray(Charsets.US_ASCII))
                if (!headOnly) {
                    if (total != null) copyBounded(stream, out, total) else stream.copyTo(out)
                }
            }
            out.flush()
        }
    }

    private fun serveRemote(
        entry: RemoteEntry,
        targetUrl: String,
        proxyHostHeader: String,
        rangeHeader: String?,
        headOnly: Boolean,
        out: OutputStream,
    ) {
        var currentUrl = targetUrl
        var conn: HttpURLConnection? = null
        var code = 502
        try {
            for (hop in 0..5) {
                val u = URI(currentUrl).toURL()
                val c = (u.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    requestMethod = if (headOnly) "HEAD" else "GET"
                }
                val hopHost = u.host?.lowercase()
                for ((k, v) in entry.headers) {
                    if (k.equals(MediaHeaders.COOKIE, ignoreCase = true)) {
                        if (entry.rootHost != null && hopHost == entry.rootHost) {
                            c.setRequestProperty(MediaHeaders.COOKIE, v)
                        }
                    } else {
                        c.setRequestProperty(k, v)
                    }
                }
                if (c.getRequestProperty(MediaHeaders.COOKIE) == null && entry.cookieProvider != null) {
                    entry.cookieProvider.invoke(currentUrl)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        c.setRequestProperty(MediaHeaders.COOKIE, it)
                    }
                }
                if (rangeHeader != null) c.setRequestProperty("Range", rangeHeader)
                c.connect()
                code = c.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrBlank()) {
                        writeStatus(out, 502, "Bad Gateway")
                        return
                    }
                    val resolved = runCatching { URI(currentUrl).resolve(loc).toString() }.getOrNull()
                    if (resolved == null || !isValidHttpUrl(resolved)) {
                        writeStatus(out, 502, "Bad Gateway")
                        return
                    }
                    currentUrl = resolved
                    continue
                }
                conn = c
                break
            }
            val c = conn
            if (c == null) {
                writeStatus(out, 502, "Too Many Redirects")
                return
            }
            val contentType = c.contentType ?: entry.mimeType ?: "application/octet-stream"
            val isHls = isHlsPlaylist(currentUrl, contentType)
            val stream = if (code >= 400) (c.errorStream ?: ByteArrayInputStream(ByteArray(0))) else c.inputStream
            stream.use { body ->
                if (code in 200..299 && isHls) {
                    val text = body.readBytes().toString(Charsets.UTF_8)
                    val rewritten = if (text.trimStart().startsWith("#EXTM3U")) {
                        rewriteHlsPlaylist(text, currentUrl, "http://$proxyHostHeader/remote/${entry.token}")
                    } else text
                    val bytes = rewritten.toByteArray(Charsets.UTF_8)
                    val resp = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: application/vnd.apple.mpegurl\r\n")
                        append("Content-Length: ${bytes.size}\r\n")
                        append("Cache-Control: no-cache\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(resp.toByteArray(Charsets.US_ASCII))
                    if (!headOnly) out.write(bytes)
                } else {
                    val statusText = when (code) {
                        200 -> "OK"
                        206 -> "Partial Content"
                        403 -> "Forbidden"
                        404 -> "Not Found"
                        else -> "Upstream"
                    }
                    val contentLength = c.contentLengthLong
                    val contentRange = c.getHeaderField("Content-Range")
                    val acceptRanges = c.getHeaderField("Accept-Ranges")
                    val resp = buildString {
                        append("HTTP/1.1 $code $statusText\r\n")
                        append("Content-Type: $contentType\r\n")
                        if (contentLength >= 0) append("Content-Length: $contentLength\r\n")
                        if (!contentRange.isNullOrBlank()) append("Content-Range: $contentRange\r\n")
                        if (!acceptRanges.isNullOrBlank()) append("Accept-Ranges: $acceptRanges\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(resp.toByteArray(Charsets.US_ASCII))
                    if (!headOnly) body.copyTo(out)
                }
                out.flush()
            }
        } catch (_: Exception) {
            runCatching { writeStatus(out, 502, "Bad Gateway") }
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private val URI_ATTR_REGEX = Regex("""URI="([^"]+)"""")

        fun isHlsPlaylist(url: String, contentType: String?): Boolean {
            val ct = contentType?.lowercase().orEmpty()
            if (ct.contains("mpegurl") || ct.contains("x-mpegurl")) return true
            val path = url.substringBefore('?').lowercase()
            return path.endsWith(".m3u8")
        }

        /**
         * Rewrites every segment/playlist URI line and every `URI="..."` attribute in an HLS manifest so that the
         * TV fetches them through `[proxyBase]/ref?u=<base64url(resolved)>`.
         */
        fun rewriteHlsPlaylist(manifest: String, baseUrl: String, proxyBase: String): String {
            val baseUri = runCatching { URI(baseUrl) }.getOrNull()
            fun proxyUrl(rawRef: String): String {
                val trimmed = rawRef.trim()
                if (trimmed.isEmpty()) return rawRef
                val resolved = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                    trimmed
                } else {
                    runCatching { baseUri?.resolve(trimmed)?.toString() }.getOrNull() ?: trimmed
                }
                if (!resolved.startsWith("http://", true) && !resolved.startsWith("https://", true)) return rawRef
                val encoded = Bytes.b64(resolved.toByteArray(Charsets.UTF_8))
                return "$proxyBase/ref?u=$encoded"
            }

            return manifest.lines().joinToString("\n") { line ->
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> line
                    trimmed.startsWith("#") -> {
                        URI_ATTR_REGEX.replace(line) { match ->
                            val target = match.groupValues[1]
                            """URI="${proxyUrl(target)}""""
                        }
                    }
                    else -> proxyUrl(trimmed)
                }
            }
        }

        fun parseRange(header: String, totalLength: Long): Pair<Long, Long>? {
            if (totalLength <= 0 || !header.startsWith("bytes=", ignoreCase = true)) return null
            val spec = header.substring(6).trim().substringBefore(',')
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val startStr = spec.substring(0, dash).trim()
            val endStr = spec.substring(dash + 1).trim()
            return try {
                if (startStr.isEmpty()) {
                    val suffix = endStr.toLong()
                    if (suffix <= 0) return null
                    val start = (totalLength - suffix).coerceAtLeast(0)
                    start to (totalLength - 1)
                } else {
                    val start = startStr.toLong()
                    val end = if (endStr.isEmpty()) totalLength - 1 else endStr.toLong().coerceAtMost(totalLength - 1)
                    if (start < 0 || start >= totalLength || end < start) null else start to end
                }
            } catch (_: NumberFormatException) {
                null
            }
        }

        private fun decodeQueryUrl(query: String): String? {
            for (part in query.split('&')) {
                if (part.startsWith("u=")) {
                    val b64 = part.substring(2)
                    return runCatching { String(Bytes.unb64(b64), Charsets.UTF_8) }.getOrNull()
                }
            }
            return null
        }

        private fun isValidHttpUrl(url: String): Boolean = runCatching {
            val u = URI(url)
            val s = u.scheme?.lowercase()
            (s == "http" || s == "https") && !u.host.isNullOrEmpty()
        }.getOrDefault(false)

        private fun readAsciiLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) {
                    if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                    return sb.toString()
                }
                if (sb.length < 8192) sb.append(b.toChar())
            }
        }

        private fun writeStatus(out: OutputStream, code: Int, reason: String) {
            val msg = "HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(msg.toByteArray(Charsets.US_ASCII))
            out.flush()
        }

        private fun skipFully(input: InputStream, bytes: Long) {
            var remaining = bytes
            val buf = ByteArray(8192)
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else {
                    val toRead = minOf(remaining, buf.size.toLong()).toInt()
                    val read = input.read(buf, 0, toRead)
                    if (read <= 0) break
                    remaining -= read
                }
            }
        }

        private fun copyBounded(input: InputStream, out: OutputStream, maxBytes: Long) {
            var remaining = maxBytes
            val buf = ByteArray(16 * 1024)
            while (remaining > 0) {
                val toRead = minOf(remaining, buf.size.toLong()).toInt()
                val read = input.read(buf, 0, toRead)
                if (read <= 0) break
                out.write(buf, 0, read)
                remaining -= read
            }
        }
    }
}
