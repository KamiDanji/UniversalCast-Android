package com.universalcast.updater

/** Structured block parsed from GitHub-Flavored Markdown release notes. */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val spans: List<MarkdownSpan>) : MarkdownBlock
    data class ListItem(val bullet: String, val indentLevel: Int, val spans: List<MarkdownSpan>) : MarkdownBlock
    data class CodeBlock(val code: String) : MarkdownBlock
    data object Divider : MarkdownBlock
    data class Paragraph(val spans: List<MarkdownSpan>) : MarkdownBlock
}

/** Inline styled span inside a [MarkdownBlock]. */
sealed interface MarkdownSpan {
    data class Plain(val text: String) : MarkdownSpan
    data class Bold(val text: String) : MarkdownSpan
    data class Italic(val text: String) : MarkdownSpan
    data class Code(val text: String) : MarkdownSpan
    data class Link(val label: String, val url: String) : MarkdownSpan
}

/**
 * Pure Kotlin parser for GitHub release notes Markdown: headings (`#`..`####`), bullet and numbered lists
 * (including nested indentation), fenced code blocks (` ``` `), horizontal rules (`---`), and inline
 * `**bold**`, `*italic*`, `` `code` ``, and `[label](url)` spans.
 */
object MarkdownParser {
    private val NUMBERED_ITEM = Regex("""^(\d+)[.)]\s+(.*)$""")
    private val HEADING = Regex("""^(#{1,4})\s+(.*)$""")
    private val HR = Regex("""^([-*_])\s*\1\s*\1[\s\-*_]*$""")

    fun parse(markdown: String): List<MarkdownBlock> {
        val out = ArrayList<MarkdownBlock>()
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
        var i = 0
        val paragraphBuf = StringBuilder()

        fun flushParagraph() {
            val text = paragraphBuf.toString().trim()
            if (text.isNotEmpty()) {
                out.add(MarkdownBlock.Paragraph(parseInlines(text)))
            }
            paragraphBuf.setLength(0)
        }

        while (i < lines.size) {
            val rawLine = lines[i]
            val trimmed = rawLine.trim()

            // Fenced code block
            if (trimmed.startsWith("```")) {
                flushParagraph()
                i++
                val codeBuf = StringBuilder()
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (codeBuf.isNotEmpty()) codeBuf.append('\n')
                    codeBuf.append(lines[i])
                    i++
                }
                if (i < lines.size) i++ // skip closing ```
                out.add(MarkdownBlock.CodeBlock(codeBuf.toString()))
                continue
            }

            // Blank line ends current paragraph
            if (trimmed.isEmpty()) {
                flushParagraph()
                i++
                continue
            }

            // Horizontal rule
            if (HR.matches(trimmed)) {
                flushParagraph()
                out.add(MarkdownBlock.Divider)
                i++
                continue
            }

            // Heading
            val headingMatch = HEADING.matchEntire(trimmed)
            if (headingMatch != null) {
                flushParagraph()
                val level = headingMatch.groupValues[1].length
                val content = headingMatch.groupValues[2].trim()
                out.add(MarkdownBlock.Heading(level, parseInlines(content)))
                i++
                continue
            }

            // Bullet list item (-, *, •)
            val leadingSpaces = rawLine.takeWhile { it == ' ' || it == '\t' }.count { if (it == '\t') true else true }
            val indent = (rawLine.takeWhile { it == ' ' }.length / 2) + rawLine.takeWhile { it == '\t' }.length
            if ((trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ")) && trimmed.length > 2) {
                flushParagraph()
                val content = trimmed.substring(2).trim()
                out.add(MarkdownBlock.ListItem(if (indent > 0) "◦" else "•", indent.coerceAtMost(3), parseInlines(content)))
                i++
                continue
            }

            // Numbered list item (1. ...)
            val numMatch = NUMBERED_ITEM.matchEntire(trimmed)
            if (numMatch != null) {
                flushParagraph()
                val num = numMatch.groupValues[1]
                val content = numMatch.groupValues[2].trim()
                out.add(MarkdownBlock.ListItem("$num.", indent.coerceAtMost(3), parseInlines(content)))
                i++
                continue
            }

            // Regular paragraph line
            if (paragraphBuf.isNotEmpty()) paragraphBuf.append(' ')
            paragraphBuf.append(trimmed)
            i++
        }
        flushParagraph()
        return out
    }

    fun parseInlines(text: String): List<MarkdownSpan> {
        val out = ArrayList<MarkdownSpan>()
        val plain = StringBuilder()
        var i = 0

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out.add(MarkdownSpan.Plain(plain.toString()))
                plain.setLength(0)
            }
        }

        while (i < text.length) {
            // Inline code `...`
            if (text[i] == '`') {
                val close = text.indexOf('`', i + 1)
                if (close > i + 1) {
                    flushPlain()
                    out.add(MarkdownSpan.Code(text.substring(i + 1, close)))
                    i = close + 1
                    continue
                }
            }

            // Bold **...** or __...__
            if (i + 1 < text.length && ((text[i] == '*' && text[i + 1] == '*') || (text[i] == '_' && text[i + 1] == '_'))) {
                val delim = text.substring(i, i + 2)
                val close = text.indexOf(delim, i + 2)
                if (close > i + 2) {
                    flushPlain()
                    out.add(MarkdownSpan.Bold(stripNestedMarkers(text.substring(i + 2, close))))
                    i = close + 2
                    continue
                }
            }

            // Link [label](url)
            if (text[i] == '[') {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket > i + 1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen > closeBracket + 2) {
                        flushPlain()
                        val label = stripNestedMarkers(text.substring(i + 1, closeBracket))
                        val url = text.substring(closeBracket + 2, closeParen).trim()
                        out.add(MarkdownSpan.Link(label, url))
                        i = closeParen + 1
                        continue
                    }
                }
            }

            // Italic *...* or _..._
            if (text[i] == '*' || text[i] == '_') {
                val ch = text[i]
                val prevIsWord = i > 0 && text[i - 1].isLetterOrDigit()
                if (!prevIsWord) {
                    val close = text.indexOf(ch, i + 1)
                    if (close > i + 1 && !text[i + 1].isWhitespace()) {
                        flushPlain()
                        out.add(MarkdownSpan.Italic(stripNestedMarkers(text.substring(i + 1, close))))
                        i = close + 1
                        continue
                    }
                }
            }

            plain.append(text[i])
            i++
        }
        flushPlain()
        return out
    }

    private fun stripNestedMarkers(s: String): String =
        s.replace("**", "").replace("__", "").replace("`", "")
}
