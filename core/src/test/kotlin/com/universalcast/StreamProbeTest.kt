package com.universalcast

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.universalcast.sender.*
import org.junit.After
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** StreamProbe against a real local HTTP server (JDK com.sun.net.httpserver). */
class StreamProbeTest {
    private class Hit(val path: String, val headers: Map<String, String?>)

    private val routes = ConcurrentHashMap<String, (HttpExchange) -> Unit>()
    private val hits = CopyOnWriteArrayList<Hit>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            hits += Hit(ex.requestURI.toString(), listOf("Referer", "Origin", "User-Agent", "Cookie", "Accept-Language", "Range").associateWith { ex.requestHeaders.getFirst(it) })
            try {
                (routes[ex.requestURI.path] ?: { it.sendResponseHeaders(404, -1) })(ex)
            } catch (_: Exception) {
            } finally {
                ex.close()
            }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    // "localhost" is a different host name for the same server: used to test cross-host redirects.
    private val otherHostBase = "http://localhost:${server.address.port}"

    @After fun stop() = server.stop(0)

    private fun text(path: String, body: String, type: String = "application/vnd.apple.mpegurl", status: Int = 200) {
        routes[path] = { ex ->
            val b = body.toByteArray()
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(status, b.size.toLong())
            ex.responseBody.write(b)
        }
    }

    private fun redirect(path: String, to: String, code: Int = 302) {
        routes[path] = { ex -> ex.responseHeaders.add("Location", to); ex.sendResponseHeaders(code, -1) }
    }

    private val probe = StreamProbe(timeoutMs = 1_500)

    private val master = """
        #EXTM3U
        #EXT-X-VERSION:4
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="subs/en.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Main",URI="audio.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,SUBTITLES="subs"
        360p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
        /ep1/1080p/index.m3u8?sig=1
        #EXT-X-STREAM-INF:BANDWIDTH=1
        javascript:alert(1)
    """.trimIndent()

    private val vod = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:6.0,\nseg0.ts\n#EXTINF:1440.5,\nseg1.ts\n#EXT-X-ENDLIST\n"

    @Test fun masterPlaylistGivesResolvedVariantsSubtitlesAndDurationFromTheSmallestVariant() {
        text("/ep1/master.m3u8", master)
        text("/ep1/360p/index.m3u8", vod)
        val r = probe.probe("$base/ep1/master.m3u8", emptyMap())
        assertEquals(ProbeOutcome.OK, r.outcome)
        assertEquals(MediaKind.HLS, r.kind)
        assertTrue(r.hlsMaster)
        assertEquals(listOf("$base/ep1/1080p/index.m3u8?sig=1", "$base/ep1/360p/index.m3u8"), r.variants.map { it.uri })
        assertEquals(listOf(1080, 360), r.variants.map { it.height })
        assertEquals(listOf(HlsSubtitleTrack("$base/ep1/subs/en.m3u8", "en", "English")), r.subtitleTracks)
        assertEquals(1446.5, r.durationSec)
        assertEquals(false, r.live)
        assertFalse(r.isProtected, "AES-128 with a clear key is not DRM")
        assertTrue(r.verified)
    }

    @Test fun mediaAndLivePlaylists() {
        text("/vod.m3u8", vod)
        text("/live.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:100\n#EXTINF:4.0,\ns100.ts\n#EXTINF:4.0,\ns101.ts\n")
        val v = probe.probe("$base/vod.m3u8", emptyMap())
        assertFalse(v.hlsMaster); assertEquals(1446.5, v.durationSec); assertEquals(false, v.live)
        val l = probe.probe("$base/live.m3u8", emptyMap())
        assertEquals(MediaKind.HLS, l.kind); assertEquals(true, l.live)
    }

    @Test fun extensionlessPlaylistIsRecognisedByContent() {
        text("/api/stream", "﻿#EXTM3U\n#EXTINF:5,\na.ts\n#EXT-X-ENDLIST", type = "application/octet-stream")
        val r = probe.probe("$base/api/stream", emptyMap())
        assertEquals(MediaKind.HLS, r.kind); assertEquals(5.0, r.durationSec)
    }

    @Test fun drmMarkersAreDetected() {
        val cases = mapOf(
            "/sa.m3u8" to "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:6,\na.ts\n",
            "/wv.m3u8" to "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI=\"data:x\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"\n#EXTINF:6,\na.m4s\n",
            "/fp.m3u8" to "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n",
        )
        for ((path, body) in cases) {
            text(path, body)
            assertTrue(probe.probe("$base$path", emptyMap()).isProtected, path)
        }
        text("/drm-master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv/drm.m3u8\n")
        text("/v/drm.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://k\"\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST")
        assertTrue(probe.probe("$base/drm-master.m3u8", emptyMap()).isProtected, "DRM found in the variant playlist")

        text("/plain.mpd", """<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT23M40.5S"><Period/></MPD>""", "application/dash+xml")
        val plain = probe.probe("$base/plain.mpd", emptyMap())
        assertEquals(MediaKind.DASH, plain.kind); assertFalse(plain.isProtected); assertEquals(1420.5, plain.durationSec); assertEquals(false, plain.live)
        text("/cenc.mpd", """<MPD type="dynamic"><Period><AdaptationSet><ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/></AdaptationSet></Period></MPD>""", "application/dash+xml")
        val cenc = probe.probe("$base/cenc.mpd", emptyMap())
        assertTrue(cenc.isProtected); assertEquals(true, cenc.live)
    }

    @Test fun htmlPagesAndJunkAreNotStreams() {
        text("/page.m3u8", "<!DOCTYPE html><html><title>Just a moment...</title>", type = "text/html")
        val p = probe.probe("$base/page.m3u8", emptyMap())
        assertEquals(ProbeOutcome.WEB_PAGE, p.outcome); assertEquals("This is a web page, not a stream.", p.problem)
        text("/x.mp4", "{\"error\":\"nope\"}", type = "application/json")
        assertEquals(ProbeOutcome.NOT_MEDIA, probe.probe("$base/x.mp4", emptyMap()).outcome)
    }

    @Test fun progressiveFilesAreSniffedWithARangeRequest() {
        routes["/v.mp4"] = { ex ->
            val b = byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) + ByteArray(100)
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            ex.responseHeaders.add("Content-Range", "bytes 0-107/734003200")
            ex.sendResponseHeaders(206, b.size.toLong()); ex.responseBody.write(b)
        }
        val r = probe.probe("$base/v.mp4", emptyMap())
        assertEquals(MediaKind.PROGRESSIVE, r.kind); assertEquals(734003200L, r.sizeBytes)
        assertEquals("bytes=0-262143", hits.last().headers["Range"])
    }

    @Test fun httpErrorsAndUnreachableHostsAreReportedNotThrown() {
        text("/forbidden.m3u8", "nope", status = 403)
        val f = probe.probe("$base/forbidden.m3u8", emptyMap())
        assertEquals(ProbeOutcome.HTTP_ERROR, f.outcome); assertEquals(403, f.httpStatus); assertFalse(f.verified)
        val port = java.net.ServerSocket(0).use { it.localPort }
        val dead = probe.probe("http://127.0.0.1:$port/a.m3u8", emptyMap())
        assertEquals(ProbeOutcome.FAILED, dead.outcome); assertFalse(dead.verified)
        assertEquals(ProbeOutcome.FAILED, probe.probe("not a url", emptyMap()).outcome)
    }

    @Test fun slowServersTimeOut() {
        routes["/slow.m3u8"] = { ex -> Thread.sleep(4_000); ex.sendResponseHeaders(200, -1) }
        val t0 = System.currentTimeMillis()
        val r = probe.probe("$base/slow.m3u8", emptyMap())
        assertEquals(ProbeOutcome.FAILED, r.outcome)
        assertTrue(System.currentTimeMillis() - t0 < 3_500, "read timeout (1.5 s) applies")
    }

    @Test fun oversizedBodiesAreCappedAt256KiB() {
        val big = "#EXTM3U\n" + "#EXTINF:1,\nseg.ts\n".repeat(40_000) // ~720 KB, no ENDLIST within the cap
        text("/big.m3u8", big)
        val r = probe.probe("$base/big.m3u8", emptyMap())
        assertEquals(MediaKind.HLS, r.kind)
        assertTrue(r.durationSec!! < 20_000, "only the first 256 KiB were parsed: ${r.durationSec}")
    }

    @Test fun redirectsAreFollowedAndHeadersForwardedButCookiesStayOnTheOriginalHost() {
        text("/cdn/master.m3u8", vod)
        redirect("/start.m3u8", "$otherHostBase/cdn/master.m3u8") // other host name
        redirect("/same.m3u8", "/cdn/master.m3u8", code = 301)
        val headers = mapOf("Referer" to "https://site.example/watch", "Origin" to "https://site.example", "User-Agent" to "TestUA/1",
            "Accept-Language" to "en", "Cookie" to "sid=secret")

        assertEquals(MediaKind.HLS, probe.probe("$base/same.m3u8", headers).kind)
        val sameHostFinal = hits.last()
        assertEquals("/cdn/master.m3u8", sameHostFinal.path)
        assertEquals("sid=secret", sameHostFinal.headers["Cookie"], "same host after redirect keeps the cookie")
        assertEquals("TestUA/1", sameHostFinal.headers["User-Agent"])

        hits.clear()
        assertEquals(MediaKind.HLS, probe.probe("$base/start.m3u8", headers).kind)
        assertEquals(listOf("/start.m3u8", "/cdn/master.m3u8"), hits.map { it.path })
        assertEquals("sid=secret", hits[0].headers["Cookie"])
        assertNull(hits[1].headers["Cookie"], "cookie must not follow a redirect to another host")
        assertEquals("https://site.example/watch", hits[1].headers["Referer"])
        assertEquals("https://site.example", hits[1].headers["Origin"])
        assertEquals("en", hits[1].headers["Accept-Language"])

        redirect("/loop.m3u8", "/loop.m3u8")
        assertEquals(ProbeOutcome.FAILED, probe.probe("$base/loop.m3u8", emptyMap()).outcome, "redirect loops end")
        redirect("/evil.m3u8", "file:///etc/passwd")
        assertEquals(ProbeOutcome.FAILED, probe.probe("$base/evil.m3u8", emptyMap()).outcome, "non-http redirect refused")
    }

    @Test fun isoDurations() {
        assertEquals(3723.5, StreamProbe.isoDurationSec("PT1H2M3.5S"))
        assertEquals(90.0, StreamProbe.isoDurationSec("PT1M30S"))
        assertEquals(86400.0, StreamProbe.isoDurationSec("P1D"))
        assertNull(StreamProbe.isoDurationSec("soon"))
    }
}
