package io.legado.app.ui.book.read.page

import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn

/** Use layout positions, which include the title, paragraphs and inline image placeholders. */
internal fun findPdfPagePosition(page: TextPage, pdfPageIndex: Int): Int? {
    val source = pdfPageIndex.toString()
    for (line in page.lines) {
        var offset = 0
        for (column in line.columns) {
            if (column is ImageColumn && column.src == source) return line.chapterPosition + offset
            offset += column.positionLength
        }
    }
    return null
}
