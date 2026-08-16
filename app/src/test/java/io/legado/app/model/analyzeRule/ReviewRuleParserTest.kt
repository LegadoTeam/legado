package io.legado.app.model.analyzeRule

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ReviewRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class ReviewRuleParserTest {

    private val source = BookSource(
        bookSourceUrl = "https://example.com",
        bookSourceName = "Review source",
    )
    private val book = Book(bookUrl = "https://example.com/book", origin = source.bookSourceUrl)
    private val chapter = BookChapter(
        url = "https://example.com/chapter/1",
        bookUrl = book.bookUrl,
    )

    @Test
    fun `summary configuration requires every lookup rule`() {
        val rule = ReviewRule(
            enabled = true,
            reviewSummaryUrl = "https://example.com/reviews",
            summaryListRule = "$.items",
            summaryParagraphIndexRule = "$.index",
            summaryCountRule = "$.count",
        )

        assertEquals("https://example.com/reviews", rule.configuredSummaryUrl())
        rule.summaryCountRule = null
        assertEquals(null, rule.configuredSummaryUrl())
    }

    @Test
    fun `parses JSON summary returned as a native array`() {
        val result = ReviewRuleParser.parseSummary(
            body = """
                {
                  "items": [
                    {"index": 2, "data": "p2", "count": 3},
                    {"index": "4", "count": "5.0"},
                    {"index": 5, "count": 0}
                  ]
                }
            """.trimIndent(),
            rule = ReviewRule(
                summaryListRule = "@js:JSON.parse(src).items",
                summaryParagraphIndexRule = "index",
                summaryParagraphDataRule = "data",
                summaryCountRule = "count",
            ),
            source = source,
            book = book,
            chapter = chapter,
            baseUrl = chapter.url,
            context = EmptyCoroutineContext,
        )

        assertNotNull(result)
        assertEquals(mapOf(2 to 3, 4 to 5), result!!.counts)
        assertEquals(mapOf(2 to "p2", 4 to "4"), result.keys)
    }

    @Test
    fun `parses detail content protocol replies and local next page variables`() {
        val result = ReviewRuleParser.parseDetailPage(
            body = """
                {
                  "items": [
                    {
                      "id": "m1",
                      "avatar": "/avatar.png",
                      "name": "Alice",
                      "badges": ["author", "vip"],
                      "content": "{\"text\":\"Hello\",\"img\":\"/media.jpg\",\"audio\":\"/audio.mp3\",\"time\":\"now\",\"likeCount\":\"7.0\",\"replyCount\":1}",
                      "replies": [
                        {
                          "id": "r1",
                          "avatar": "/reply.png",
                          "name": "Bob",
                          "badges": "reader|top",
                          "content": "{\"text\":\"Reply\",\"replyToName\":\"Alice\",\"likeCount\":3}"
                        }
                      ]
                    },
                    {
                      "id": "m2",
                      "content": "{\"other\":\"kept\"}"
                    }
                  ]
                }
            """.trimIndent(),
            rule = ReviewRule(
                detailListRule = "$.items",
                detailIdRule = "$.id",
                detailAvatarRule = "$.avatar",
                detailNameRule = "$.name",
                detailBadgeRule = "$.badges",
                detailContentRule = "$.content",
                replyListRule = "$.replies",
                replyIdRule = "$.id",
                replyAvatarRule = "$.avatar",
                replyNameRule = "$.name",
                replyBadgeRule = "$.badges",
                replyContentRule = "$.content",
            ),
            nextPageRule = "@js:'/comments/' + paraIndex + '/' + paraData + '/' + page",
            baseUrl = chapter.url,
            source = source,
            book = book,
            chapter = chapter,
            context = EmptyCoroutineContext,
            paraIndex = "8",
            paraData = "key",
            page = "2",
        )

        assertEquals("https://example.com/comments/8/key/2", result.nextPageUrl)
        assertEquals(2, result.items.size)
        with(result.items.first()) {
            assertEquals("m1", id)
            assertEquals("https://example.com/avatar.png", avatar)
            assertEquals("Alice", name)
            assertEquals(listOf("author", "vip"), badges)
            assertEquals("Hello", content)
            assertEquals("https://example.com/media.jpg", imageUrl)
            assertEquals("https://example.com/audio.mp3", audioUrl)
            assertEquals("now", time)
            assertEquals(7, likeCount)
            assertEquals(1, replyCount)
            assertEquals(1, replies.size)
            with(replies.single()) {
                assertEquals("r1", id)
                assertEquals("https://example.com/reply.png", avatar)
                assertEquals("Bob", name)
                assertEquals("Alice", replyToName)
                assertEquals(listOf("reader", "top"), badges)
                assertEquals("Reply", content)
                assertEquals(3, likeCount)
                assertEquals(null, replyCount)
            }
        }
        assertEquals("{\"other\":\"kept\"}", result.items[1].content)
    }

    @Test
    fun `declarative detail preserves 64-bit numeric ids`() {
        val result = ReviewRuleParser.parseDetailPage(
            body = """{"items":[{"id":1051979893439332353,"content":"评论"}]}""",
            rule = ReviewRule(
                detailListRule = "$.items",
                detailIdRule = "$.id",
                detailContentRule = "$.content",
            ),
            nextPageRule = null,
            baseUrl = chapter.url,
            source = source,
            book = book,
            chapter = chapter,
            context = EmptyCoroutineContext,
            paraIndex = "1",
            paraData = "",
            page = "1",
        )

        assertEquals("1051979893439332353", result.items.single().id)
    }

    @Test
    fun `parses a standalone reply page with reply rules`() {
        val replies = ReviewRuleParser.parseReplyPage(
            body = """
                {
                  "data": {
                    "reply_list": [
                      {
                        "id": "r1",
                        "avatar": "/reply.png",
                        "name": "Bob",
                        "badges": ["reader", "top"],
                        "content": "{\"text\":\"Reply\",\"replyToName\":\"Alice\",\"img\":\"/reply.jpg\",\"time\":\"now\",\"likeCount\":4}"
                      }
                    ]
                  }
                }
            """.trimIndent(),
            rule = ReviewRule(
                replyListRule = "$.data.reply_list",
                replyIdRule = "$.id",
                replyAvatarRule = "$.avatar",
                replyNameRule = "$.name",
                replyBadgeRule = "$.badges",
                replyContentRule = "$.content",
            ),
            baseUrl = chapter.url,
            source = source,
            book = book,
            chapter = chapter,
            context = EmptyCoroutineContext,
            paraIndex = "8",
            paraData = "7",
            page = "2",
        )

        with(replies.single()) {
            assertEquals("r1", id)
            assertEquals("https://example.com/reply.png", avatar)
            assertEquals("Bob", name)
            assertEquals("Alice", replyToName)
            assertEquals(listOf("reader", "top"), badges)
            assertEquals("Reply", content)
            assertEquals("https://example.com/reply.jpg", imageUrl)
            assertEquals("now", time)
            assertEquals(4, likeCount)
            assertTrue(this.replies.isEmpty())
        }
    }

    @Test
    fun `standalone reply rule is not evaluated against detail items`() {
        val result = ReviewRuleParser.parseDetailPage(
            body = """{"items":[{"id":"m1","content":"Comment"}]}""",
            rule = ReviewRule(
                reviewQuoteUrl = "/replies",
                detailListRule = "$.items",
                detailIdRule = "$.id",
                detailContentRule = "$.content",
                replyListRule = "@js:java.put('inlineReplyRule', 'evaluated');[]",
                replyContentRule = "$.content",
            ),
            nextPageRule = null,
            baseUrl = chapter.url,
            source = source,
            book = book,
            chapter = chapter,
            context = EmptyCoroutineContext,
            paraIndex = "1",
            paraData = "0",
            page = "1",
        )

        assertEquals(1, result.items.size)
        assertTrue(result.items.single().replies.isEmpty())
        assertFalse(chapter.variableMap.containsKey("inlineReplyRule"))
    }

    @Test
    fun `standalone reply list failures are retryable errors`() {
        assertThrows(Exception::class.java) {
            ReviewRuleParser.parseReplyPage(
                body = "{}",
                rule = ReviewRule(
                    replyListRule = "@js:throw new Error('invalid reply list')",
                    replyContentRule = "$.content",
                ),
                baseUrl = chapter.url,
                source = source,
                book = book,
                chapter = chapter,
                context = EmptyCoroutineContext,
                paraIndex = "1",
                paraData = "0",
                page = "1",
            )
        }
    }

    @Test
    fun `detail JavaScript keeps grouped roots and replies when optional badges are missing`() {
        val source = BookSource(
            bookSourceUrl = "review-js://grouped-detail",
            bookSourceName = "Review source",
            jsLib = "function formatEmoji(value) { return value; }",
        )
        val book = Book(bookUrl = "https://example.com/book", origin = source.bookSourceUrl)
        val chapter = BookChapter(
            url = "https://example.com/chapter/1",
            bookUrl = book.bookUrl,
        )
        val contentRule = """@js:
            const J = (path) => java.getString(path);
            JSON.stringify({text: formatEmoji(String(J("$.Content"))) });
        """.trimIndent()

        val result = ReviewRuleParser.parseDetailPage(
            body = """
                {
                  "Data": {
                    "DataList": [
                      {
                        "Id": "root-1",
                        "RootReviewId": "root-1",
                        "UserName": "Alice",
                        "Content": "Root comment"
                      },
                      {
                        "Id": "reply-1",
                        "RootReviewId": "root-1",
                        "UserName": "Bob",
                        "Content": "Reply comment"
                      }
                    ]
                  }
                }
            """.trimIndent(),
            rule = ReviewRule(
                detailListRule = """@js:
                    const data = JSON.parse(result).Data.DataList;
                    const roots = new Map();
                    for (const item of data) {
                      if (item.Id === item.RootReviewId) {
                        item.replyList = [];
                        roots.set(item.Id, item);
                      } else {
                        const root = roots.get(item.RootReviewId);
                        if (root) root.replyList.push(item);
                      }
                    }
                    [...roots.values()];
                """.trimIndent(),
                detailIdRule = "$.Id",
                detailNameRule = "$.UserName",
                detailBadgeRule = "$.TitleInfoList[*].TitleImage",
                detailContentRule = contentRule,
                replyListRule = "replyList",
                replyIdRule = "$.Id",
                replyNameRule = "$.UserName",
                replyBadgeRule = "$.TitleInfoList[*].TitleImage",
                replyContentRule = contentRule,
            ),
            nextPageRule = null,
            baseUrl = chapter.url,
            source = source,
            book = book,
            chapter = chapter,
            context = EmptyCoroutineContext,
            paraIndex = "2",
            paraData = "2",
            page = "1",
        )

        assertEquals(1, result.items.size)
        with(result.items.single()) {
            assertEquals("root-1", id)
            assertEquals("Alice", name)
            assertEquals("Root comment", content)
            assertTrue(badges.isEmpty())
            assertEquals(1, replies.size)
            with(replies.single()) {
                assertEquals("reply-1", id)
                assertEquals("Bob", name)
                assertEquals("Reply comment", content)
                assertTrue(badges.isEmpty())
            }
        }
    }

    @Test
    fun `detail JavaScript list fields execute against native objects`() {
        val result = ReviewRuleParser.parseDetailPage(
            body = """{"items":[{"name":"Alice","badges":["author","vip"],"content":"Hello"}]}""",
            rule = ReviewRule(
                detailListRule = "@js:JSON.parse(result).items",
                detailNameRule = "@js:result.name",
                detailBadgeRule = "@js:result.badges",
                detailContentRule = "@js:result.content",
            ),
            nextPageRule = null,
            baseUrl = chapter.url,
            source = source,
            book = book,
            chapter = chapter,
            context = EmptyCoroutineContext,
            paraIndex = "1",
            paraData = "0",
            page = "1",
        )

        assertEquals(listOf("author", "vip"), result.items.single().badges)
    }

    @Test
    fun `legacy review rules survive converter round trip and equality checks them`() {
        val converters = BookSource.Converters()
        val legacy = requireNotNull(
            converters.stringToReviewRule(
                """{"reviewUrl":"/legacy","avatarRule":".avatar","contentRule":".text"}""",
            ),
        )

        assertFalse(legacy.enabled)
        assertEquals("/legacy", legacy.reviewUrl)
        assertEquals(legacy, converters.stringToReviewRule(converters.reviewRuleToString(legacy)))
        assertFalse(
            BookSource(bookSourceUrl = "source", ruleReview = legacy).equal(
                BookSource(
                    bookSourceUrl = "source",
                    ruleReview = legacy.copy(contentRule = ".changed"),
                ),
            ),
        )
    }

    @Test
    fun `local rule bindings are explicit and limited to review variables`() {
        val analyzeRule = AnalyzeRule().setContent("{}")
        assertEquals(
            "undefined|undefined|undefined",
            analyzeRule.evalJS("[typeof paraIndex, typeof paraData, typeof page].join('|')"),
        )

        analyzeRule
            .setLocal("custom", "hidden")
            .setLocal("paraIndex", "8")
            .setLocal("paraData", "")
            .setLocal("page", "3")
        assertEquals(
            "undefined|string:8|string:|number:3",
            analyzeRule.evalJS(
                "[typeof custom, typeof paraIndex + ':' + paraIndex, " +
                    "typeof paraData + ':' + paraData, typeof page + ':' + page].join('|')",
            ),
        )
        assertEquals("hidden", analyzeRule.get("custom"))
    }

    @Test
    fun `url extra parameters preserve info map and only add supplied globals`() {
        val infoMap = mutableMapOf("token" to "ok")
        val ordinary = AnalyzeUrl("https://example.com", infoMap = infoMap)
        assertEquals(
            "undefined|undefined|ok",
            ordinary.evalJS(
                "[typeof paraIndex, typeof paraData, infoMap['token']].join('|')",
            ),
        )

        val reviewUrl = AnalyzeUrl(
            "https://example.com",
            page = 1,
            infoMap = infoMap,
            extraParams = mapOf(
                "paraIndex" to "8",
                "paraData" to "key",
                "reviewId" to "root-1",
                "page" to "2",
                "infoMap" to "shadow",
            ),
        )
        assertEquals(
            "8|key|root-1|number:2|ok",
            reviewUrl.evalJS(
                "[paraIndex, paraData, reviewId, typeof page + ':' + page, " +
                    "infoMap['token']].join('|')",
            ),
        )
    }
}
