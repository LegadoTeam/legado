package io.legado.app.model.analyzeRule

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ReviewRule
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class ReviewRuleParserFallbackTest {

    @Test
    fun `summary falls back to list order and ignores unusable counts`() {
        val source = BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "Review source",
        )
        val book = Book(
            bookUrl = "https://example.com/book",
            origin = source.bookSourceUrl,
        )
        val chapter = BookChapter(
            url = "https://example.com/chapter/1",
            bookUrl = book.bookUrl,
        )

        val result = ReviewRuleParser.parseSummary(
            body = """
                {
                  "items": [
                    {"data": "first", "count": "2"},
                    {"count": 3},
                    {"index": 0, "count": 9},
                    {"count": 0}
                  ]
                }
            """.trimIndent(),
            rule = ReviewRule(
                summaryListRule = "$.items",
                summaryParagraphIndexRule = "$.index",
                summaryParagraphDataRule = "$.data",
                summaryCountRule = "$.count",
            ),
            source = source,
            book = book,
            chapter = chapter,
            baseUrl = chapter.url,
            context = EmptyCoroutineContext,
        )

        assertEquals(mapOf(1 to 2, 2 to 3), result?.counts)
        assertEquals(mapOf(1 to "first", 2 to "2"), result?.keys)
    }

    @Test
    fun `summary accepts a JSON array string returned by JavaScript`() {
        val source = BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "Review source",
        )
        val book = Book(
            bookUrl = "https://example.com/book",
            origin = source.bookSourceUrl,
        )
        val chapter = BookChapter(
            url = "https://example.com/chapter/1",
            bookUrl = book.bookUrl,
        )

        val result = ReviewRuleParser.parseSummary(
            body = "{}",
            rule = ReviewRule(
                summaryListRule = "@js:JSON.stringify([{index: 3, count: 4}])",
                summaryParagraphIndexRule = "index",
                summaryCountRule = "count",
            ),
            source = source,
            book = book,
            chapter = chapter,
            baseUrl = chapter.url,
            context = EmptyCoroutineContext,
        )

        assertEquals(mapOf(3 to 4), result?.counts)
        assertEquals(mapOf(3 to "3"), result?.keys)
    }

    @Test
    fun `summary keeps every regex list match`() {
        val source = BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "Review source",
        )
        val book = Book(
            bookUrl = "https://example.com/book",
            origin = source.bookSourceUrl,
        )
        val chapter = BookChapter(
            url = "https://example.com/chapter/1",
            bookUrl = book.bookUrl,
        )

        val result = ReviewRuleParser.parseSummary(
            body = "item:1:2 item:3:4",
            rule = ReviewRule(
                summaryListRule = """:item:(\d+):(\d+)""",
                summaryParagraphIndexRule = "@js:result[1]",
                summaryCountRule = "@js:result[2]",
            ),
            source = source,
            book = book,
            chapter = chapter,
            baseUrl = chapter.url,
            context = EmptyCoroutineContext,
        )

        assertEquals(mapOf(1 to 2, 3 to 4), result?.counts)
    }
}
