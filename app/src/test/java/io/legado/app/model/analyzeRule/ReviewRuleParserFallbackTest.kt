package io.legado.app.model.analyzeRule

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ReviewRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class ReviewRuleParserFallbackTest {

    @Test
    fun `missing optional JSONPath fields stay empty without error logs`() {
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

        AppLog.clear()
        try {
            val result = ReviewRuleParser.parseDetailPage(
                body = """
                    {
                      "items": [
                        {
                          "UserName": "Alice",
                          "Content": "Hello",
                          "replies": [{"UserName": "Bob", "Content": "Reply"}]
                        }
                      ]
                    }
                """.trimIndent(),
                rule = ReviewRule(
                    detailListRule = "$.items",
                    detailAvatarRule = "$.UserHeadIcon",
                    detailNameRule = "$.UserName",
                    detailBadgeRule = "$.TitleInfoList[*].TitleImage",
                    detailContentRule = "$.Content",
                    replyListRule = "$.replies",
                    replyAvatarRule = "$.UserHeadIcon",
                    replyNameRule = "$.UserName",
                    replyBadgeRule = "$.TitleInfoList[*].TitleImage",
                    replyContentRule = "$.Content",
                ),
                nextPageRule = null,
                baseUrl = chapter.url,
                source = source,
                book = book,
                chapter = chapter,
                context = EmptyCoroutineContext,
                paraIndex = "1",
                paraData = "key",
                page = "1",
            )

            assertEquals(listOf("Alice"), result.items.map { it.name })
            with(result.items.single()) {
                assertNull(avatar)
                assertTrue(badges.isEmpty())
                assertEquals(listOf("Bob"), replies.map { it.name })
                assertNull(replies.single().avatar)
                assertTrue(replies.single().badges.isEmpty())
            }
            assertTrue(AppLog.logs.isEmpty())
        } finally {
            AppLog.clear()
        }
    }

    @Test
    fun `rule failures keep empty fallback and are recorded`() {
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
        val brokenRule = "@js:missingReviewFunction()"

        AppLog.clear()
        try {
            val result = ReviewRuleParser.parseSummary(
                body = "{}",
                rule = ReviewRule(
                    summaryListRule = brokenRule,
                    summaryParagraphIndexRule = "index",
                ),
                source = source,
                book = book,
                chapter = chapter,
                baseUrl = chapter.url,
                context = EmptyCoroutineContext,
            )

            assertEquals(emptyMap<Int, Int>(), result?.counts)
            assertTrue(
                AppLog.logs.any {
                    it.second.contains("段评统计列表规则执行出错") &&
                        it.second.contains(brokenRule)
                }
            )

            AppLog.clear()
            val detail = ReviewRuleParser.parseDetailPage(
                body = """{"items":[{},{}]}""",
                rule = ReviewRule(
                    detailListRule = "$.items",
                    detailNameRule = brokenRule,
                ),
                nextPageRule = null,
                baseUrl = chapter.url,
                source = source,
                book = book,
                chapter = chapter,
                context = EmptyCoroutineContext,
                paraIndex = "1",
                paraData = "key",
                page = "1",
            )

            assertTrue(detail.items.isEmpty())
            assertEquals(
                1,
                AppLog.logs.count {
                    it.second.contains("段评规则执行出错") &&
                        it.second.contains(brokenRule)
                }
            )

            AppLog.clear()
            ReviewRuleParser.parseDetailPage(
                body = """{"items":[{}]}""",
                rule = ReviewRule(
                    detailListRule = "$.items",
                    detailNameRule = "$.broken[foo]",
                ),
                nextPageRule = null,
                baseUrl = chapter.url,
                source = source,
                book = book,
                chapter = chapter,
                context = EmptyCoroutineContext,
                paraIndex = "1",
                paraData = "key",
                page = "1",
            )
            assertTrue(AppLog.logs.any { it.second.contains("$.broken[foo]") })
        } finally {
            AppLog.clear()
        }
    }

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
