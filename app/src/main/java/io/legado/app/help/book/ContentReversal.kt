package io.legado.app.help.book

import io.legado.app.constant.AppPattern

// Keep reader markup in place: moving a paragraph's trailing review image would
// attach its click action to other text. In particular, never rewrite src JSON.
private val readerMarkup = Regex(
    "${AppPattern.useHtmlRegex.pattern}|${AppPattern.imgPattern.pattern()}|" +
        "\\[newpage\\]|<!--[\\s\\S]*?-->|</?[a-zA-Z][^<>]*>|" +
        "&(?:#\\d+|#x[\\da-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);",
    RegexOption.DOT_MATCHES_ALL,
)
private val richContentBoundary = Regex("${readerMarkup.pattern}|\\r\\n|\\r|\\n", RegexOption.DOT_MATCHES_ALL)

internal fun reverseContentText(content: String): String = buildString(content.length) {
    fun appendReversed(start: Int, end: Int) {
        var position = end
        while (position > start) {
            val codePoint = content.codePointBefore(position)
            appendCodePoint(codePoint)
            position -= Character.charCount(codePoint)
        }
    }
    var start = 0
    // Rich content keeps paragraph boundaries too, so its review/image remains
    // attached to the same paragraph. Unmarked text retains full reversal.
    val boundaries = if (readerMarkup.containsMatchIn(content)) richContentBoundary.findAll(content)
        else emptySequence()
    for (markup in boundaries) {
        appendReversed(start, markup.range.first)
        append(markup.value)
        start = markup.range.last + 1
    }
    appendReversed(start, content.length)
}
