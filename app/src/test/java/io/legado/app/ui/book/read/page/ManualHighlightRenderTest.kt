package io.legado.app.ui.book.read.page

import io.legado.app.data.entities.BookHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManualHighlightRenderTest {

    @Test
    fun `manual ranges cover every text column but skip titles`() {
        val content = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt")

        assertTrue(content.contains("ReadBook.highlightsOfChapter(chapter, titleLength)"))
        assertTrue(content.contains("(column as? TextBaseColumn)?.charData?.length ?: 0"))
        assertTrue(content.contains("isTitle = line.isTitle"))
        assertTrue(content.contains("if (column is TextBaseColumn)"))
    }

    @Test
    fun `layout captures the exact raw title prefix before body content`() {
        val layout = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/provider/TextChapterLayout.kt"
        )
        val capture = layout.indexOf("textChapter.layoutTitleLength =")
        val body = layout.indexOf("contents.forEach")

        assertTrue(capture in 0 until body)
        assertTrue(layout.contains("textPages.sumOf { it.text.length } + stringBuilder.length"))
        assertTrue(layout.contains("isTitle = isTitle"))
        assertTrue(layout.contains("isTitle = true"))
    }

    @Test
    fun `highlight creation rejects cross chapter selections`() {
        val content = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt")

        assertTrue(content.contains("if (startPage.chapterIndex != endPage.chapterIndex) return null"))
        assertTrue(content.contains("if (startLine.isTitle || endLine.isTitle) return null"))
        assertTrue(content.contains("highlightSelectionEndLength(selectEnd.columnIndex)"))
        assertTrue(content.contains("layoutTitleLength = titleLength"))
        assertTrue(content.contains("bookUrl = book.bookUrl"))
        assertTrue(content.contains("chapterUrl = chapter.chapter.url"))
    }

    @Test
    fun `line start boundary does not read or include the previous column`() {
        var invoked = false

        val length = highlightSelectionEndLength(-1) {
            invoked = true
            4
        }

        assertEquals(0, length)
        assertFalse(invoked)
    }

    @Test
    fun `paragraph breaks alone cannot create an invisible highlight`() {
        assertFalse("".hasHighlightableText())
        assertFalse("\r\n".hasHighlightableText())
        assertTrue(" ".hasHighlightableText())
        assertTrue("text\n".hasHighlightableText())
    }

    @Test
    fun `html links and review columns keep click priority`() {
        val content = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt")
        val click = content.indexOf("fun click(")
        val review = content.indexOf("is ReviewColumn ->", click)
        val html = content.indexOf("is TextHtmlColumn ->", review)
        val link = content.indexOf("if (linkUrl != null)", html)
        val highlight = content.indexOf("column.highlightStyle != null", html)
        val longPress = content.indexOf("fun longPress(")
        val longPressHtml = content.indexOf("is TextHtmlColumn ->", longPress)
        val manageHighlight = content.indexOf("notifyHighlightClick(", longPressHtml)
        val selectText = content.indexOf("column.selected = true", manageHighlight)

        assertTrue(review in 0 until html)
        assertTrue(link in html until highlight)
        assertTrue(manageHighlight in longPressHtml until selectText)
    }

    @Test
    fun `overlapping highlight click follows the last rendered range`() {
        val outer = BookHighlight(time = 1, chapterPos = 0, chapterPosEnd = 10)
        val inner = BookHighlight(time = 2, chapterPos = 2, chapterPosEnd = 4)

        assertSame(inner, listOf(outer, inner).lastHighlightAt(position = 3, titleLength = 0))
    }

    @Test
    fun `column drawing uses isolated temporary paint styles`() {
        val text = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/column/TextColumn.kt")
        val html = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/column/TextHtmlColumn.kt")
        val draw = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/HighlightDraw.kt")

        assertTrue(text.contains("HighlightDraw.obtainTextPaint(textPaint, it, textColor)"))
        assertTrue(text.contains("HighlightDraw::recycleTextPaint"))
        assertTrue(html.contains("HighlightDraw.obtainTextPaint(textPaint, it, textColor)"))
        assertTrue(html.contains("HighlightDraw::recycleTextPaint"))
        assertTrue(draw.contains("ThreadLocal<DrawState>"))
    }

    @Test
    fun `underline variants do not depend on clipped or path-effect drawing`() {
        val draw = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/HighlightDraw.kt")

        assertFalse(draw.contains("DashPathEffect"))
        assertTrue(draw.contains("height - 3.5f.dpToPx()"))
        assertTrue(draw.contains("while (start < x1)"))
        assertTrue(draw.contains("canvas.drawCircle(center, y, radius, fillPaint)"))
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first(File::isFile)
            .readText()
    }
}
