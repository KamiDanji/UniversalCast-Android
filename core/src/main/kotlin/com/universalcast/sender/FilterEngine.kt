package com.universalcast.sender

import java.net.URI

/** What a request is for, as far as a WebView lets us tell (it gives no real resource type). */
enum class ReqType { DOCUMENT, SUBDOCUMENT, SCRIPT, IMAGE, STYLESHEET, MEDIA, XHR, OTHER }

/**
 * A uBlock-style content blocker: reads Adblock-Plus/uBlock filter lists (EasyList, uBlock filters, …) and answers
 * "block this request?" plus the CSS selectors to hide on a page. Supported syntax: `||domain^`, `|` anchors, `*` and
 * `^` wildcards, `@@` exceptions, `$third-party`, `$domain=`, resource-type options, and `##selector` / `domain##selector`
 * element hiding. Scriptlets, `:has()`/procedural cosmetics and redirect rules are skipped (not possible in a WebView).
 * Immutable once built, thread-safe, and fast: rules are indexed by host or by a URL token, never scanned linearly.
 */
class FilterEngine private constructor(
    private val hostBlock: Map<String, List<Rule>>,
    private val tokenBlock: Map<String, List<Rule>>,
    private val looseBlock: List<Rule>,
    private val hostAllow: Map<String, List<Rule>>,
    private val tokenAllow: Map<String, List<Rule>>,
    private val looseAllow: List<Rule>,
    private val genericCss: List<String>,
    private val domainCss: Map<String, List<String>>,
    val ruleCount: Int,
) {
    class Rule(
        val segments: List<String>, val anchorStart: Boolean, val anchorHost: Boolean, val anchorEnd: Boolean,
        val thirdParty: Boolean?, val types: Set<ReqType>?, val notTypes: Set<ReqType>,
        val domainsInclude: Set<String>, val domainsExclude: Set<String>,
    )

    fun shouldBlock(url: String, pageHost: String?, type: ReqType): Boolean {
        val u = Url.parse(url) ?: return false
        val third = pageHost != null && registrable(u.host) != registrable(pageHost)
        if (matches(u, pageHost, third, type, hostAllow, tokenAllow, looseAllow)) return false
        return matches(u, pageHost, third, type, hostBlock, tokenBlock, looseBlock)
    }

    /** CSS that hides the page's ad elements: generic selectors plus those written for [host]. */
    fun cosmeticCss(host: String?): String {
        val sel = ArrayList<String>(genericCss)
        var h = host?.lowercase()
        while (h != null && h.isNotEmpty()) {
            domainCss[h]?.let { sel.addAll(it) }
            val dot = h.indexOf('.')
            h = if (dot < 0) null else h.substring(dot + 1)
        }
        if (sel.isEmpty()) return ""
        // Chunked: one invalid selector would otherwise void the whole rule it is in.
        return sel.chunked(1).joinToString("\n") { it.joinToString(",") + "{display:none!important}" }
    }

    private fun matches(
        u: Url, pageHost: String?, third: Boolean, type: ReqType,
        byHost: Map<String, List<Rule>>, byToken: Map<String, List<Rule>>, loose: List<Rule>,
    ): Boolean {
        var h: String = u.host
        while (true) {
            byHost[h]?.let { if (it.any { r -> ruleMatches(r, u, pageHost, third, type) }) return true }
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        for (tok in tokens(u.lower)) byToken[tok]?.let { if (it.any { r -> ruleMatches(r, u, pageHost, third, type) }) return true }
        return loose.any { ruleMatches(it, u, pageHost, third, type) }
    }

    private fun ruleMatches(r: Rule, u: Url, pageHost: String?, third: Boolean, type: ReqType): Boolean {
        if (r.thirdParty != null && r.thirdParty != third) return false
        val t = r.types
        if (t != null && type !in t && !(type == ReqType.OTHER && ReqType.XHR in t)) return false
        if (type in r.notTypes) return false
        if (r.domainsInclude.isNotEmpty() || r.domainsExclude.isNotEmpty()) {
            val ph = pageHost?.lowercase() ?: return r.domainsInclude.isEmpty()
            if (r.domainsExclude.any { ph == it || ph.endsWith(".$it") }) return false
            if (r.domainsInclude.isNotEmpty() && r.domainsInclude.none { ph == it || ph.endsWith(".$it") }) return false
        }
        return patternMatches(r, u)
    }

    private fun patternMatches(r: Rule, u: Url): Boolean {
        val s = u.lower
        if (r.segments.isEmpty()) return true
        // Candidate start offsets for the first segment.
        if (r.anchorHost) return u.hostStarts.any { matchFrom(r, s, it) }
        if (r.anchorStart) return matchFrom(r, s, 0)
        var from = 0
        val first = r.segments[0]
        while (from <= s.length) {
            val i = indexOfSeg(s, first, from)
            if (i < 0) return false
            if (matchFrom(r, s, i)) return true
            from = i + 1
        }
        return false
    }

    /** Matches all segments in order, segment 0 exactly at [start]. */
    private fun matchFrom(r: Rule, s: String, start: Int): Boolean {
        var pos = start
        for ((idx, seg) in r.segments.withIndex()) {
            val at = if (idx == 0) pos else indexOfSeg(s, seg, pos)
            if (at < 0) return false
            pos = matchSeg(s, seg, at)
            if (pos < 0) return false
        }
        return !r.anchorEnd || pos == s.length
    }

    /** End offset of [seg] matched at [at], or -1. `^` is one separator character, or the end of the URL (consuming nothing). */
    private fun matchSeg(s: String, seg: String, at: Int): Int {
        var i = at
        for (c in seg) {
            if (c == '^') {
                if (i == s.length) continue
                if (!isSeparator(s[i])) return -1
                i++
            } else {
                if (i >= s.length || s[i] != c) return -1
                i++
            }
        }
        return i
    }

    private fun indexOfSeg(s: String, seg: String, from: Int): Int {
        if ('^' !in seg) return s.indexOf(seg, from)
        for (i in from..s.length) if (matchSeg(s, seg, i) >= 0) return i
        return -1
    }

    private class Url(val lower: String, val host: String, val hostStarts: List<Int>) {
        companion object {
            fun parse(url: String): Url? {
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true) && !url.startsWith("ws://", true) && !url.startsWith("wss://", true)) return null
                val host = (runCatching { URI(url).host }.getOrNull()
                    ?: url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@').substringBefore(':'))
                    .trimEnd('.').lowercase()
                if (host.isEmpty()) return null
                val lower = url.lowercase()
                val hs = lower.indexOf("://") + 3
                val he = lower.indexOf(host, hs).let { if (it < 0) hs else it }
                // "||" may start at the host or at any label boundary inside it.
                val starts = arrayListOf(he)
                var i = he
                while (true) { val d = lower.indexOf('.', i); if (d < 0 || d >= he + host.length) break; starts.add(d + 1); i = d + 1 }
                return Url(lower, host, starts)
            }
        }
    }

    companion object {
        private val EMPTY = FilterEngine(emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyMap(), emptyList(), emptyList(), emptyMap(), 0)
        fun empty() = EMPTY

        private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")

        /** Approximation of the registrable domain (no public-suffix list): "a.b.example.co.uk" → "example.co.uk". */
        fun registrable(host: String): String {
            val p = host.lowercase().trimEnd('.').split('.')
            if (p.size <= 2) return p.joinToString(".")
            val n = if (p[p.size - 2] in SECOND_LEVEL && p.last().length == 2) 3 else 2
            return p.takeLast(n).joinToString(".")
        }

        private fun isSeparator(c: Char) = !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

        private fun tokens(url: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < url.length) {
                if (url[i].isLetterOrDigit()) {
                    var j = i
                    while (j < url.length && url[j].isLetterOrDigit()) j++
                    if (j - i >= 3) out.add(url.substring(i, j))
                    i = j
                } else i++
            }
            return out
        }

        /** Builds an engine from the text of one or more filter lists. */
        fun parse(lists: Collection<String>): FilterEngine {
            val hostB = HashMap<String, MutableList<Rule>>(); val tokB = HashMap<String, MutableList<Rule>>(); val looseB = ArrayList<Rule>()
            val hostA = HashMap<String, MutableList<Rule>>(); val tokA = HashMap<String, MutableList<Rule>>(); val looseA = ArrayList<Rule>()
            val generic = LinkedHashSet<String>(); val perDomain = HashMap<String, MutableList<String>>()
            val noCss = HashSet<String>() // generic `#@#` exceptions
            var count = 0
            for (text in lists) {
                for (raw in text.lineSequence()) {
                    val line = raw.trim()
                    if (line.isEmpty() || line[0] == '!' || line[0] == '[') continue
                    val cosm = line.indexOf("##")
                    val cosmEx = line.indexOf("#@#")
                    when {
                        cosmEx >= 0 && line.indexOf('$') !in 0 until cosmEx -> { if (cosmEx == 0) noCss.add(line.substring(3)) }
                        cosm >= 0 && !line.contains("#?#") -> {
                            val sel = line.substring(cosm + 2)
                            if (sel.startsWith("+js") || sel.contains(":has") || sel.contains(":-abp") || sel.contains(":xpath") || sel.contains(":matches") ||
                                sel.contains(":upward") || sel.contains(":remove") || sel.contains(":style") || sel.contains(":contains") || sel.contains("{")
                            ) continue
                            val doms = line.substring(0, cosm)
                            if (doms.isEmpty()) generic.add(sel)
                            else {
                                val inc = doms.split(',').filter { it.isNotEmpty() && !it.startsWith("~") && '*' !in it }
                                if (inc.isNotEmpty() && inc.size == doms.split(',').size) for (d in inc) perDomain.getOrPut(d.lowercase()) { ArrayList() }.add(sel)
                            }
                            count++
                        }
                        line.contains("#$#") || line.contains("#?#") || line.contains("#%#") || line.contains("##^") -> {}
                        else -> {
                            val rule = parseNetwork(line) ?: continue
                            count++
                            val allow = line.startsWith("@@")
                            val (hostMap, tokMap, loose) = if (allow) Triple(hostA, tokA, looseA) else Triple(hostB, tokB, looseB)
                            index(rule, hostMap, tokMap, loose)
                        }
                    }
                }
            }
            generic.removeAll(noCss)
            return FilterEngine(hostB, tokB, looseB, hostA, tokA, looseA, generic.toList(), perDomain, count)
        }

        private fun index(r: Rule, hostMap: MutableMap<String, MutableList<Rule>>, tokMap: MutableMap<String, MutableList<Rule>>, loose: MutableList<Rule>) {
            if (r.anchorHost && r.segments.isNotEmpty()) {
                val first = r.segments[0]
                val end = first.indexOfFirst { it == '^' || it == '/' || it == ':' || it == '?' || it == '*' }
                val hostPart = if (end < 0) first else first.substring(0, end)
                // Whole-label host ("||ads.example.com^") → index by host; partial label ("||ads*") → fall through.
                if (hostPart.contains('.') && end != 0 && (end >= 0 || r.segments.size == 1)) {
                    hostMap.getOrPut(hostPart) { ArrayList() }.add(r); return
                }
            }
            val tok = r.segments.flatMap { tokensOfPattern(it) }.maxByOrNull { it.length }
            if (tok != null) tokMap.getOrPut(tok) { ArrayList() }.add(r) else loose.add(r)
        }

        /** Tokens of a pattern segment that are surely whole tokens of any matching URL (not touching a wildcard/edge). */
        private fun tokensOfPattern(seg: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < seg.length) {
                if (seg[i].isLetterOrDigit()) {
                    var j = i
                    while (j < seg.length && seg[j].isLetterOrDigit()) j++
                    val leftOk = i > 0
                    val rightOk = j < seg.length
                    if (j - i >= 3 && leftOk && rightOk) out.add(seg.substring(i, j).lowercase())
                    i = j
                } else i++
            }
            return out
        }

        private val TYPE_NAMES = mapOf(
            "script" to ReqType.SCRIPT, "image" to ReqType.IMAGE, "stylesheet" to ReqType.STYLESHEET, "media" to ReqType.MEDIA,
            "subdocument" to ReqType.SUBDOCUMENT, "document" to ReqType.DOCUMENT, "xmlhttprequest" to ReqType.XHR, "xhr" to ReqType.XHR,
            "ping" to ReqType.OTHER, "other" to ReqType.OTHER, "font" to ReqType.OTHER, "websocket" to ReqType.OTHER, "object" to ReqType.OTHER,
        )

        internal fun parseNetwork(input: String): Rule? {
            var line = input
            if (line.startsWith("@@")) line = line.substring(2)
            var opts = ""
            val d = line.lastIndexOf('$')
            if (d >= 0 && !line.substring(d).contains('/')) { opts = line.substring(d + 1); line = line.substring(0, d) }
            if (line.startsWith("/") && line.endsWith("/") && line.length > 2) return null // regex rules: skipped
            var third: Boolean? = null
            var types: MutableSet<ReqType>? = null
            val notTypes = HashSet<ReqType>()
            val inc = HashSet<String>(); val exc = HashSet<String>()
            if (opts.isNotEmpty()) for (o in opts.split(',')) {
                val opt = o.trim().lowercase()
                when {
                    opt == "third-party" || opt == "3p" -> third = true
                    opt == "~third-party" || opt == "1p" || opt == "first-party" -> third = false
                    opt.startsWith("domain=") -> for (x in opt.substring(7).split('|')) if (x.startsWith("~")) exc.add(x.substring(1)) else if (x.isNotEmpty()) inc.add(x)
                    opt == "important" || opt == "match-case" || opt == "all" -> {}
                    opt == "popup" || opt == "csp" || opt.startsWith("redirect") || opt.startsWith("removeparam") || opt == "badfilter" ||
                        opt == "genericblock" || opt == "elemhide" || opt == "generichide" || opt == "specifichide" || opt.startsWith("header") ||
                        opt.startsWith("replace") || opt == "inline-script" || opt == "inline-font" || opt.startsWith("denyallow") || opt.startsWith("to=") -> return null
                    opt.startsWith("~") && TYPE_NAMES.containsKey(opt.substring(1)) -> notTypes.add(TYPE_NAMES.getValue(opt.substring(1)))
                    TYPE_NAMES.containsKey(opt) -> (types ?: HashSet<ReqType>().also { types = it }).add(TYPE_NAMES.getValue(opt))
                    else -> return null // unknown option: better to skip the rule than to over-block
                }
            }
            var anchorHost = false; var anchorStart = false; var anchorEnd = false
            if (line.startsWith("||")) { anchorHost = true; line = line.substring(2) }
            else if (line.startsWith("|")) { anchorStart = true; line = line.substring(1) }
            if (line.endsWith("|")) { anchorEnd = true; line = line.dropLast(1) }
            line = line.lowercase()
            if (line.isEmpty() && !anchorHost) return null
            val segs = line.split('*').filter { it.isNotEmpty() }
            if (segs.isEmpty() && inc.isEmpty() && types == null && third == null) return null // would match everything
            // A leading/trailing '*' removes the anchor on that side.
            if (line.startsWith("*")) { anchorStart = false; anchorHost = false }
            // Too-generic rules ("/ads", "&ad=") are common in lists but are the main source of broken pages; require some substance.
            if (!anchorHost && !anchorStart && segs.all { it.length < 4 } && inc.isEmpty()) return null
            return Rule(segs, anchorStart, anchorHost, anchorEnd, third, types, notTypes, inc, exc)
        }
    }
}
