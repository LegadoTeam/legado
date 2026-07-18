package io.legado.app.ui.login

import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SourceLoginRouteTest {

    @Test
    fun `blank origin keeps current source`() {
        val current = BookSource(bookSourceUrl = "https://current.example")

        val result = resolveLoginSource("  ", current, { null }, { null })

        assertSame(current, result)
    }

    @Test
    fun `origin resolves book source before rss source`() {
        val current = BookSource(bookSourceUrl = "https://current.example")
        val book = BookSource(bookSourceUrl = "https://target.example")
        val rss = RssSource(sourceUrl = "https://target.example")

        val result = resolveLoginSource(book.bookSourceUrl, current, { book }, { rss })

        assertSame(book, result)
    }

    @Test
    fun `matching origin keeps current source before database lookup`() {
        val current = RssSource(sourceUrl = "https://same.example")
        val collidingBook = BookSource(bookSourceUrl = current.sourceUrl)

        val result = resolveLoginSource(current.sourceUrl, current, { collidingBook }, { null })

        assertSame(current, result)
    }

    @Test
    fun `origin can resolve rss source and missing source does not fall back`() {
        val current = BookSource(bookSourceUrl = "https://current.example")
        val rss = RssSource(sourceUrl = "https://rss.example")

        val resolved = resolveLoginSource(rss.sourceUrl, current, { null }, { rss })
        val missing = resolveLoginSource("https://missing.example", current, { null }, { null })

        assertSame(rss, resolved)
        assertNull(missing)
    }

    @Test
    fun `active book source keeps reading context`() {
        val current = BookSource(bookSourceUrl = "https://current.example")

        val route = createSourceLoginRoute(current, current, BookType.audio)

        assertEquals("bookSource", route?.type)
        assertEquals(current.bookSourceUrl, route?.key)
        assertEquals(BookType.audio, route?.bookType)
    }

    @Test
    fun `cross source and rss routes use explicit keys`() {
        val current = BookSource(bookSourceUrl = "https://current.example")
        val target = BookSource(bookSourceUrl = "https://target.example")
        val rss = RssSource(sourceUrl = "https://rss.example")

        val bookRoute = createSourceLoginRoute(target, current, BookType.text)
        val rssRoute = createSourceLoginRoute(rss, current, BookType.text)

        assertEquals("bookSource", bookRoute?.type)
        assertEquals(target.bookSourceUrl, bookRoute?.key)
        assertNull(bookRoute?.bookType)
        assertEquals("rssSource", rssRoute?.type)
        assertEquals(rss.sourceUrl, rssRoute?.key)
        assertNull(rssRoute?.bookType)
    }
}
