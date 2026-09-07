package io.legado.app.help

/** Resolves chapter-relative half-open highlight ranges onto laid-out text columns. */
object HighlightMatcher {
    data class Range(
        val start: Int,
        val end: Int,
        val style: HighlightStyle,
        val applyToTitle: Boolean = false,
        val applyToBody: Boolean = true
    )

    data class LineSpec(
        val charSize: Int,
        val columnCharLengths: List<Int>,
        val isParagraphEnd: Boolean,
        val isTitle: Boolean = false
    )

    fun resolve(
        pageBase: Int,
        lines: List<LineSpec>,
        ranges: List<Range>
    ): List<List<HighlightStyle?>> {
        val result = ArrayList<List<HighlightStyle?>>(lines.size)
        var lineBase = pageBase
        for (line in lines) {
            var columnPosition = lineBase
            val columnStyles = ArrayList<HighlightStyle?>(line.columnCharLengths.size)
            for (length in line.columnCharLengths) {
                val columnStart = columnPosition
                val columnEnd = columnPosition + length
                var style: HighlightStyle? = null
                for (range in ranges) {
                    if (line.isTitle && !range.applyToTitle ||
                        !line.isTitle && !range.applyToBody
                    ) continue
                    if (length > 0 && columnStart < range.end && columnEnd > range.start) {
                        style = HighlightStyle.merge(style, range.style)
                    }
                }
                columnStyles.add(style)
                columnPosition += length
            }
            result.add(columnStyles)
            lineBase += line.charSize + if (line.isParagraphEnd) 1 else 0
        }
        return result
    }
}
