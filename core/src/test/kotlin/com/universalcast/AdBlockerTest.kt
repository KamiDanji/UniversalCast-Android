package com.universalcast

import com.universalcast.sender.AdBlocker
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdBlockerTest {
    private val b = AdBlocker()

    @Test fun listedDomainsAndTheirSubdomainsAreBlocked() {
        for (url in listOf(
            "https://doubleclick.net/x", "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "http://c.adsterra.com/script.js?x=1",
            "https://ADS.PopAds.NET/pop.js", "https://www.popads.net./x", "https://cdn.exoclick.com:8443/a",
            "https://user:pw@onclickads.net/redirect", "https://example.popcash.net/x y|z",
        )) assertTrue(b.shouldBlock(url), url)
    }

    @Test fun lookAlikesVideoCdnsAndNonWebUrlsAreNotBlocked() {
        for (url in listOf(
            "https://notdoubleclick.net/x", "https://doubleclick.net.evil.example/x", "https://cdn.example/doubleclick.net",
            "https://cdn.example/ep1/master.m3u8", "https://onepace.example/watch", "https://www.youtube.com/watch?v=1",
            "https://vod.akamaized.net/a.m3u8", "https://d1.cloudfront.net/v.mp4", "https://storage.googleapis.com/v.mp4",
            "blob:https://popads.net/1", "data:text/html,doubleclick.net", "javascript:void(0)", "", "not a url", "https://",
        )) assertFalse(b.shouldBlock(url), url)
    }

    @Test fun bareTldsAndEmptyHostsNeverMatch() {
        val custom = AdBlocker(listOf("net", ".tracker.example", " "))
        assertFalse(custom.isBlockedHost("anything.net"), "a bare TLD entry must not block everything")
        assertTrue(custom.isBlockedHost("a.tracker.example"))
        assertTrue(custom.isBlockedHost("tracker.example"))
        assertFalse(custom.isBlockedHost(null))
        assertFalse(custom.isBlockedHost(""))
    }

    @Test fun isFastEnoughForEveryRequest() {
        val t0 = System.nanoTime()
        repeat(100_000) { b.shouldBlock("https://a.b.c.d.cdn.example/seg-$it.ts") }
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "100k lookups under 2 s")
    }
}
