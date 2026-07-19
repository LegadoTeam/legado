package io.legado.app.model.jsSource

import io.legado.app.exception.NoStackTraceException
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsSourceConfigTest {

    private val validScript = """
        var config = {
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
    fun `extracts modern source configuration without rewriting declarations`() {
        val script = """
            const config = {
                bookSourceUrl: "https://audio.example",
                bookSourceName: "Audio source",
                loginUi: [{ name: "token", type: "text" }],
                exploreUrl: [{ title: "Daily", url: "daily" }]
            };
            function copy(params) {
                const result = {};
                for (const key in params) result[key] = String(params[key]);
                if (params.mode === "free") {
                    const value = "free";
                    result.value = value;
                } else {
                    const value = "paid";
                    result.value = value;
                }
                return result;
            }
            function search(key, page) { return [copy({ key: key, page: page })]; }
            function explore(url, page) { return search(url, page); }
            function getBookInfo(book) { return book; }
            function getChapters(book) { return []; }
            function getContent(chapter, book) { return "content"; }
            function login() { return true; }
        """.trimIndent()

        val source = JsSourceConfig.extract(script)

        assertEquals("https://audio.example", source.bookSourceUrl)
        assertEquals("Audio source", source.bookSourceName)
        assertTrue(source.loginUi.orEmpty().contains("token"))
        assertTrue(source.exploreUrl.orEmpty().contains("Daily"))
        assertEquals(script, source.mainJs)
    }

    @Test
    fun `accepts legacy source config object`() {
        val source = JsSourceConfig.extract(
            validScript.replaceFirst("var config =", "var source =")
        )

        assertEquals("https://example.com", source.bookSourceUrl)
        assertEquals("示例源", source.bookSourceName)
    }

    @Test
    fun `prefers config object over legacy source object`() {
        val source = JsSourceConfig.extract(
            """
                var source = {
                    bookSourceUrl: "https://legacy.example",
                    bookSourceName: "旧配置"
                };
                var config = {
                    bookSourceUrl: "https://config.example",
                    bookSourceName: "新配置"
                };
                function search(key, page) { return []; }
                function getChapters(book) { return []; }
                function getContent(chapter, book) { return "content"; }
            """.trimIndent()
        )

        assertEquals("https://config.example", source.bookSourceUrl)
        assertEquals("新配置", source.bookSourceName)
    }

    @Test
    fun `ignores unrelated config object for legacy source`() {
        val source = JsSourceConfig.extract(
            "var config = { timeout: 10000 };\n" +
                validScript.replaceFirst("var config =", "var source =")
        )

        assertEquals("https://example.com", source.bookSourceUrl)
        assertEquals("示例源", source.bookSourceName)
    }

    @Test
    fun `ignores incomplete config object for legacy source`() {
        val source = JsSourceConfig.extract(
            "var config = { bookSourceUrl: 'https://partial.example' };\n" +
                validScript.replaceFirst("var config =", "var source =")
        )

        assertEquals("https://example.com", source.bookSourceUrl)
        assertEquals("示例源", source.bookSourceName)
    }

    @Test
    fun `ignores undefined config for legacy source`() {
        val source = JsSourceConfig.extract(
            "var config;\n" + validScript.replaceFirst("var config =", "var source =")
        )

        assertEquals("https://example.com", source.bookSourceUrl)
        assertEquals("示例源", source.bookSourceName)
    }

    @Test
    fun `requires config or legacy source object`() {
        assertExtractError(
            """
                function search(key, page) { return []; }
                function getChapters(book) { return []; }
                function getContent(chapter, book) { return "content"; }
            """.trimIndent(),
            "config",
        )
    }

    @Test
    fun `requires core functions`() {
        assertExtractError(
            """
                var config = { bookSourceUrl: "https://a.com", bookSourceName: "缺函数" };
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
    fun `explore metadata requires matching function`() {
        assertExtractError(
            validScript.replace(
                "header: \"{\\\"User-Agent\\\":\\\"test\\\"}\"",
                "exploreUrl: \"分类::https://example.com/list\"",
            ),
            "explore",
        )
    }

    @Test
    fun `normalizes login form array`() {
        val source = JsSourceConfig.extract(
            validScript.replace(
                "header: \"{\\\"User-Agent\\\":\\\"test\\\"}\"",
                """loginUi: [
                    { name: "账号", type: "text" },
                    { name: "密码", type: "password" }
                ]""".trimIndent(),
            ) + "\nfunction login() {}"
        )

        val rows = GSON.fromJson(source.loginUi, Array<RowUi>::class.java)
        assertEquals(2, rows.size)
        assertEquals("账号", rows[0].name)
        assertEquals("password", rows[1].type)
    }

    @Test
    fun `empty login form does not require login function`() {
        val source = JsSourceConfig.extract(
            validScript.replace(
                "header: \"{\\\"User-Agent\\\":\\\"test\\\"}\"",
                "loginUi: []",
            )
        )

        assertNull(source.loginUi)
    }

    @Test
    fun `empty login form json string folds to none`() {
        val source = JsSourceConfig.extract(
            validScript.replace(
                "header: \"{\\\"User-Agent\\\":\\\"test\\\"}\"",
                "loginUi: \"[ ]\"",
            )
        )

        assertNull(source.loginUi)
    }

    @Test
    fun `login form item requires name`() {
        assertExtractError(
            validScript.replace(
                "header: \"{\\\"User-Agent\\\":\\\"test\\\"}\"",
                "loginUi: [{ type: \"text\" }]",
            ) + "\nfunction login() {}",
            "缺少 name",
        )
    }

    @Test
    fun `login form requires login function`() {
        assertExtractError(
            validScript.replace(
                "header: \"{\\\"User-Agent\\\":\\\"test\\\"}\"",
                "loginUi: [{ name: \"账号\", type: \"text\" }]",
            ),
            "login",
        )
    }

    @Test
    fun `web login url does not require login function`() {
        val source = JsSourceConfig.extract(
            validScript.replace(
                "header: \"{\\\"User-Agent\\\":\\\"test\\\"}\"",
                "loginUrl: \"https://example.com/login\"",
            )
        )

        assertEquals("https://example.com/login", source.loginUrl)
    }

    @Test
    fun `rejects top level capability access`() {
        assertExtractError(
            """
                var probe = new java.net.URL("https://example.com");
                var config = { bookSourceUrl: "https://a.com", bookSourceName: "越权" };
            """.trimIndent(),
            "执行失败",
        )
    }

    @Test
    fun `stamps declared update time`() {
        val script = "var config = { lastUpdateTime: Date.now() };"
        assertEquals(
            "var config = { lastUpdateTime: 123456 };",
            JsSourceConfig.stampLastUpdateTime(script, 123456),
        )
    }

    @Test
    fun `extracts declared update time and defaults missing to zero`() {
        val declared = JsSourceConfig.extract(
            validScript.replace(
                "header: \"{\\\"User-Agent\\\":\\\"test\\\"}\"",
                "lastUpdateTime: 1752449000000",
            )
        )

        assertEquals(1752449000000L, declared.lastUpdateTime)
        assertEquals(0L, JsSourceConfig.extract(validScript).lastUpdateTime)
    }

    @Test
    fun `stamps numeric update time without touching later declarations`() {
        val script = """
            var source = {
                lastUpdateTime: 0 // version timestamp
            };
            var fallback = { lastUpdateTime: 1 };
        """.trimIndent()
        val expected = """
            var source = {
                lastUpdateTime: 123456 // version timestamp
            };
            var fallback = { lastUpdateTime: 1 };
        """.trimIndent()

        assertEquals(expected, JsSourceConfig.stampLastUpdateTime(script, 123456))
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
