package com.universalcast

import com.sun.net.httpserver.HttpServer
import com.universalcast.identity.Role
import com.universalcast.playback.PlaybackSnapshot
import com.universalcast.playback.PlaybackStatus
import com.universalcast.playback.RemoteKey
import com.universalcast.playback.SubtitleInfo
import com.universalcast.playback.SubtitleStyle
import com.universalcast.security.SoftwareSenderIdentity
import com.universalcast.sender.BookmarkStore
import com.universalcast.sender.ByteArrayMediaSource
import com.universalcast.sender.CastHistoryStore
import com.universalcast.sender.ConnectionPhase
import com.universalcast.sender.MediaCast
import com.universalcast.sender.MediaProxyServer
import com.universalcast.sender.PairingSession
import com.universalcast.sender.PairOutcome
import com.universalcast.sender.SenderController
import com.universalcast.ucp.ErrorCode
import com.universalcast.ucp.Method
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoadmapFeaturesTest {
    private inline fun withEnv(block: (Env) -> Unit) {
        val env = Env()
        try { block(env) } finally { env.close() }
    }

    private fun pairController(env: Env): Pair<SoftwareSenderIdentity, SenderController> {
        val id = SoftwareSenderIdentity.generate()
        val ps = PairingSession(id, "Phone", env.core.startPairing().toUri())
        ps.begin()
        ps.confirm()
        env.core.pairing.userConfirmSas(true)
        env.core.pairing.approve(Role.CONTROLLER)
        val out = ps.awaitApproval(10_000)
        assertIs<PairOutcome.Approved>(out)
        val ctl = SenderController(id, connectTimeoutMs = 2_000)
        ctl.connect(out.receiver)
        waitFor { ctl.state.phase == ConnectionPhase.CONNECTED }
        return id to ctl
    }

    // ---- 1. Phone-Side Media Proxy (Local files + Remote HLS / 403 streams) ------------------------------

    @Test fun mediaProxyStreamsLocalMediaWithByteRangesAndRejectsInvalidTokens() {
        val proxy = MediaProxyServer()
        try {
            val payload = ByteArray(100) { it.toByte() }
            val source = ByteArrayMediaSource("clip.mp4", "video/mp4", payload)
            val cast = proxy.registerLocal(source, targetTvHost = "127.0.0.1", title = "My Clip")
            assertEquals("My Clip", cast.title)
            assertEquals("video/mp4", cast.mimeType)

            // Full GET
            val fullConn = URI(cast.uri).toURL().openConnection() as HttpURLConnection
            assertEquals(200, fullConn.responseCode)
            assertEquals("video/mp4", fullConn.contentType)
            assertEquals("bytes", fullConn.getHeaderField("Accept-Ranges"))
            assertContentEquals(payload, fullConn.inputStream.use { it.readBytes() })

            // Byte-range GET (seeking on TV)
            val rangeConn = (URI(cast.uri).toURL().openConnection() as HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=10-29")
            }
            assertEquals(206, rangeConn.responseCode)
            assertEquals("bytes 10-29/100", rangeConn.getHeaderField("Content-Range"))
            assertEquals(20L, rangeConn.contentLengthLong)
            assertContentEquals(payload.sliceArray(10..29), rangeConn.inputStream.use { it.readBytes() })

            // Suffix range
            val suffixConn = (URI(cast.uri).toURL().openConnection() as HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=-5")
            }
            assertEquals(206, suffixConn.responseCode)
            assertContentEquals(payload.sliceArray(95..99), suffixConn.inputStream.use { it.readBytes() })

            // Unauthorized token
            val badUrl = "http://127.0.0.1:${proxy.port}/local/wrongtoken/clip.mp4"
            val badConn = URI(badUrl).toURL().openConnection() as HttpURLConnection
            assertEquals(403, badConn.responseCode)
        } finally {
            proxy.stop()
        }
    }

    @Test fun mediaProxyForwardsHeadersRewritesHlsManifestsAndStreamsSegments() {
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = upstream.address.port
        upstream.createContext("/video/master.m3u8") { ex ->
            val cookie = ex.requestHeaders.getFirst("Cookie")
            val referer = ex.requestHeaders.getFirst("Referer")
            if (cookie != "session=ok" || referer != "https://site.example/watch") {
                ex.sendResponseHeaders(403, -1)
                ex.close()
                return@createContext
            }
            val m3u8 = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
                #EXTINF:10.0,
                seg1.ts
            """.trimIndent().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            ex.sendResponseHeaders(200, m3u8.size.toLong())
            ex.responseBody.use { it.write(m3u8) }
        }
        upstream.createContext("/video/seg1.ts") { ex ->
            if (ex.requestHeaders.getFirst("Cookie") != "session=ok") {
                ex.sendResponseHeaders(403, -1); ex.close(); return@createContext
            }
            val seg = "TS_SEGMENT_DATA".toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "video/mp2t")
            ex.sendResponseHeaders(200, seg.size.toLong())
            ex.responseBody.use { it.write(seg) }
        }
        upstream.createContext("/subs/en.vtt") { ex ->
            val vtt = "WEBVTT\n\n00:00.000 --> 00:02.000\nHello".toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/vtt")
            ex.sendResponseHeaders(200, vtt.size.toLong())
            ex.responseBody.use { it.write(vtt) }
        }
        upstream.start()
        val proxy = MediaProxyServer()
        try {
            val original = MediaCast(
                uri = "http://127.0.0.1:$port/video/master.m3u8",
                title = "Locked Episode",
                mimeType = "application/x-mpegURL",
                headers = mapOf("Referer" to "https://site.example/watch", "Cookie" to "session=ok"),
                subtitles = listOf(SubtitleInfo("http://127.0.0.1:$port/subs/en.vtt", "text/vtt", "en", "English")),
            )
            val proxied = proxy.registerRemote(original, targetTvHost = "127.0.0.1")
            assertTrue(proxied.headers.isEmpty(), "headers stay on phone; TV gets plain proxy URL")

            val manifestConn = URI(proxied.uri).toURL().openConnection() as HttpURLConnection
            assertEquals(200, manifestConn.responseCode)
            val rewritten = manifestConn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            assertTrue(rewritten.contains("/remote/"), rewritten)
            val segLine = rewritten.lines().last { !it.startsWith("#") && it.isNotBlank() }

            // Fetch the rewritten segment URL as the TV would (with no cookies or headers)
            val segConn = URI(segLine).toURL().openConnection() as HttpURLConnection
            assertEquals(200, segConn.responseCode)
            assertEquals("TS_SEGMENT_DATA", segConn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })

            // Fetch the proxied subtitle URL
            val subConn = URI(proxied.subtitles.single().uri).toURL().openConnection() as HttpURLConnection
            assertEquals(200, subConn.responseCode)
            assertTrue(subConn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.startsWith("WEBVTT"))
        } finally {
            proxy.stop()
            upstream.stop(0)
        }
    }

    // ---- 2. Up Next Queue & Auto-Play --------------------------------------------------------------------

    @Test fun queueAddReorderSkipAndAutoAdvanceEndToEnd() = withEnv { env ->
        val (_, ctl) = pairController(env)
        try {
            ctl.enqueue("https://example.org/ep1.mp4", "Episode 1")
            waitFor { ctl.state.snapshot.mediaUri == "https://example.org/ep1.mp4" && ctl.state.snapshot.queue.size == 1 }
            assertEquals(0, ctl.state.snapshot.queueIndex)

            ctl.enqueue("https://example.org/ep2.mp4", "Episode 2")
            ctl.enqueue("https://example.org/ep3.mp4", "Episode 3")
            waitFor { ctl.state.snapshot.queue.size == 3 }
            assertEquals(listOf("Episode 1", "Episode 2", "Episode 3"), ctl.state.snapshot.queue.map { it.title })

            // Auto-advance when Episode 1 ends
            env.engine.simulateEnded()
            waitFor { ctl.state.snapshot.mediaUri == "https://example.org/ep2.mp4" && ctl.state.snapshot.queueIndex == 1 }

            // Skip next / previous
            ctl.queueNext()
            waitFor { ctl.state.snapshot.mediaUri == "https://example.org/ep3.mp4" && ctl.state.snapshot.queueIndex == 2 }
            ctl.queuePrevious()
            waitFor { ctl.state.snapshot.mediaUri == "https://example.org/ep2.mp4" && ctl.state.snapshot.queueIndex == 1 }

            // Reorder & remove
            val ep3Id = ctl.state.snapshot.queue[2].id
            ctl.queueReorder(ep3Id, 0)
            waitFor { ctl.state.snapshot.queue.first().id == ep3Id }
            ctl.queueRemove(ep3Id)
            waitFor { ctl.state.snapshot.queue.size == 2 }
            ctl.queueClear()
            waitFor { ctl.state.snapshot.queue.isEmpty() }
        } finally {
            ctl.shutdown()
        }
    }

    // ---- 3. Live Audio & Subtitle Track Switching, Offset & Styling --------------------------------------

    @Test fun trackSwitchingSubtitleOffsetAndStylingSyncAcrossSnapshot() = withEnv { env ->
        val (_, ctl) = pairController(env)
        try {
            val cast = MediaCast(
                uri = "https://example.org/anime.m3u8",
                title = "Anime Ep 1",
                subtitles = listOf(
                    SubtitleInfo("https://example.org/en.vtt", "text/vtt", "en", "English"),
                    SubtitleInfo("https://example.org/es.vtt", "text/vtt", "es", "Spanish"),
                ),
            )
            ctl.load(cast)
            waitFor { ctl.state.snapshot.status == PlaybackStatus.PLAYING && ctl.state.snapshot.subtitleTracks.size == 2 }

            // Switch audio to Japanese (a:0:1) and subtitles to Spanish (s:0:1)
            ctl.selectTracks(audioTrackId = "a:0:1", subtitleTrackId = "s:0:1")
            waitFor {
                ctl.state.snapshot.audioTracks.firstOrNull { it.selected }?.id == "a:0:1" &&
                    ctl.state.snapshot.subtitleTracks.firstOrNull { it.selected }?.id == "s:0:1"
            }

            // Turn subtitles off
            ctl.selectTracks(subtitleTrackId = "off")
            waitFor { !ctl.state.snapshot.subtitlesEnabled }

            // Set subtitle offset (+500 ms) and custom style
            ctl.setSubtitleOffset(500L)
            waitFor { ctl.state.snapshot.subtitleOffsetMs == 500L }

            val customStyle = SubtitleStyle(fontScale = 1.5f, backgroundOpacity = 0.8f, bottomPaddingFraction = 0.12f)
            ctl.setSubtitleStyle(customStyle)
            waitFor { kotlin.math.abs(ctl.state.snapshot.subtitleStyle.fontScale - 1.5f) < 0.01f }
        } finally {
            ctl.shutdown()
        }
    }

    // ---- 4. Google TV Remote Key Input -------------------------------------------------------------------

    @Test fun remoteKeysReachReceiverAndAreDeniedToObservers() = withEnv { env ->
        val (_, ctl) = pairController(env)
        val (_, observer) = env.pairedLogin(Role.OBSERVER)
        try {
            ctl.remoteKey(RemoteKey.UP)
            ctl.remoteKey(RemoteKey.CENTER)
            ctl.remoteKey(RemoteKey.VOLUME_UP)
            waitFor { env.engine.calls.containsAll(listOf("key:UP", "key:CENTER", "key:VOLUME_UP")) }

            val resp = observer.request(Method.REMOTE_KEY, mapOf("key" to "HOME"), session = "forged")
            assertEquals(ErrorCode.FORBIDDEN, resp.error?.code)
        } finally {
            ctl.shutdown()
        }
    }

    // ---- 5. Resume History & Bookmarks -------------------------------------------------------------------

    @Test fun castHistoryAndBookmarksPersistAndTrackResumePositions() {
        val dir = File.createTempFile("uchist", "").let { it.delete(); it.mkdirs(); it }
        val histFile = File(dir, "history.json")
        val bmFile = File(dir, "bookmarks.json")

        var clock = 1_000L
        val history = CastHistoryStore(histFile, clock = { clock++ })
        history.recordCast("https://cdn.example/movie.mp4", "Great Movie", "video/mp4", "https://site.example/movie")
        history.onSnapshot(
            PlaybackSnapshot(
                status = PlaybackStatus.PLAYING,
                mediaUri = "https://cdn.example/movie.mp4",
                title = "Great Movie",
                positionMs = 45_000L,
                durationMs = 120_000L,
                isPlaying = true,
            )
        )
        // Reload from disk
        val reloaded = CastHistoryStore(histFile).all().single()
        assertEquals("Great Movie", reloaded.title)
        assertEquals("https://site.example/movie", reloaded.pageUrl)
        assertEquals(45_000L, reloaded.lastPositionMs)
        assertEquals(120_000L, reloaded.durationMs)
        assertTrue(reloaded.canResume)

        // Bookmarks
        val bookmarks = BookmarkStore(bmFile, clock = { clock++ })
        assertTrue(bookmarks.toggle("https://onepace.co", "One Pace"))
        assertTrue(bookmarks.isBookmarked("https://onepace.co"))
        assertEquals("One Pace", BookmarkStore(bmFile).all().single().title)
        assertFalse(bookmarks.toggle("https://onepace.co", "One Pace"))
        assertTrue(BookmarkStore(bmFile).all().isEmpty())
    }
}
