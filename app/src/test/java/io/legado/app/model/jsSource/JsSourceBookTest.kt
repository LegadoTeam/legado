package io.legado.app.model.jsSource

import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.isWebFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSourceBookTest {

    @Test
    fun `volume placeholder skips js content and ignores its tag`() = runBlocking {
        val source = BookSource(
            bookSourceUrl = "https://source.example",
            bookSourceName = "Test source",
            mainJs = "function getContent() { throw 'must not run'; }",
        )
        val chapter = BookChapter(
            url = "Volume 1#0",
            title = "Volume 1",
            isVolume = true,
            tag = "2026-08-10",
        )

        val content = JsSourceBook.getContentAwait(
            source = source,
            book = Book(bookUrl = "https://source.example/book/1", name = "Book"),
            chapter = chapter,
            needSave = false,
        )

        assertEquals("", content)
    }

    @Test
    fun `blank content is allowed only for volume chapters`() = runBlocking {
        val source = BookSource(
            bookSourceUrl = "https://source.example",
            bookSourceName = "Test source",
            mainJs = "function getContent() { return ''; }",
        )
        val chapter = BookChapter(
            url = "https://source.example/volume/1",
            title = "Volume 1",
            isVolume = true,
        )

        val content = JsSourceBook.getContentAwait(
            source = source,
            book = Book(bookUrl = "https://source.example/book/1", name = "Book"),
            chapter = chapter,
            needSave = false,
        )

        assertEquals("", content)
        assertThrows(ContentEmptyException::class.java) {
            runBlocking {
                JsSourceBook.getContentAwait(
                    source = source,
                    book = Book(bookUrl = "https://source.example/book/1", name = "Book"),
                    chapter = chapter.copy(isVolume = false),
                    needSave = false,
                )
            }
        }
        Unit
    }

    @Test
    fun `file source resolves download urls without toc fallback`() = runBlocking {
        val source = BookSource(
            bookSourceUrl = "https://source.example",
            bookSourceName = "文件源",
            bookSourceType = BookSourceType.file,
            mainJs = """
                function getBookInfo(book) {
                    return { type: 8, downloadUrls: ["/download/book.epub"] };
                }
            """.trimIndent(),
        )
        val book = Book(bookUrl = "https://source.example/detail/1", name = "书名")

        JsSourceBook.getBookInfoAwait(source, book, canReName = true)

        assertEquals(listOf("https://source.example/download/book.epub"), book.downloadUrls)
        assertTrue(book.tocUrl.isBlank())
        assertTrue(book.isWebFile)
    }

    @Test
    fun `file source rejects empty download urls`() {
        val source = BookSource(
            bookSourceUrl = "https://source.example",
            bookSourceName = "文件源",
            bookSourceType = BookSourceType.file,
            mainJs = "function getBookInfo(book) { return {}; }",
        )
        val book = Book(bookUrl = "https://source.example/detail/1", name = "书名")

        val error = assertThrows(NoStackTraceException::class.java) {
            runBlocking {
                JsSourceBook.getBookInfoAwait(source, book, canReName = true)
            }
        }

        assertTrue(error.message.orEmpty().contains("下载链接为空"))
    }
}
