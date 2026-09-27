package io.legado.app.ui.book.search

import io.legado.app.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SearchResultFilterTest {

    @Test
    fun `filters confirmed fields as case-insensitive plain text`() {
        val books = listOf(
            SearchBook(bookUrl = "name", name = "Dark DRAGON"),
            SearchBook(bookUrl = "author", name = "Keep", author = "BAD AUTHOR"),
            SearchBook(bookUrl = "kind", name = "Keep", kind = "都市,仙侠"),
            SearchBook(bookUrl = "literal", name = "Guide to .* ["),
            SearchBook(
                bookUrl = "kept-intro",
                name = "Kept One",
                intro = "dragon",
                wordCount = "仙侠",
            ),
            SearchBook(bookUrl = "kept", name = "Kept Two"),
        )

        val filtered = filterSearchResults(
            books,
            " dragon \r\nBAD AUTHOR\n 仙侠 \n.* [\n\n",
        )

        assertEquals(listOf("kept-intro", "kept"), filtered.map { it.bookUrl })
        assertSame(books, filterSearchResults(books, "\r\n \t"))
    }
}
