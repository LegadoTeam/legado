package io.legado.app.ui.book.info

internal data class IntroIndentRange(
    val start: Int,
    val endExclusive: Int,
)

internal fun introIndentRanges(text: CharSequence): List<IntroIndentRange> {
    if (text.isEmpty()) return emptyList()
    val ranges = arrayListOf<IntroIndentRange>()
    var paragraphStart = 0
    while (paragraphStart < text.length) {
        val lineBreak = text.indexOf('\n', paragraphStart)
        val contentEnd = if (lineBreak >= 0) lineBreak else text.length
        val paragraphEnd = if (lineBreak >= 0) lineBreak + 1 else text.length
        if (contentEnd > paragraphStart && !text[paragraphStart].isWhitespace()) {
            ranges.add(IntroIndentRange(paragraphStart, paragraphEnd))
        }
        paragraphStart = paragraphEnd
    }
    return ranges
}
