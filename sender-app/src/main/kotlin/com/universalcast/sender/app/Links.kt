package com.universalcast.sender.app

object Links {
    private val URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** First http(s) URL inside shared text ("Watch this: https://…"), without trailing punctuation. */
    fun extractHttpUrl(text: String?): String? {
        val m = URL.find(text ?: return null)?.value ?: return null
        return m.trimEnd('.', ',', ';', ':', ')', ']', '!', '?').takeIf { it.length > 8 }
    }
}
