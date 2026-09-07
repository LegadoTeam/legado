package io.legado.app.ui.book.read

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.ui.book.read.page.findPdfPagePosition
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfPagePositionTest {
    @Test
    fun findsActualImageOffsetAfterTitleAndUnicodeColumns() {
        val page = TextPage(text = "", title = "目录标题").apply {
            addLine(TextLine(chapterPosition = 24, isTitle = true).apply {
                addColumn(TextColumn(0f, 10f, "目录标题"))
            })
            addLine(TextLine(chapterPosition = 81).apply {
                addColumn(TextColumn(0f, 10f, "😀"))
                addColumn(ImageColumn(10f, 20f, "12"))
                addColumn(ImageColumn(20f, 30f, "13"))
            })
        }
        assertEquals(83, findPdfPagePosition(page, 12))
        assertEquals(84, findPdfPagePosition(page, 13))
        assertNull(findPdfPagePosition(page, 1))
        assertNull(findPdfPagePosition(TextPage(text = "", title = ""), 12))
    }
}
