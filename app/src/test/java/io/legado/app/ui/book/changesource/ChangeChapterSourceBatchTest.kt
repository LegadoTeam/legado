package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeChapterSourceBatchTest {

    @Test
    fun `selected target chapters keep toc order and next urls`() {
        val chapters = listOf(
            BookChapter(index = 10, url = "volume", isVolume = true),
            BookChapter(index = 11, url = "part-1"),
            BookChapter(index = 12, url = "part-2"),
            BookChapter(index = 13, url = "next"),
        )

        assertEquals(
            listOf("part-1" to "part-2", "part-2" to "next"),
            selectedChapterSourceItems(chapters, setOf(12, 10, 11)).map { (chapter, next) ->
                chapter.url to next
            },
        )
    }

    @Test
    fun `merged content only inserts a line break after sentence punctuation`() {
        assertEquals(
            "第一段。\n第二段第三段！\n第四段",
            mergeChapterSourceContents(
                listOf("第一段。", "第二段", "第三段！", "第四段")
            ),
        )
        assertEquals(
            "第一段。\n第二段",
            mergeChapterSourceContents(listOf("第一段。\n", "第二段")),
        )
        assertEquals(
            "第一段。\n 第二段",
            mergeChapterSourceContents(listOf("第一段。\n ", "第二段")),
        )
    }

    @Test
    fun `next original chapter skips volume rows`() {
        val chapters = listOf(
            BookChapter(index = 8, title = "当前章"),
            BookChapter(index = 9, title = "第二卷", isVolume = true),
            BookChapter(index = 10, title = "下一章"),
        )

        assertEquals(10, nextChapterSourceOriginal(chapters, 8)?.index)
        assertNull(nextChapterSourceOriginal(chapters, 10))
    }

    @Test
    fun `batch progress survives repeated fragment initialization`() {
        val progress = ChapterSourceProgress()
        val chapters = listOf(
            BookChapter(index = 8, title = "当前章"),
            BookChapter(index = 9, title = "第二卷", isVolume = true),
            BookChapter(index = 10, title = "下一章"),
        )

        progress.initialize(8, "当前章")
        assertEquals(10, progress.advance(chapters, chapters.first())?.index)
        progress.initialize(8, "重建参数中的旧章节")

        assertEquals(10, progress.chapterIndex)
        assertEquals("下一章", progress.chapterTitle)
        assertEquals(10, progress.currentChapter(chapters)?.index)
    }

    @Test
    fun `batch progress records completion without an attached view`() {
        val progress = ChapterSourceProgress()
        val chapter = BookChapter(index = 8, title = "最后一章")
        progress.initialize(chapter.index, chapter.title)

        assertNull(progress.advance(listOf(chapter), chapter))
        assertEquals(true, progress.isFinished)
        assertNull(progress.currentChapter(listOf(chapter)))
    }

    @Test
    fun `automation range is inclusive and excludes volume rows`() {
        val chapters = listOf(
            BookChapter(index = 1, title = "第一章"),
            BookChapter(index = 2, title = "第二卷", isVolume = true),
            BookChapter(index = 3, title = "第二章"),
            BookChapter(index = 4, title = "第三章"),
        )

        assertEquals(
            listOf(1, 3),
            chapterSourceAutomationRange(chapters, start = 1, endInclusive = 2)
                .map(BookChapter::index),
        )
        assertTrue(chapterSourceAutomationRange(chapters, 0, 2).isEmpty())
        assertTrue(chapterSourceAutomationRange(chapters, 3, 2).isEmpty())
        assertTrue(chapterSourceAutomationRange(chapters, 1, 4).isEmpty())
    }

    @Test
    fun `automation session advances each original chapter once`() {
        val first = BookChapter(index = 8, title = "第一章")
        val second = BookChapter(index = 10, title = "第二章")
        val session = ChapterSourceAutomationSession(
            id = 7,
            originalBook = Book(bookUrl = "original"),
            chapters = listOf(first, second),
            targetBook = Book(bookUrl = "target"),
            targetToc = listOf(BookChapter(index = 20, title = "目标章")),
        )

        assertTrue(session.advance(first.index))
        assertEquals(second, session.currentChapter)
        assertFalse(session.advance(first.index))
        assertEquals(second, session.currentChapter)
        session.requestStopAfterCurrent()
        assertTrue(session.stopAfterCurrent)
        assertTrue(session.advance(second.index))
        assertNull(session.currentChapter)
    }
}
