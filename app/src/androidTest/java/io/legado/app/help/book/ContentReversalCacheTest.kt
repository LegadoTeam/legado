package io.legado.app.help.book

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.getFolderName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ContentReversalCacheTest {
    @Test fun reverseRestoreAndFreshDownloadUseTheActualCacheContents() = withChapter { book, chapter ->
        // Reversing this plain text creates a new entity. Re-parsing it on undo
        // would no longer invert the first operation; restore the original bytes.
        val content = ";pma&😀甲"
        BookHelp.saveText(book, chapter, content)
        assertTrue(BookHelp.reverseContent(book, chapter))
        assertEquals("甲😀&amp;", BookHelp.getContent(book, chapter))
        assertTrue(BookHelp.isContentReversed(book, chapter))
        assertTrue(BookHelp.reverseContent(book, chapter))
        assertEquals(content, BookHelp.getContent(book, chapter))
        assertFalse(BookHelp.isContentReversed(book, chapter))
        assertTrue(BookHelp.reverseContent(book, chapter))
        assertTrue(BookHelp.saveContent(BookSource(), book, chapter, "downloaded again"))
        assertEquals("downloaded again", BookHelp.getContent(book, chapter))
        assertFalse(BookHelp.isContentReversed(book, chapter))
        BookHelp.delContent(book, chapter)
        val pendingDownload = BookHelp.contentSaveToken(book, chapter)
        assertFalse(BookHelp.reverseContent(book, chapter))
        assertFalse(BookHelp.isContentReversed(book, chapter))
        assertTrue("A no-op must not invalidate the pending download",
            BookHelp.saveContent(BookSource(), book, chapter, "pending content", pendingDownload))
    }

    @Test fun failedWriteDoesNotToggleStateOrReplaceTheOriginalCache() = withChapter { book, chapter ->
        val original = "甲乙😀"
        BookHelp.saveText(book, chapter, original)
        val file = File(BookHelp.cachePath, "${book.getFolderName()}/${chapter.getFileName()}")
        val marker = File(file.path + ".reversed")
        assertTrue(marker.mkdir())
        try {
            assertTrue("Failed undo-data writes must leave the content unchanged", runCatching {
                BookHelp.reverseContent(book, chapter)
            }.isFailure)
            assertEquals(original, BookHelp.getContent(book, chapter))
            assertFalse(BookHelp.isContentReversed(book, chapter))
        } finally { assertTrue(marker.delete()) }
        for (checked in listOf(false, true)) {
            if (checked) assertTrue(BookHelp.reverseContent(book, chapter))
            val content = BookHelp.getContent(book, chapter)
            // Emulated external storage ignores chmod; obstruct AtomicFile's
            // staging path to produce a real, deterministic filesystem error.
            val staging = File(file.path + ".new")
            assertTrue(staging.mkdir())
            try {
                assertTrue("An obstructed atomic cache write must fail", runCatching {
                    BookHelp.reverseContent(book, chapter)
                }.isFailure)
                assertEquals(content, BookHelp.getContent(book, chapter))
                assertEquals(checked, BookHelp.isContentReversed(book, chapter))
            } finally {
                if (staging.exists()) assertTrue(staging.delete())
            }
        }
    }

    private fun withChapter(test: (Book, BookChapter) -> Unit) {
        val id = UUID.randomUUID().toString()
        val book = Book(bookUrl = "https://example.invalid/reversal-cache/$id", name = id)
        val chapter = BookChapter(bookUrl = book.bookUrl, url = "${book.bookUrl}/1", title = "Chapter")
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(chapter)
        try { test(book, chapter) } finally {
            BookHelp.clearCache(book)
            appDb.bookDao.delete(book)
        }
    }
}
