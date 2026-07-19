package io.legado.app.model.jsSource

import com.google.gson.JsonObject
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Scriptable

class JsSourceEngineTest {

    @Test
    fun `keeps runtime source available when config object is declared`() {
        val source = BookSource(
            bookSourceUrl = "https://persisted.example",
            bookSourceName = "测试源",
            mainJs = """
                var config = { bookSourceUrl: 'https://config.example' };
                function identity() {
                    return {
                        configUrl: config.bookSourceUrl,
                        runtimeUrl: source.bookSourceUrl,
                        aliasUrl: sourceApi.bookSourceUrl
                    };
                }
            """.trimIndent(),
        )

        val json = JsSourceEngine(source).callFunction("identity", emptyList()).orEmpty()
        val result = GSON.fromJson(json, JsonObject::class.java)

        assertEquals("https://config.example", result.get("configUrl").asString)
        assertEquals("https://persisted.example", result.get("runtimeUrl").asString)
        assertEquals("https://persisted.example", result.get("aliasUrl").asString)
    }

    @Test
    fun `calls source function through complete engine`() {
        val source = BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "测试源",
            mainJs = """
                var source = { bookSourceUrl: 'https://script.example' };
                function search(key, page) {
                    return [{ name: key, bookUrl: source.bookSourceUrl + '/book/' + page }];
                }
            """.trimIndent(),
        )

        val json = JsSourceEngine(source).callFunction(
            "search",
            listOf("key" to "书名", "page" to 3),
        ).orEmpty()

        assertTrue(json.contains("书名"))
        assertTrue(json.contains("https://script.example/book/3"))
    }

    @Test
    fun `keeps persisted source available through source api`() {
        val source = BookSource(
            bookSourceUrl = "https://persisted.example",
            bookSourceName = "测试源",
            mainJs = """
                var source = { bookSourceUrl: 'https://script.example' };
                function identity() {
                    return {
                        configUrl: source.bookSourceUrl,
                        persistedUrl: sourceApi.bookSourceUrl
                    };
                }
            """.trimIndent(),
        )

        val json = JsSourceEngine(source).callFunction("identity", emptyList()).orEmpty()

        assertTrue(json.contains("https://script.example"))
        assertTrue(json.contains("https://persisted.example"))
    }

    @Test
    fun `normalizes object and lazy strings with current rhino`() {
        val (result, scope) = evaluate(
            "function search(key, page) { return [{name: key, bookUrl: 'u' + page}]; }",
            "search(key, page)",
            listOf("key" to "测试", "page" to 2),
        )

        val json = JsSourceEngine.normalizeJsResult(result, scope).orEmpty()
        assertTrue(json.contains("测试"))
        assertTrue(json.contains("u2"))
    }

    @Test
    fun `passes content string through unchanged`() {
        val (result, scope) = evaluate(
            "function content() { return '第一段\\n第二段'; }",
            "content()",
        )

        assertEquals("第一段\n第二段", JsSourceEngine.normalizeJsResult(result, scope))
    }

    @Test
    fun `maps undefined to null`() {
        val (result, scope) = evaluate("function noop() {}", "noop()")
        assertNull(JsSourceEngine.normalizeJsResult(result, scope))
    }

    @Test
    fun `optional function returns null when missing`() {
        val source = BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "test",
            mainJs = "var source = {};",
        )

        assertNull(JsSourceEngine(source).callFunctionIfExists("getBookInfo", emptyList()))
    }

    @Test
    fun `optional function executes when present`() {
        val source = BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "test",
            mainJs = """
                var source = {};
                function getBookInfo() {
                    return { intro: "optional result" };
                }
            """.trimIndent(),
        )

        val json = JsSourceEngine(source)
            .callFunctionIfExists("getBookInfo", emptyList())
            .orEmpty()

        assertTrue(json.contains("optional result"))
    }

    private fun evaluate(
        script: String,
        expression: String,
        args: List<Pair<String, Any?>> = emptyList(),
    ): Pair<Any?, Scriptable> {
        val bindings = buildScriptBindings { target ->
            args.forEach { (key, value) -> target[key] = value }
        }
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        RhinoScriptEngine.eval(script, scope)
        return RhinoScriptEngine.eval(expression, scope) to scope
    }
}
