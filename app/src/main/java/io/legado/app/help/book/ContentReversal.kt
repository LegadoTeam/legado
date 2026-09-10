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
private val mirroredSymbols = buildMap {
    "“”‘’【】()<>{}[]（）《》〈〉〖〗〔〕『』「」｛｝≤≥≦≧⊆⊇⊂⊃◢◣◤◥←→↖↗↙↘☜☞꧁꧂╭╮╰╯«»〝〞＜＞［］｢｣"
        .chunked(2).forEach { pair ->
            put(pair[0].code, pair[1].code)
            put(pair[1].code, pair[0].code)
        }
}

internal fun reverseContentText(content: String): String = buildString(content.length) {
    val richContent = readerMarkup.containsMatchIn(content)
    fun appendReversed(start: Int, end: Int) {
        var textStart = start
        var textEnd = end
        if (richContent && (start == 0 || content[start - 1] == '\n' || content[start - 1] == '\r')) {
            // Keep paragraph indentation at the edge, not between text and its review bubble.
            while (textStart < textEnd && content[textStart].isWhitespace()) textStart++
            while (textEnd > textStart && content[textEnd - 1].isWhitespace()) textEnd--
        }
        append(content, start, textStart)
        var position = textEnd
        while (position > textStart) {
            val codePoint = content.codePointBefore(position)
            appendCodePoint(mirroredSymbols[codePoint] ?: codePoint)
            position -= Character.charCount(codePoint)
        }
        append(content, textEnd, end)
    }
    var start = 0
    // Rich content keeps paragraph boundaries too, so its review/image remains
    // attached to the same paragraph. Unmarked text retains full reversal.
    val boundaries = if (richContent) richContentBoundary.findAll(content)
        else emptySequence()
    for (markup in boundaries) {
        appendReversed(start, markup.range.first)
        append(markup.value)
        start = markup.range.last + 1
    }
    appendReversed(start, content.length)
}
