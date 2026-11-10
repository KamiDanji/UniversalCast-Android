package com.universalcast

import com.universalcast.sender.MediaKind
import com.universalcast.sender.MediaSniffer
import org.junit.Test
import kotlin.test.*

class MediaSnifferTest {
    @Test fun urlsAreClassifiedByPathAndQuery() {
        val cases = mapOf(
            "https://cdn.example/anime/ep1/master.m3u8" to MediaKind.HLS,
            "https://cdn.example/ep1/index.M3U8?token=abc&exp=1" to MediaKind.HLS,
            "https://proxy.example/play?file=ep1.m3u8" to MediaKind.HLS,
            "https://proxy.example/stream?id=9&type=m3u8" to MediaKind.HLS,
            "https://cdn.example/v/manifest.mpd" to MediaKind.DASH,
            "https://cdn.example/files/One%20Pace%20-%20Ep%201.mp4" to MediaKind.VIDEO,
            "https://cdn.example/a.webm" to MediaKind.VIDEO,
            "https://cdn.example/song.mp3" to MediaKind.AUDIO,
        )
        for ((url, kind) in cases) assertEquals(kind, MediaSniffer.classifyUrl(url), url)

        for (notMedia in listOf(
            "https://cdn.example/ep1/seg-00001.ts", "https://cdn.example/ep1/chunk-3.m4s?f=x.mp4", "https://cdn.example/key.key",
            "https://onepace.example/series/one-pace-english-sub/", "https://cdn.example/player.js", "https://cdn.example/thumb.jpg",
            "https://cdn.example/subs.vtt", "blob:https://site/1234", "data:video/mp4;base64,AAAA", "file:///sdcard/a.mp4",
            "javascript:alert(1)", "not a url", "",
        )) assertNull(MediaSniffer.classifyUrl(notMedia), notMedia)
    }

    @Test fun contentBeatsTheUrl() {
        assertEquals(MediaKind.HLS, MediaSniffer.classifyContent("application/octet-stream", "#EXTM3U\n#EXT-X-VERSION:3".toByteArray()))
        assertEquals(MediaKind.HLS, MediaSniffer.classifyContent(null, "﻿#EXTM3U\n".toByteArray(Charsets.UTF_8)))
        assertEquals(MediaKind.HLS, MediaSniffer.classifyContent("application/vnd.apple.mpegurl", ByteArray(0)))
        assertEquals(MediaKind.DASH, MediaSniffer.classifyContent("text/xml", """<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011">""".toByteArray()))
        assertEquals(MediaKind.VIDEO, MediaSniffer.classifyContent("video/mp4; charset=binary", byteArrayOf(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70)))
        assertEquals(MediaKind.AUDIO, MediaSniffer.classifyContent("audio/mpeg", ByteArray(4)))
        assertNull(MediaSniffer.classifyContent("text/html", "<!doctype html><title>403</title>".toByteArray()))
        assertNull(MediaSniffer.classifyContent("application/json", "{}".toByteArray()))
        assertNull(MediaSniffer.classifyContent(null, ByteArray(0)))
    }

    @Test fun hlsMasterPlaylistIsParsedIntoResolvedVariantsHighestFirst() {
        val master = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            360p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            https://other.cdn/1080p/index.m3u8?sig=1
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720

            /abs/720p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1
            javascript:alert(1)
        """.trimIndent()
        val v = MediaSniffer.hlsVariants(master, "https://cdn.example/ep1/master.m3u8?token=t")
        assertEquals(listOf(1080, 720, 360), v.map { it.height })
        assertEquals("https://other.cdn/1080p/index.m3u8?sig=1", v[0].uri)
        assertEquals("https://cdn.example/abs/720p.m3u8", v[1].uri)
        assertEquals("https://cdn.example/ep1/360p/index.m3u8", v[2].uri)
        assertEquals("1080p · 5.0 Mbit/s", v[0].label)
    }

    @Test fun mediaPlaylistsAndGarbageHaveNoVariants() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg0.ts\n#EXT-X-ENDLIST"
        assertTrue(MediaSniffer.hlsVariants(media, "https://cdn.example/a.m3u8").isEmpty())
        assertTrue(MediaSniffer.hlsVariants("<html>", "https://cdn.example/a.m3u8").isEmpty())
        assertTrue(MediaSniffer.hlsVariants("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8", "::bad").isEmpty())
    }

    @Test fun hlsDurationOnlyForFinishedMediaPlaylists() {
        assertEquals(1452.5, MediaSniffer.hlsDurationSec("#EXTM3U\n#EXTINF:6.0,\na.ts\n#EXTINF:1446.5,\nb.ts\n#EXT-X-ENDLIST\n"))
        assertNull(MediaSniffer.hlsDurationSec("#EXTM3U\n#EXTINF:6.0,\na.ts\n"), "live: no end")
        assertNull(MediaSniffer.hlsDurationSec("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n#EXT-X-ENDLIST"), "master: no segments")
        assertNull(MediaSniffer.hlsDurationSec("<html>#EXT-X-ENDLIST"))
    }
}
