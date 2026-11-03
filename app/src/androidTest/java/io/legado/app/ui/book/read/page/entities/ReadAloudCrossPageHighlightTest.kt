package io.legado.app.ui.book.read.page.entities

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadAloudCrossPageHighlightTest {
    private fun page() = TextPage(text = "后半段\n下一段", title = "").apply {
        addLine(TextLine(text = "后半段", paragraphNum = 1, isParagraphEnd = true))
        addLine(TextLine(text = "下一段", paragraphNum = 2, isParagraphEnd = true))
    }

    @Test
    fun paragraphStartingOnPreviousPageHighlightsItsContinuationOnly() {
        val page = page()
        page.upPageAloudSpan(-3)
        assertTrue(page.lines[0].isReadAloud)
        assertFalse(page.lines[1].isReadAloud)
        assertTrue(page.hasReadAloudSpan)
    }

    @Test
    fun movingToNextParagraphClearsThePreviousHighlight() {
        val page = page()
        page.upPageAloudSpan(-3)
        page.upPageAloudSpan(4)
        assertEquals(listOf(false, true), page.lines.map { it.isReadAloud })
        page.removePageAloudSpan()
        assertEquals(listOf(false, false), page.lines.map { it.isReadAloud })
        assertFalse(page.hasReadAloudSpan)
    }

    @Test
    fun pagesAfterTheParagraphAreNotHighlighted() {
        val page = page()
        page.upPageAloudSpan(-10, -2)
        assertEquals(listOf(false, false), page.lines.map { it.isReadAloud })
        assertFalse(page.hasReadAloudSpan)
    }

    @Test
    fun paragraphMayContinueThroughAnEntirePage() {
        val page = TextPage(text = "中间页", title = "").apply {
            addLine(TextLine(text = "中间页", paragraphNum = 1))
        }
        page.upPageAloudSpan(-3, 10)
        assertTrue(page.lines.single().isReadAloud)
    }

    @Test
    fun speechPositionHighlightsWholeParagraphOnBothPagesAndClearsOldPages() {
        val first = TextPage(text = "前半段", title = "").apply {
            addLine(TextLine(text = "前半段", paragraphNum = 1, chapterPosition = 0))
        }
        val second = page().apply {
            lines[0].chapterPosition = 3
            lines[1].chapterPosition = 7
        }
        val chapter = TextChapter(BookChapter(), 0, "", 1, false, false, false, null)
        // Populate the same page collection that the layout normally produces.
        val field = TextChapter::class.java.getDeclaredField("textPages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(chapter) as MutableList<TextPage>).addAll(listOf(first, second))
        chapter.isCompleted = true

        chapter.upParagraphAloudSpan(4)
        assertTrue(first.lines.single().isReadAloud)
        assertEquals(listOf(true, false), second.lines.map { it.isReadAloud })
        chapter.upParagraphAloudSpan(8)
        assertFalse(first.hasReadAloudSpan)
        assertEquals(listOf(false, true), second.lines.map { it.isReadAloud })
        chapter.upParagraphAloudSpan(-1)
        assertFalse(second.hasReadAloudSpan)
    }
}
