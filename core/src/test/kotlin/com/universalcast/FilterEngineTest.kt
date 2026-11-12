package com.universalcast

import com.universalcast.sender.FilterEngine
import com.universalcast.sender.ReqType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilterEngineTest {
    private val list = """
        [Adblock Plus 2.0]
        ! comment
        ||ads.example.com^
        ||tracker.net^${'$'}third-party
        ||cdn.site.org/banners/
        /adframe/*/show.js
        |https://pop.example/x|
        ||metrics.io^${'$'}script,domain=news.com|~safe.news.com
        @@||ads.example.com/allowed/
        ||img.example^${'$'}image
        ##.ad-banner
        news.com##.sponsored
        shop.com#@#.ad-banner
        example.org##div:has(.ad)
        ##+js(abort-on-property-read, x)
    """.trimIndent()
    private val e = FilterEngine.parse(listOf(list))

    private fun blocked(url: String, page: String? = "page.org", t: ReqType = ReqType.SCRIPT) = e.shouldBlock(url, page, t)

    @Test fun hostAnchoredRulesBlockSubdomainsAndRespectSeparators() {
        assertTrue(blocked("https://ads.example.com/a.js"))
        assertTrue(blocked("https://x.ads.example.com/a.js"))
        assertTrue(blocked("https://ads.example.com"))
        assertFalse(blocked("https://ads.example.community/a.js"), "^ needs a separator")
        assertFalse(blocked("https://notads.example.com/a.js"))
        assertFalse(blocked("https://example.com/ads.example.com"))
    }

    @Test fun exceptionsWin() {
        assertFalse(blocked("https://ads.example.com/allowed/x.js"))
        assertTrue(blocked("https://ads.example.com/other/x.js"))
    }

    @Test fun thirdPartyOptionNeedsADifferentSite() {
        assertTrue(blocked("https://tracker.net/p.gif", page = "page.org"))
        assertFalse(blocked("https://tracker.net/p.gif", page = "www.tracker.net"))
    }

    @Test fun pathWildcardAndAnchors() {
        assertTrue(blocked("https://cdn.site.org/banners/1.png"))
        assertFalse(blocked("https://cdn.site.org/images/1.png"))
        assertTrue(blocked("https://z.com/x/adframe/a/b/show.js"))
        assertTrue(blocked("https://pop.example/x"))
        assertFalse(blocked("https://pop.example/xy"))
    }

    @Test fun domainOptionLimitsWhereARuleApplies() {
        assertTrue(blocked("https://metrics.io/m.js", page = "www.news.com"))
        assertFalse(blocked("https://metrics.io/m.js", page = "safe.news.com"))
        assertFalse(blocked("https://metrics.io/m.js", page = "other.org"))
    }

    @Test fun typeOptionsAreHonoured() {
        assertTrue(blocked("https://img.example/a", t = ReqType.IMAGE))
        assertFalse(blocked("https://img.example/a", t = ReqType.SCRIPT))
    }

    @Test fun cosmeticRulesGenericPerDomainAndExceptions() {
        assertTrue(e.cosmeticCss("other.org").contains(".ad-banner"))
        assertFalse(e.cosmeticCss("other.org").contains(".sponsored"))
        assertTrue(e.cosmeticCss("www.news.com").contains(".sponsored"))
        assertFalse(e.cosmeticCss("example.org").contains(":has"), "procedural selectors are skipped")
        assertFalse(e.cosmeticCss("x").contains("abort-on"), "scriptlets are skipped")
    }

    @Test fun nonWebUrlsAndGarbageNeverBlock() {
        for (u in listOf("blob:https://ads.example.com/1", "data:text/html,x", "javascript:void(0)", "", "https://")) assertFalse(blocked(u), u)
    }

    @Test fun registrableDomainHandlesTwoPartSuffixes() {
        assertEquals("example.co.uk", FilterEngine.registrable("a.b.example.co.uk"))
        assertEquals("example.com", FilterEngine.registrable("www.example.com"))
    }

    @Test fun overlyGenericRulesAreDropped() {
        val g = FilterEngine.parse(listOf("/ad\n&a=\n-ad-\n"))
        assertFalse(g.shouldBlock("https://site.com/ad/x", "site.com", ReqType.SCRIPT))
    }

    @Test fun isFastWithAHugeList() {
        val sb = StringBuilder()
        repeat(60_000) { sb.append("||host$it.ads$it.example^\n/banner$it/ad.js\n") }
        val big = FilterEngine.parse(listOf(sb.toString()))
        val t0 = System.nanoTime()
        repeat(20_000) { big.shouldBlock("https://cdn.video.example/seg-$it.ts?token=abc", "page.org", ReqType.MEDIA) }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000, "20k lookups against 120k rules")
        assertTrue(big.shouldBlock("https://host5.ads5.example/x", "page.org", ReqType.SCRIPT))
    }
}
