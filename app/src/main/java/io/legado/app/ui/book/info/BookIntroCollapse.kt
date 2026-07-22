package io.legado.app.ui.book.info

internal object BookIntroCollapse {

    const val COLLAPSED_LINES = 4

    fun hasOverflow(
        expanded: Boolean,
        lineCount: Int,
        lastLineEllipsisCount: Int,
        lastLineEnd: Int,
        textLength: Int,
        contentHeight: Int,
        collapsedContentHeight: Int,
    ): Boolean {
        if (lineCount <= 0 || textLength <= 0) return false
        if (expanded) {
            return lineCount > COLLAPSED_LINES || contentHeight > collapsedContentHeight
        }
        return lineCount > COLLAPSED_LINES ||
            lastLineEllipsisCount > 0 ||
            lastLineEnd < textLength
    }
}
