package io.legado.app.help

object HighlightTextBuilder {

    data class LineInput(
        val text: String,
        val isParagraphEnd: Boolean
    )

    fun build(lines: List<LineInput>): String = buildString {
        lines.forEach { line ->
            append(line.text)
            if (line.isParagraphEnd) append('\n')
        }
    }
}
