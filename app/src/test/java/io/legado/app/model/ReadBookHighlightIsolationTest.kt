package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookHighlight
import io.legado.app.ui.book.read.page.entities.TextChapter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadBookHighlightIsolationTest {

    private val book = Book(bookUrl = "book-url", name = "book", author = "author")

    @Test
    fun `highlight belongs only to the matching current book`() {
        assertTrue(BookHighlight(bookUrl = "book-url").isForBook(book))
        assertFalse(BookHighlight(bookUrl = "other-url").isForBook(book))
        assertFalse(BookHighlight(bookUrl = "book-url").isForBook(null))
    }

    @Test
    fun `highlight follows chapter url instead of mutable directory index`() {
        val chapter = BookChapter(bookUrl = "book-url", url = "chapter-url", index = 8)
        val highlight = BookHighlight(
            bookUrl = "book-url",
            chapterUrl = "chapter-url",
            chapterIndex = 2
        )

        assertTrue(highlight.isForChapter(book, chapter))
        assertFalse(highlight.isForChapter(book, chapter.copy(url = "other-chapter")))
        assertFalse(highlight.isForChapter(Book(bookUrl = "other-url"), chapter))
    }

    @Test
    fun `legacy highlight binds once when chapter metadata matches`() {
        val chapter = BookChapter(
            bookUrl = "book-url",
            url = "chapter-url",
            index = 2,
            title = "raw chapter"
        )
        val highlight = BookHighlight(
            bookUrl = "book-url",
            chapterIndex = 2,
            chapterName = "display chapter"
        )

        assertTrue(highlight.bindLegacyChapter(book, chapter, "display chapter"))
        assertTrue(highlight.isForChapter(book, chapter))
        assertFalse(highlight.bindLegacyChapter(book, chapter.copy(url = "other-chapter")))
        assertFalse(
            BookHighlight(
                bookUrl = "book-url",
                chapterIndex = 2,
                chapterName = "other"
            ).bindLegacyChapter(book, chapter)
        )
    }

    @Test
    fun `laid out chapter belongs only to the matching book url`() {
        val chapter = TextChapter(
            chapter = BookChapter(bookUrl = "book-url"),
            position = 0,
            title = "chapter",
            chaptersSize = 1,
            sameTitleRemoved = false,
            isVip = false,
            isPay = false,
            effectiveReplaceRules = null
        )

        assertTrue(chapter.isForBook(Book(bookUrl = "book-url")))
        assertFalse(chapter.isForBook(Book(bookUrl = "other-url")))
        assertFalse(chapter.isForBook(null))
    }

    @Test
    fun `automatic matching validates async result ownership before caching`() {
        val source = projectFile("src/main/java/io/legado/app/model/ReadBook.kt").readText()

        assertTrue(source.contains("launch(Default, start = CoroutineStart.LAZY)"))
        assertTrue(source.contains("highlightRulesVersion != version"))
        assertTrue(source.contains("highlightRulesBookUrl != bookUrl"))
        assertTrue(source.contains("textChapter.chapter.index != chapterIndex"))
        assertTrue(source.contains("!isActiveTextChapter(textChapter)"))
        assertTrue(source.contains("textChapter.highlightRuleMatchesJob !== job"))
        assertTrue(source.contains("if (matchResult.completed)"))
        assertTrue(source.contains("HighlightTextBuilder.LineInput("))
        assertTrue(source.contains("line.text,"))
        assertFalse(source.contains("line.columns.map { column ->"))
    }

    @Test
    fun `completed layouts always trigger automatic matching`() {
        val source = projectFile("src/main/java/io/legado/app/model/ReadBook.kt").readText()
        val observer = source.substringAfter("private fun observeHighlightRuleLayout")
            .substringBefore("private fun invalidateHighlightRuleMatches")

        assertTrue(observer.contains("textChapter.setProgressListener"))
        assertTrue(observer.contains("override fun onLayoutCompleted()"))
        assertTrue(observer.contains("launch { ruleMatchesOfChapter(textChapter) }"))
        assertTrue(observer.contains("if (textChapter.isCompleted) ruleMatchesOfChapter(textChapter)"))
        assertTrue(source.countOccurrences("observeHighlightRuleLayout(textChapter)") >= 6)
    }

    @Test
    fun `chapter navigation cancels matching for evicted chapters`() {
        val source = projectFile("src/main/java/io/legado/app/model/ReadBook.kt").readText()
        val next = source.substringAfter("fun moveToNextChapter(")
            .substringBefore("suspend fun moveToNextChapterAwait(")
        val nextAwait = source.substringAfter("suspend fun moveToNextChapterAwait(")
            .substringBefore("fun moveToPrevChapter(")
        val previous = source.substringAfter("fun moveToPrevChapter(")
            .substringBefore("fun skipToPage(")

        assertTrue(next.indexOf("prevTextChapter?.invalidateHighlightRuleMatches()") in
            0 until next.indexOf("prevTextChapter = curTextChapter"))
        assertTrue(nextAwait.indexOf("prevTextChapter?.invalidateHighlightRuleMatches()") in
            0 until nextAwait.indexOf("prevTextChapter = curTextChapter"))
        assertTrue(previous.indexOf("nextTextChapter?.invalidateHighlightRuleMatches()") in
            0 until previous.indexOf("nextTextChapter = curTextChapter"))
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first(File::isFile)

    private fun String.countOccurrences(value: String): Int =
        split(value).size - 1
}
