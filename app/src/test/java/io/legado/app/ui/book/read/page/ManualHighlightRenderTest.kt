package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManualHighlightRenderTest {

    @Test
    fun `manual ranges cover every text column but skip titles`() {
        val content = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt")

        assertTrue(content.contains("ReadBook.anchoredHighlightsOfChapter(chapter, titleLength)"))
        assertTrue(content.contains("anchor.start + titleLength"))
        assertTrue(content.contains("anchor.end + titleLength"))
        assertTrue(content.contains(".lastOrNull { (_, anchor)"))
        assertTrue(content.contains("val pageBase = chapter.getReadLength(page.index)"))
        assertTrue(content.contains("val ruleRanges = ReadBook.ruleMatchesOfChapter(chapter)"))
        assertTrue(content.contains("line.columns.map { it.positionLength }"))
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
        assertTrue(content.contains("if (page.getLine(textPos.lineIndex).isTitle) return null"))
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
    fun `column drawing uses isolated temporary paint styles`() {
        val text = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/column/TextColumn.kt")
        val html = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/column/TextHtmlColumn.kt")
        val draw = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/HighlightDraw.kt")

        assertTrue(text.contains("HighlightDraw.obtainTextPaint(textPaint, it, textColor)"))
        assertTrue(text.contains("HighlightDraw::recycleTextPaint"))
        assertTrue(html.contains("HighlightDraw.obtainTextPaint(textPaint, it, textColor)"))
        assertTrue(html.contains("HighlightDraw::recycleTextPaint"))
        assertTrue(draw.contains("ThreadLocal<DrawState>"))
        assertTrue(text.contains("it.shadow != null"))
        assertTrue(html.contains("it.shadow != null"))
        assertTrue(draw.contains("paint.setShadowLayer(it.radius, it.dx, it.dy, it.color)"))
    }

    @Test
    fun `underline variants do not depend on clipped or path-effect drawing`() {
        val draw = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/HighlightDraw.kt")

        assertFalse(draw.contains("DashPathEffect"))
        assertTrue(draw.contains("height - 3.5f.dpToPx()"))
        assertTrue(draw.contains("while (start < x1)"))
        assertTrue(draw.contains("canvas.drawCircle(center, y, radius, fillPaint)"))
    }

    @Test
    fun `fill shapes share one run renderer across fast and styled text`() {
        val line = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/TextLine.kt")
        val text = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/column/TextColumn.kt")
        val html = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/column/TextHtmlColumn.kt")
        val draw = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/HighlightDraw.kt")

        assertTrue(line.indexOf("drawHighlightFills(canvas)") < line.indexOf("checkFastDraw()"))
        assertTrue(line.contains("nextStyle.resolvedFillShape == shape"))
        assertTrue(line.contains("nextTextSize == textSize"))
        assertTrue(draw.contains("fun drawFillRun("))
        assertTrue(draw.contains("val inset = strokePaint.strokeWidth / 2f"))
        assertTrue(draw.contains("top + inset"))
        assertTrue(draw.contains("bottom - inset"))
        assertFalse(text.contains("highlightPaint("))
        assertFalse(html.contains("highlightPaint("))
    }

    @Test
    fun `run decorations follow html text size`() {
        val line = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/TextLine.kt")
        val draw = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/HighlightDraw.kt")

        assertTrue(line.contains("val sizeSensitive = strike != null || box != null"))
        assertTrue(line.contains("val sameTextSize = !sizeSensitive ||"))
        assertTrue(line.contains("val metricScale = textSize / baseTextSize"))
        assertTrue(draw.contains("if (right > left && bottom > top)"))
    }

    @Test
    fun `html horizontal rules consume one chapter position`() {
        val column = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/column/BaseColumn.kt"
        )
        val baseColumn = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/column/TextBaseColumn.kt"
        )
        val htmlColumn = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/column/TextHtmlColumn.kt"
        )
        val page = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/TextPage.kt"
        )

        assertTrue(column.contains("val positionLength: Int get() = 0"))
        assertTrue(baseColumn.contains("override val positionLength: Int get() = charData.length"))
        assertTrue(htmlColumn.contains("charData == HR_PLACE_STR"))
        assertTrue(htmlColumn.contains("HR_PLACE_CHAR.length"))
        assertTrue(page.contains("length += columns[index].positionLength"))
    }

    @Test
    fun `inline images consume one chapter position without counting review controls`() {
        val image = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/column/ImageColumn.kt"
        )
        val content = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt"
        )

        assertTrue(image.contains("override val positionLength: Int = 1"))
        assertTrue(content.contains("line.columns.map { it.positionLength }"))
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first(File::isFile)
            .readText()
    }
}
