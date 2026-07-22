package io.legado.app.web.mcp

import io.legado.app.data.entities.BookSource
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
}
