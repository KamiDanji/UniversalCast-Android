package com.universalcast.sender.app.web

import com.universalcast.playback.MediaHeaders
import org.junit.Test
import kotlin.test.assertEquals

class MediaCandidatesTest {
    private val stream = "https://cdn.example/hls/ep1/master.m3u8?token=t"

    @Test fun reportedHeadersWinAndOnlyAllowlistedOnesAreForwarded() {
        val h = MediaCandidates.forwardable(
            stream,
            mapOf("referer" to "https://player.example/embed/1", "ORIGIN" to "https://player.example", "User-Agent" to "PageUA", "Accept" to "*/*", "Cookie" to "x=1", "Range" to "bytes=0-"),
            refererFallback = "https://site.example/watch", userAgent = "WebViewUA",
        )
        assertEquals(mapOf("Referer" to "https://player.example/embed/1", "Origin" to "https://player.example", "User-Agent" to "PageUA"), h)
        assertEquals(h, MediaHeaders.validate(h)) // what the phone sends is exactly what the TV accepts
    }

    @Test fun missingHeadersAreCompletedLikeTheBrowserWould() {
        // WebView reported nothing: the player iframe becomes Referer, its origin the Origin (cross-site), WebView UA is used.
        assertEquals(
            mapOf("Referer" to "https://vidstream.example/e/abc", "Origin" to "https://vidstream.example", "User-Agent" to "WebViewUA"),
            MediaCandidates.forwardable(stream, emptyMap(), "https://vidstream.example/e/abc", "WebViewUA"),
        )
        // Same-site stream: browsers send no Origin on a plain GET, so neither do we.
        assertEquals(
            mapOf("Referer" to "https://cdn.example/watch/1"),
            MediaCandidates.forwardable(stream, emptyMap(), "https://cdn.example/watch/1", null),
        )
    }

    @Test fun badValuesAreDroppedNotTruncated() {
        assertEquals(emptyMap(), MediaCandidates.forwardable(stream, mapOf("Referer" to "https://a/" + "x".repeat(3000), "Origin" to "null"), "about:blank", "x".repeat(600)))
        assertEquals("https://h:8443", MediaCandidates.originOf("https://h:8443/p?q"))
    }
}
