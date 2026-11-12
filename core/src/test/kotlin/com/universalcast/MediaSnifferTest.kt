package com.universalcast

import com.universalcast.sender.*
import org.junit.Test
import kotlin.test.*

class MediaSnifferTest {
    @Test fun urlShapesAreClassified() {
        val cases = listOf(
            // HLS
            "https://cdn.example/anime/ep1/master.m3u8" to MediaKind.HLS,
            "https://cdn.example/ep1/index.M3U8?token=abc&exp=1" to MediaKind.HLS,
            "https://cdn.example/hls/720p/playlist.m3u8#t=10" to MediaKind.HLS,
            "https://proxy.example/play?file=ep1.m3u8" to MediaKind.HLS,
            "https://proxy.example/stream?id=9&type=m3u8" to MediaKind.HLS,
            "https://proxy.example/stream.php?u=aHR0cHM6Ly9jZG4vYS5tM3U4&ext=m3u8" to MediaKind.HLS,
            "https://cdn.example/m3u8/abcdef0123" to MediaKind.HLS,
            "https://cdn.example/v/abc/master.m3u8/token/xyz" to MediaKind.HLS,
            "http://10.0.0.2:8080/live/stream.m3u8" to MediaKind.HLS,
            // DASH
            "https://cdn.example/v/manifest.mpd" to MediaKind.DASH,
            "https://cdn.example/v/Manifest.MPD?sig=1" to MediaKind.DASH,
            "https://proxy.example/get?file=video.mpd" to MediaKind.DASH,
            // progressive
            "https://cdn.example/files/One%20Pace%20-%20Ep%201.mp4" to MediaKind.PROGRESSIVE,
            "https://cdn.example/a.webm" to MediaKind.PROGRESSIVE,
            "https://cdn.example/a.mkv?download=1" to MediaKind.PROGRESSIVE,
            "https://cdn.example/clip.MOV" to MediaKind.PROGRESSIVE,
            "https://cdn.example/clip.m4v" to MediaKind.PROGRESSIVE,
            "https://cdn.example/One-Pace-Part-12.mp4" to MediaKind.PROGRESSIVE,
            "https://cdn.example/segment-of-the-day.mp4" to MediaKind.PROGRESSIVE,
            // audio
            "https://cdn.example/song.mp3" to MediaKind.AUDIO,
            "https://cdn.example/pod/ep.m4a?x=1" to MediaKind.AUDIO,
            "https://cdn.example/a.ogg" to MediaKind.AUDIO,
            "https://cdn.example/a.opus" to MediaKind.AUDIO,
            "https://cdn.example/a.flac" to MediaKind.AUDIO,
            "https://cdn.example/a.wav" to MediaKind.AUDIO,
            "https://cdn.example/a.aac" to MediaKind.AUDIO,
            // subtitles
            "https://cdn.example/subs/ep1_en.vtt" to MediaKind.SUBTITLE,
            "https://cdn.example/subs/ep1.srt?x=1" to MediaKind.SUBTITLE,
            "https://cdn.example/subs/ep1.ass" to MediaKind.SUBTITLE,
            "https://cdn.example/subs/ep1.ssa" to MediaKind.SUBTITLE,
            "https://cdn.example/subs/ep1.ttml" to MediaKind.SUBTITLE,
        )
        for ((url, kind) in cases) assertEquals(kind, MediaSniffer.classify(url), url)
    }

    @Test fun segmentsKeysPagesAndNonWebUrlsAreIgnored() {
        for (url in listOf(
            "https://cdn.example/ep1/seg-00001.ts", "https://cdn.example/ep1/720p_003.ts?token=m3u8",
            "https://cdn.example/ep1/chunk-3.m4s?f=x.mp4", "https://cdn.example/v/seg-12-v1-a1.m4s",
            "https://cdn.example/a.cmfv", "https://cdn.example/a.cmfa",
            "https://cdn.example/ep1/seg-12.mp4", "https://cdn.example/ep1/seg12.mp4", "https://cdn.example/ep1/segment_3.mp4",
            "https://cdn.example/ep1/chunk_0042.mp4", "https://cdn.example/ep1/frag7.mp4", "https://cdn.example/ep1/fragment-1.m4v",
            "https://cdn.example/ep1/video_seg12.mp4", "https://cdn.example/ep1/init.mp4", "https://cdn.example/ep1/init-v1.mp4",
            "https://cdn.example/ep1/v1_init.mp4",
            "https://cdn.example/key.key", "https://cdn.example/enc.key?m3u8=1",
            "https://onepace.example/series/one-pace-english-sub/", "https://cdn.example/player.js",
            "https://cdn.example/player.js?src=a.m3u8", "https://cdn.example/style.css", "https://cdn.example/thumb.jpg",
            "https://cdn.example/sprite.webp", "https://cdn.example/api/sources.json", "https://cdn.example/font.woff2",
            "https://cdn.example/index.html", "https://cdn.example/",
            "blob:https://site/1234", "data:video/mp4;base64,AAAA", "file:///sdcard/a.mp4", "content://media/1",
            "javascript:alert(1)", "not a url", "", "https://", "ftp://cdn.example/a.mp4", "intent://x#Intent;end",
        )) assertEquals(MediaKind.IGNORE, MediaSniffer.classify(url), url)
    }

    @Test fun subtitleLanguageIsGuessedFromTheUrl() {
        assertEquals("en", MediaSniffer.guessLanguage("https://cdn.example/subs/ep1_en.vtt"))
        assertEquals("en", MediaSniffer.guessLanguage("https://cdn.example/subs/ep1.eng.srt"))
        assertEquals("en", MediaSniffer.guessLanguage("https://cdn.example/subs/en/ep1.vtt"))
        assertEquals("en", MediaSniffer.guessLanguage("https://cdn.example/subs/English.vtt?sig=1"))
        assertEquals("es", MediaSniffer.guessLanguage("https://cdn.example/subs/ep1-spa.vtt"))
        assertEquals("pt", MediaSniffer.guessLanguage("https://cdn.example/subs/ep1.pt.vtt"))
        assertEquals("fr", MediaSniffer.guessLanguage("https://cdn.example/fr/subs/ep1.vtt"))
        assertNull(MediaSniffer.guessLanguage("https://cdn.example/subs/ep1.vtt"))
        assertNull(MediaSniffer.guessLanguage("not a url"))
        assertEquals("text/vtt", MediaSniffer.subtitleMimeType("https://cdn.example/a.vtt?x"))
        assertEquals("application/x-subrip", MediaSniffer.subtitleMimeType("https://cdn.example/a.srt"))
        assertEquals("text/x-ssa", MediaSniffer.subtitleMimeType("https://cdn.example/a.ass"))
        assertNull(MediaSniffer.subtitleMimeType("https://cdn.example/a.mp4"))
    }

    // ---- candidate list ---------------------------------------------------------------------------------

    private fun list() = CandidateList()
    private fun CandidateList.add(url: String) = offer(url, emptyMap(), "https://site.example/watch", "Ep 1")

    @Test fun candidatesAreDedupedRankedAndCapped() {
        val l = list()
        l.add("https://cdn.example/a.mp3")
        l.add("https://cdn.example/a.mp4")
        l.add("https://cdn.example/m.mpd")
        l.add("https://cdn.example/ep1/index.m3u8")
        l.add("https://cdn.example/ep1/master.m3u8")
        l.add("https://cdn.example/b.mp4")
        l.add("https://cdn.example/a.mp4") // duplicate
        l.add("https://cdn.example/seg-1.ts") // ignored
        assertEquals(
            listOf(
                "https://cdn.example/ep1/master.m3u8", "https://cdn.example/ep1/index.m3u8", "https://cdn.example/m.mpd",
                "https://cdn.example/b.mp4", "https://cdn.example/a.mp4", "https://cdn.example/a.mp3",
            ),
            l.streams().map { it.url },
            "HLS master > HLS > DASH > progressive (newest first) > audio",
        )
        val big = list()
        repeat(55) { big.add("https://cdn.example/v$it.mp4") }
        val urls = big.streams().map { it.url }
        assertEquals(40, urls.size)
        assertEquals("https://cdn.example/v54.mp4", urls.first(), "newest first")
        assertFalse("https://cdn.example/v0.mp4" in urls, "oldest dropped")
    }

    @Test fun variantsOfAKnownMasterAreHiddenWhicheverArrivesFirst() {
        val l = list()
        l.add("https://cdn.example/ep1/720p.m3u8") // the player fetched a variant before we probed the master
        l.add("https://cdn.example/ep1/playlist.m3u8")
        val probe = ProbeResult(
            ProbeOutcome.OK, MediaKind.HLS, 200, hlsMaster = true,
            variants = listOf(HlsVariant("https://cdn.example/ep1/720p.m3u8", 2_000_000, 1280, 720), HlsVariant("https://cdn.example/ep1/1080p.m3u8", 5_000_000, 1920, 1080)),
            subtitleTracks = listOf(HlsSubtitleTrack("https://cdn.example/ep1/subs/en.m3u8", "en", "English")),
        )
        l.applyProbe("https://cdn.example/ep1/playlist.m3u8", probe)
        l.add("https://cdn.example/ep1/1080p.m3u8") // later variant request
        assertEquals(listOf("https://cdn.example/ep1/playlist.m3u8"), l.streams().map { it.url })
        assertEquals(HlsRole.MASTER, l.streams().single().hlsRole)
        assertEquals("https://cdn.example/ep1/playlist.m3u8", l.find("https://cdn.example/ep1/1080p.m3u8")!!.variantOf)
        assertTrue(l.subtitles().isEmpty(), "HLS subtitle renditions play from the master; they are not side-loaded files")
        assertEquals("English", l.streams().single().probe!!.subtitleTracks.single().name)
    }

    @Test fun subtitlesAreCollectedEnglishFirst() {
        val l = list()
        l.add("https://cdn.example/subs/ep1_es.vtt")
        l.add("https://cdn.example/subs/ep1_en.vtt")
        l.add("https://cdn.example/subs/ep1_fr.srt")
        l.add("https://cdn.example/subs/ep1_en.vtt")
        assertTrue(l.streams().isEmpty())
        val s = l.subtitles()
        assertEquals(3, s.size)
        assertEquals("https://cdn.example/subs/ep1_en.vtt", s.first().url)
        assertEquals("application/x-subrip", s.first { it.url.endsWith(".srt") }.mimeType)
    }

    @Test fun protectedProbeMarksTheCandidate() {
        val l = list()
        l.add("https://cdn.example/drm/master.m3u8")
        l.applyProbe("https://cdn.example/drm/master.m3u8", ProbeResult(ProbeOutcome.OK, MediaKind.HLS, 200, isProtected = true))
        assertTrue(l.streams().single().isProtected)
        assertNotNull(l.streams().single().probe!!.problem)
    }

    @Test fun clearingStartsOver() {
        val l = list()
        l.add("https://cdn.example/a.mp4"); l.add("https://cdn.example/a.vtt")
        val v = l.version
        l.clear()
        assertTrue(l.streams().isEmpty() && l.subtitles().isEmpty())
        assertTrue(l.version > v)
    }

    @Test fun concurrentOffersAreSafe() {
        val l = list()
        val threads = (0 until 8).map { t -> Thread { repeat(200) { i -> l.add("https://cdn.example/t$t/v$i.m3u8"); l.streams() } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(40, l.streams().size)
    }

    // ---- headers -----------------------------------------------------------------------------------------

    @Test fun castHeadersFollowTheBrowserAndAlwaysPassTheReceiverRules() {
        val h = CastHeaders.build(
            "https://cdn.example/ep1/master.m3u8",
            mapOf("referer" to "https://player.example/embed/1", "Accept-Language" to "en-US,en;q=0.9", "Sec-Fetch-Dest" to "empty"),
            pageUrl = "https://site.example/watch/1", userAgent = "Mozilla/5.0 (WebView)", cookie = "cf_clearance=abc",
        )
        assertEquals(
            mapOf(
                "Referer" to "https://player.example/embed/1", "Origin" to "https://player.example",
                "User-Agent" to "Mozilla/5.0 (WebView)", "Accept-Language" to "en-US,en;q=0.9", "Cookie" to "cf_clearance=abc",
            ),
            h,
        )
        assertEquals(h, com.universalcast.playback.MediaHeaders.validate(h), "accepted unchanged by the receiver")

        val fallback = CastHeaders.build("https://site.example/v.mp4", emptyMap(), "https://site.example/watch/1", "UA")
        assertEquals(mapOf("Referer" to "https://site.example/watch/1", "User-Agent" to "UA"), fallback, "same origin: no Origin, page URL as Referer")

        val explicitOrigin = CastHeaders.build("https://cdn.example/a.m3u8", mapOf("Origin" to "https://o.example"), null, null)
        assertEquals(mapOf("Origin" to "https://o.example"), explicitOrigin)

        val dropped = CastHeaders.build(
            "https://cdn.example/a.m3u8", mapOf("User-Agent" to "UA\r\nX: 1", "Referer" to "javascript:x"),
            pageUrl = "about:blank", userAgent = null, cookie = "é=1",
        )
        assertTrue(dropped.isEmpty(), "invalid values are dropped, never truncated or forwarded: $dropped")
        val huge = CastHeaders.build("https://cdn.example/a.m3u8", emptyMap(), "https://s.example/" + "p".repeat(5000), "UA", "c".repeat(4000))
        assertFalse("Referer" in huge); assertEquals("UA", huge["User-Agent"]); assertTrue("Cookie" in huge)
        com.universalcast.playback.MediaHeaders.validate(huge)
    }
}
