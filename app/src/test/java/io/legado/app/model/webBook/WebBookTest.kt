package io.legado.app.model.webBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WebBookTest {

    private val source = BookSource(
        bookSourceUrl = "https://source.example",
        bookSourceName = "Test source",
    )
    private val book = Book(
        bookUrl = "https://source.example/book/1",
        name = "Book",
    )

    @Test
    fun `volume placeholder stays empty when the content rule is absent`() = runBlocking {
        val content = WebBook.getContentAwait(
            bookSource = source,
            book = book,
            bookChapter = BookChapter(
                url = "Volume 1#0",
                title = "Volume 1",
                isVolume = true,
                tag = "2026-08-10",
            ),
            needSave = false,
        )

        assertEquals("", content)
    }

    @Test
    fun `ordinary chapter still uses its url when the content rule is absent`() = runBlocking {
        val chapterUrl = "https://source.example/chapter/1"

        val content = WebBook.getContentAwait(
            bookSource = source,
            book = book,
            bookChapter = BookChapter(
                url = chapterUrl,
                title = "Chapter 1",
            ),
            needSave = false,
        )

        assertEquals(chapterUrl, content)
    }
}
