package io.legado.app.model.jsSource

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class JsSourceReviewTest {

    private val book = Book(
        bookUrl = "https://example.com/book/1",
        name = "测试书",
    )
    private val chapter = BookChapter(
        bookUrl = book.bookUrl,
        title = "第1章",
        url = "https://example.com/chapter/1",
    )

    @Test
    fun `summary parses counts data and title reviews`() = runBlocking {
        val source = source(
            """
            function getReviewSummary(chapter, book) {
                return [
                    { paraIndex: -1, count: 3, paraData: "title" },
                    { paraIndex: 1, count: 5, paraData: "token" },
                    { paraIndex: 2, count: 2 }
                ];
            }
            function getReviewDetail() { return { items: [] }; }
            """.trimIndent(),
        )

        val result = requireNotNull(
            JsSourceReview.getReviewSummaryAwait(source, book, chapter)
        )

        assertEquals(mapOf(-1 to 3, 1 to 5, 2 to 2), result.counts)
        assertEquals("title", result.keys[-1])
        assertEquals("token", result.keys[1])
        assertEquals("2", result.keys[2])
    }

    @Test
    fun `summary ignores invalid entries and non-positive counts`() = runBlocking {
        val source = source(
            """
            function getReviewSummary() {
                return [
                    null,
                    { count: 4 },
                    { paraIndex: -2, count: 4 },
                    { paraIndex: 0, count: 4 },
                    { paraIndex: 1, count: 0 },
                    { paraIndex: 2, count: -1 },
                    { paraIndex: 3, count: 7 }
                ];
            }
            function getReviewDetail() { return { items: [] }; }
            """.trimIndent(),
        )

        val result = requireNotNull(
            JsSourceReview.getReviewSummaryAwait(source, book, chapter)
        )

        assertEquals(mapOf(3 to 7), result.counts)
    }

    @Test
    fun `detail parses metadata pagination and badge`() = runBlocking {
        val source = source(
            """
            function getReviewSummary() { return []; }
            function getReviewDetail(chapter, book, paraIndex, paraData, page) {
                return {
                    items: [{
                        id: "c1",
                        content: paraData + ":" + page,
                        name: "用户",
                        avatar: "/avatar.png",
                        badge: "作者"
                    }],
                    nextPageUrl: page < 2 ? "more" : null
                };
            }
            """.trimIndent(),
        )

        val result = JsSourceReview.getReviewDetailAwait(
            source, book, chapter, 1, "token", 1,
        )

        assertNotNull(result)
        assertEquals("more", result!!.nextPageUrl)
        assertEquals(1, result.items.size)
        val item = result.items.single()
        assertEquals("c1", item.id)
        assertEquals("token:1", item.content)
        assertEquals("用户", item.name)
        assertEquals("https://example.com/avatar.png", item.avatar)
        assertEquals(listOf("作者"), item.badges)
    }

    @Test
    fun `detail flattens recursive replies for the native dialog`() = runBlocking {
        val source = source(
            """
            function getReviewSummary() { return []; }
            function getReviewDetail() {
                return { items: [{
                    content: "主评论",
                    replies: [{
                        id: "r1",
                        content: "一级回复",
                        replies: [{ id: "r2", content: "二级回复" }]
                    }]
                }] };
            }
            """.trimIndent(),
        )

        val item = JsSourceReview.getReviewDetailAwait(
            source, book, chapter, 1, "", 1,
        )!!.items.single()

        assertEquals(listOf("r1", "r2"), item.replies.map { it.id })
        assertTrue(item.replies.all { it.replies.isEmpty() })
    }

    @Test
    fun `detail flattens deep replies without recursive parsing`() {
        var reply = JsonObject().apply { addProperty("content", "leaf") }
        repeat(2_048) { index ->
            reply = JsonObject().apply {
                addProperty("id", "r$index")
                addProperty("content", "reply")
                add("replies", JsonArray().apply { add(reply) })
            }
        }
        val result = JsonObject().apply {
            add(
                "items",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("content", "main")
                            add("replies", JsonArray().apply { add(reply) })
                        }
                    )
                }
            )
        }

        val replies = JsSourceReview.parseDetailObject(result, chapter.url)!!
            .items.single().replies

        assertEquals(2_049, replies.size)
        assertTrue(replies.all { it.replies.isEmpty() })
    }

    @Test
    fun `detail tolerates null replies and drops blank content`() = runBlocking {
        val source = source(
            """
            function getReviewSummary() { return []; }
            function getReviewDetail() {
                return { items: [
                    { id: "ok", content: "内容", replies: null },
                    { id: "blank", content: "" },
                    { id: "missing" }
                ] };
            }
            """.trimIndent(),
        )

        val items = JsSourceReview.getReviewDetailAwait(
            source, book, chapter, 1, "", 1,
        )!!.items

        assertEquals(1, items.size)
        assertEquals("ok", items.single().id)
        assertTrue(items.single().replies.isEmpty())
    }

    @Test
    fun `detail rejects null or non-array items without throwing`() = runBlocking {
        val nullItems = source(
            """
            function getReviewSummary() { return []; }
            function getReviewDetail() { return { items: null }; }
            """.trimIndent(),
        )
        val objectItems = source(
            """
            function getReviewSummary() { return []; }
            function getReviewDetail() { return { items: {} }; }
            """.trimIndent(),
        )

        assertNull(JsSourceReview.getReviewDetailAwait(nullItems, book, chapter, 1, "", 1))
        assertNull(JsSourceReview.getReviewDetailAwait(objectItems, book, chapter, 1, "", 1))
    }

    @Test
    fun `missing summary capability returns no result`() {
        val source = source("")

        assertNull(runBlocking { JsSourceReview.getReviewSummaryAwait(source, book, chapter) })
        assertThrows(NoStackTraceException::class.java) {
            runBlocking {
                JsSourceReview.getReviewDetailAwait(source, book, chapter, 1, "", 1)
            }
        }
    }

    @Test
    fun `remembered missing capability skips script execution`() = runBlocking {
        val source = source("throw 'script should not execute';")
        JsSourceReview.rememberReviewCapability(source, enabled = false)

        val result = JsSourceReview.getReviewSummaryAwait(source, book, chapter)

        assertNull(result)
    }

    private fun source(reviewFunctions: String): BookSource {
        return BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "段评测试",
            mainJs = """
                var config = {
                    bookSourceUrl: "https://example.com",
                    bookSourceName: "段评测试"
                };
                function search() { return []; }
                function getChapters() { return []; }
                function getContent() { return ""; }
                $reviewFunctions
            """.trimIndent(),
        )
    }
}
