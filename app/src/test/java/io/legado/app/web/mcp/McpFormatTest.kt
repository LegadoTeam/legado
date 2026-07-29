package io.legado.app.web.mcp

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.model.CheckSourceResult
import io.legado.app.model.CheckSourceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpFormatTest {

    @Test
    fun detectFormatBoundary() {
        assertEquals("json", McpFormat.detectFormat("  {\"a\":1}"))
        assertEquals("json", McpFormat.detectFormat("[1]"))
        assertEquals("json", McpFormat.detectFormat("\uFEFF  {\"a\":1}"))
        assertEquals("json", McpFormat.detectFormat("  \uFEFF[1]"))
        assertEquals("js", McpFormat.detectFormat("// @name x"))
        assertEquals("js", McpFormat.detectFormat(""))
    }

    @Test
    fun summarizeFilterAndShape() {
        val a = BookSource(bookSourceName = "起点", bookSourceUrl = "https://a.com")
        val b = BookSource(bookSourceName = "笔趣", bookSourceUrl = "https://b.com")

        val all = McpFormat.summarizeSources(listOf(a, b), null)
        val hit = McpFormat.summarizeSources(listOf(a, b), "B.COM")

        assertEquals(2, all.size)
        assertEquals("起点", all[0]["bookSourceName"])
        assertEquals(false, all[0]["isJsSource"])
        assertEquals(1, hit.size)
        assertEquals("https://b.com", hit[0]["bookSourceUrl"])
    }

    @Test
    fun prettyJsonAndTruncateBoundary() {
        assertTrue(McpFormat.prettyJson("{\"a\":1}").contains("\n"))
        assertTrue(McpFormat.toPrettyJson(mapOf("a" to 1)).contains("\n"))
        assertEquals("abc", McpFormat.truncate("abc", 5))
        val cut = McpFormat.truncate("abcdef", 5)
        assertTrue(cut.startsWith("abcde"))
        assertTrue(cut.contains("已截断,原文 6 字符"))
    }

    @Test
    fun renderCheckSummarySeparatesFailedPassedAndPending() {
        val failed = BookSourcePart(
            bookSourceName = "坏站",
            bookSourceUrl = "https://bad.example",
        )
        val passed = BookSourcePart(
            bookSourceName = "好站",
            bookSourceUrl = "https://good.example",
        )
        val pending = BookSourcePart(
            bookSourceName = "未跑完",
            bookSourceUrl = "https://pending.example",
        )

        val rendered = McpFormat.renderCheckSummary(
            listOf(failed, passed, pending),
            mapOf(
                failed.bookSourceUrl to CheckSourceResult(
                    CheckSourceStatus.FAILED,
                    "搜索失效 | // Error: 搜索超时",
                ),
                passed.bookSourceUrl to CheckSourceResult(CheckSourceStatus.PASSED),
            ),
            mapOf(pending.bookSourceUrl to "[00:01.000] 校验成功"),
        )

        assertTrue(rendered.contains("失败 1/3"))
        assertTrue(rendered.contains("[失败] 坏站"))
        assertTrue(rendered.contains("// Error: 搜索超时"))
        assertTrue(rendered.contains("通过 1/3"))
        assertTrue(rendered.contains("[通过] 好站"))
        assertTrue(rendered.contains("未完成 1/3"))
        assertTrue(rendered.contains("[未完成] 未跑完"))
        assertTrue(rendered.contains("[00:01.000] 校验成功"))
    }

    @Test
    fun renderCheckSummaryKeepsDeletedAndChangedRequests() {
        val changedRequest = BookSourcePart(
            bookSourceName = "已修改",
            bookSourceUrl = "https://changed.example",
            lastUpdateTime = 1,
        )
        val deletedRequest = BookSourcePart(
            bookSourceName = "已删除",
            bookSourceUrl = "https://deleted.example",
            lastUpdateTime = 1,
        )
        val rendered = McpFormat.renderCheckSummary(
            listOf(changedRequest, deletedRequest),
            mapOf(
                changedRequest.bookSourceUrl to CheckSourceResult(
                    CheckSourceStatus.NOT_COMPLETED,
                    "书源已变更，校验结果未写回",
                ),
                deletedRequest.bookSourceUrl to CheckSourceResult(
                    CheckSourceStatus.NOT_COMPLETED,
                    "书源已删除",
                ),
            ),
            emptyMap(),
        )

        assertTrue(rendered.contains("通过 0/2"))
        assertTrue(rendered.contains("未完成 2/2"))
        assertTrue(rendered.contains("已修改(https://changed.example):书源已变更"))
        assertTrue(rendered.contains("已删除(https://deleted.example):书源已删除"))
    }
}
