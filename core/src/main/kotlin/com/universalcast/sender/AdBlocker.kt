package com.universalcast.sender

import java.net.URI

/**
 * Domain-based ad blocker for the cast browser. A request is blocked when its host is a listed domain or a subdomain
 * of one ("ads.example.com" matches "example.com", "badexample.com" does not). Lookups are a few hash-set probes, so
 * it is safe to call for every request on WebView network threads. Immutable and thread-safe.
 *
 * The built-in list targets ad, pop-under and redirect networks that streaming/anime sites commonly embed. It is
 * deliberately compact (not EasyList): it never lists video CDNs, so it can't break playback detection.
 */
class AdBlocker(domains: Collection<String> = DEFAULT_DOMAINS) {
    private val blocked: Set<String> = domains.map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toHashSet()

    /** True if [host] or one of its parent domains is listed. */
    fun isBlockedHost(host: String?): Boolean {
        var h = host?.trim()?.trimEnd('.')?.lowercase() ?: return false
        if (h.isEmpty()) return false
        while (true) {
            if (h in blocked) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if ('.' !in h) return false // never match a bare TLD
        }
    }

    /** True for an http(s) URL whose host is listed. Anything unparseable is not blocked (the page decides). */
    fun shouldBlock(url: String): Boolean {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return false
        val host = runCatching { URI(url).host }.getOrNull() ?: hostFallback(url)
        return isBlockedHost(host)
    }

    /** URI() rejects some real-world URLs (spaces, '|' in queries); the host is still easy to cut out. */
    private fun hostFallback(url: String): String? =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@').substringBefore(':')
            .takeIf { it.isNotEmpty() }

    companion object {
        val DEFAULT_DOMAINS: Set<String> = setOf(
            // Google ads / measurement
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "pagead2.googlesyndication.com", "google-analytics.com", "googletagservices.com", "imasdk.googleapis.com",
            // Big ad exchanges and content recommendation
            "amazon-adsystem.com", "adnxs.com", "adsrvr.org", "taboola.com", "outbrain.com", "criteo.com", "criteo.net",
            "pubmatic.com", "rubiconproject.com", "openx.net", "casalemedia.com", "smartadserver.com", "adform.net",
            "moatads.com", "scorecardresearch.com", "quantserve.com", "yieldmo.com", "media.net", "zedo.com",
            "revcontent.com", "mgid.com", "adskeeper.com", "adskeeper.co.uk", "bidswitch.net", "sharethrough.com",
            "teads.tv", "33across.com", "indexww.com", "contextweb.com", "lijit.com", "sovrn.com", "advertising.com",
            // Pop-under / redirect / push networks common on streaming and anime sites
            "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "adsterra.com", "adsterratech.com",
            "highperformanceformat.com", "profitablegatecpm.com", "effectivegatecpm.com", "displaycontentnetwork.com",
            "exoclick.com", "exosrv.com", "exdynsrv.com", "juicyads.com", "trafficjunky.net", "trafficstars.com",
            "hilltopads.net", "hilltopads.com", "a-ads.com", "adcash.com", "onclickads.net", "onclkds.com",
            "clickadu.com", "clickaine.com", "ad-maven.com", "admaven.com", "monetag.com", "galaksion.com",
            "pushground.com", "richpush.co", "evadav.com", "rollerads.com", "adspyglass.com", "bidvertiser.com",
            "pemsrv.com", "realsrv.com", "tsyndicate.com", "twinrdengine.com", "acint.net", "dtscout.com",
            "adtrafficquality.google",
        )
    }
}
