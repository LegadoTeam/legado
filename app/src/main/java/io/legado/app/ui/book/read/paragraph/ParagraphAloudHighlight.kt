package io.legado.app.ui.book.read.paragraph

/** The spoken position, scoped to its book, chapter and complete display item. */
internal data class ParagraphAloudHighlight(
    val bookUrl: String,
    val chapterUrl: String,
    val itemId: String,
    val chapterPosition: Int,
) {
    fun matchesChapter(bookUrl: String?, chapterUrl: String?): Boolean =
        this.bookUrl == bookUrl && this.chapterUrl == chapterUrl

    /** Start of the remaining spoken paragraph, in UTF-16 display coordinates. */
    fun textStart(item: ParagraphItem): Int? {
        if (item.id != itemId || item.isImage || item.text.isEmpty() || chapterPosition < 0) return null
        val start = if (item.positionRanges.isEmpty()) {
            val offset = chapterPosition.toLong() - item.chapterPosition
            offset.takeIf { it >= 0 && it < item.text.length }?.toInt()
        } else {
            item.positionRanges.firstNotNullOfOrNull { range ->
                if (range.textStart < 0 || range.textEnd > item.text.length) return@firstNotNullOfOrNull null
                val offset = chapterPosition.toLong() - range.chapterStart
                val length = range.textEnd.toLong() - range.textStart
                if (offset >= 0 && offset < length) range.textStart + offset.toInt() else null
            }
        } ?: return null
        // A TTS engine may report either UTF-16 unit of an emoji. Keep its visual glyph whole.
        return if (start > 0 && Character.isLowSurrogate(item.text[start]) &&
            Character.isHighSurrogate(item.text[start - 1])
        ) start - 1 else start
    }
}
