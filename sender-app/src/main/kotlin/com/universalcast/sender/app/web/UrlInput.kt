package com.universalcast.sender.app.web

/** What the user typed into the browser's address field → a loadable URL, or null. No search: just addresses. */
object UrlInput {
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):(.*)$")

    fun normalize(input: String): String? {
        val t = input.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() || it.code < 0x20 }) return null
        val m = SCHEME.matchEntire(t)
        if (m != null) {
            val scheme = m.groupValues[1].lowercase()
            val rest = m.groupValues[2]
            if (scheme == "http" || scheme == "https") return if (rest.startsWith("//") && rest.length > 2) t else null
            // "localhost:8080/x" or "example.org:8443" is a host with a port, not a scheme.
            if (!(rest.firstOrNull()?.isDigit() == true)) return null // javascript:, file:, intent:, data:, about:…
        }
        if (t.startsWith("/") || t.startsWith(".")) return null
        val host = t.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        if (host.isEmpty() || (!host.contains('.') && !host.equals("localhost", ignoreCase = true))) return null
        return "https://$t"
    }
}
