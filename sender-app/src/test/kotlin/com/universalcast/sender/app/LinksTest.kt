package com.universalcast.sender.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinksTest {
    @Test fun extractsUrlFromSharedText() {
        assertEquals("https://example.org/v.mp4", Links.extractHttpUrl("Watch this: https://example.org/v.mp4"))
        assertEquals("http://a.example/x?y=1&z=2", Links.extractHttpUrl("http://a.example/x?y=1&z=2."))
        assertEquals("https://example.org/a", Links.extractHttpUrl("(see https://example.org/a)"))
        assertEquals("https://first.example/1", Links.extractHttpUrl("https://first.example/1 and https://second.example/2"))
    }

    @Test fun ignoresNonWebText() {
        assertNull(Links.extractHttpUrl(null))
        assertNull(Links.extractHttpUrl(""))
        assertNull(Links.extractHttpUrl("just words"))
        assertNull(Links.extractHttpUrl("ftp://example.org/x"))
        assertNull(Links.extractHttpUrl("file:///sdcard/a.mp4"))
    }
}
