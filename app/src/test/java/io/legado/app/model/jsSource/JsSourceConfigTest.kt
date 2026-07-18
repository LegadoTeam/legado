package io.legado.app.model.jsSource

import io.legado.app.exception.NoStackTraceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsSourceConfigTest {

    private val validScript = """
        var source = {
            bookSourceUrl: "https://example.com",
            bookSourceName: "示例源",
            header: "{\"User-Agent\":\"test\"}"
        };
        function search(key, page) { return []; }
        function getChapters(book) { return []; }
        function getContent(chapter, book) { return "content"; }
    """.trimIndent()

    @Test
    fun `extracts metadata and keeps full script`() {
        val source = JsSourceConfig.extract(validScript)

        assertEquals("https://example.com", source.bookSourceUrl)
        assertEquals("示例源", source.bookSourceName)
        assertEquals("{\"User-Agent\":\"test\"}", source.header)
        assertEquals(validScript, source.mainJs)
        assertTrue(source.isJsSource())
    }

    @Test
    fun `requires core functions`() {
        assertExtractError(
            """
                var source = { bookSourceUrl: "https://a.com", bookSourceName: "缺函数" };
                function search(key, page) { return []; }
                function getChapters(book) { return []; }
            """.trimIndent(),
            "getContent",
        )
    }

    @Test
    fun `strips declarative rules from config`() {
        val source = JsSourceConfig.extract(
            validScript.replace(
                "header: \"{\\\"User-Agent\\\":\\\"test\\\"}\"",
                "ruleSearch: { bookList: \"ignored\" }",
            )
        )

        assertNull(source.ruleSearch)
        assertTrue(source.mainJs.orEmpty().contains("ruleSearch"))
    }

    @Test
    fun `normalizes explore array`() {
        val source = JsSourceConfig.extract(
            validScript.replace(
                "header: \"{\\\"User-Agent\\\":\\\"test\\\"}\"",
                "exploreUrl: [{ title: \"分类\", url: \"https://example.com/list\" }]",
            ) + "\nfunction explore(url, page) { return []; }"
        )

        assertTrue(source.exploreUrl.orEmpty().contains("分类"))
        assertTrue(source.exploreUrl.orEmpty().contains("https://example.com/list"))
    }

    @Test
    fun `rejects top level capability access`() {
        assertExtractError(
            """
                var probe = new java.net.URL("https://example.com");
                var source = { bookSourceUrl: "https://a.com", bookSourceName: "越权" };
            """.trimIndent(),
            "执行失败",
        )
    }

    @Test
    fun `stamps declared update time`() {
        val script = "var source = { lastUpdateTime: Date.now() };"
        assertEquals(
            "var source = { lastUpdateTime: 123456 };",
            JsSourceConfig.stampLastUpdateTime(script, 123456),
        )
    }

    private fun assertExtractError(script: String, messagePart: String) {
        try {
            JsSourceConfig.extract(script)
            fail("Expected extract failure containing $messagePart")
        } catch (error: NoStackTraceException) {
            assertTrue(error.message.orEmpty().contains(messagePart))
        }
    }
}
