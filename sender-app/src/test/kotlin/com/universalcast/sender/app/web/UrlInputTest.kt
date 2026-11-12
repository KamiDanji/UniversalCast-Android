package com.universalcast.sender.app.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlInputTest {
    @Test fun addsHttpsWhenMissing() {
        assertEquals("https://example.org", UrlInput.normalize("example.org"))
        assertEquals("https://example.org/watch?v=1#t", UrlInput.normalize("  example.org/watch?v=1#t  "))
        assertEquals("https://sub.example.co.uk/a", UrlInput.normalize("sub.example.co.uk/a"))
        assertEquals("https://example.org:8443/x", UrlInput.normalize("example.org:8443/x"))
        assertEquals("https://localhost:8080/", UrlInput.normalize("localhost:8080/"))
    }

    @Test fun keepsWebUrls() {
        assertEquals("https://example.org/a", UrlInput.normalize("https://example.org/a"))
        assertEquals("http://example.org", UrlInput.normalize("http://example.org"))
        assertEquals("HTTPS://Example.org", UrlInput.normalize("HTTPS://Example.org"))
    }

    @Test fun rejectsEverythingElse() {
        for (bad in listOf("", "   ", "one piece episode 1", "javascript:alert(1)", "file:///sdcard/a.html", "intent://x#Intent;end",
            "data:text/html,hi", "about:blank", "content://x", "https://", "http:/example.org", "/local/path", "./x", "word", "a b.com")) {
            assertNull(UrlInput.normalize(bad), bad)
        }
    }
}
