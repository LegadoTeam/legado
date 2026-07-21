package io.legado.app.model.jsSource

import com.google.gson.JsonObject
import com.script.ScriptBindings
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `executes source functions with const for in loops`() {
        val source = BookSource(
            bookSourceUrl = "https://audio.example",
            bookSourceName = "Audio source",
            mainJs = """
                const config = { bookSourceUrl: 'https://audio.example' };
                function normalize(params) {
                    const output = {};
                    for (const key in params) output[key] = String(params[key]);
                    if (String(params.mode) === 'free') {
                        const result = 'free';
                        output.result = result;
                    } else {
                        const result = 'paid';
                        output.result = result;
                    }
                    return output;
                }
            """.trimIndent(),
        )

        val json = JsSourceEngine(source).callFunction(
            "normalize",
            listOf("params" to mapOf("page" to 3, "mode" to "free")),
        ).orEmpty()
        val result = GSON.fromJson(json, JsonObject::class.java)

        assertEquals("3", result.get("page").asString)
        assertEquals("free", result.get("result").asString)
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
        val (result, _) = evaluate(
            "function search(key, page) { return [{name: key, bookUrl: 'u' + page}]; }",
            "search(key, page)",
            listOf("key" to "测试", "page" to 2),
        )

        val json = JsSourceEngine.normalizeJsResult(result).orEmpty()
        assertTrue(json.contains("测试"))
        assertTrue(json.contains("u2"))
    }

    @Test
    fun `normalizes custom toJSON and getters in the source scope`() {
        val source = BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "custom json",
            mainJs = """
                var prefix = 'scope';
                function search() {
                    var result = {};
                    Object.defineProperty(result, 'computed', {
                        enumerable: true,
                        get: function() { return prefix + '-getter'; }
                    });
                    result.toJSON = function() {
                        return { transformed: this.computed };
                    };
                    return result;
                }
            """.trimIndent(),
        )

        val json = JsSourceEngine(source).callFunction("search", emptyList()).orEmpty()

        assertEquals("scope-getter", GSON.fromJson(json, JsonObject::class.java)
            .get("transformed").asString)
    }

    @Test
    fun `preserves cancellation while normalizing custom toJSON`() {
        val (result, _) = evaluate(
            "function value() { return { toJSON: function() { return { ok: true }; } }; }",
            "value()",
        )
        val job = Job().apply { cancel() }

        assertThrows(CancellationException::class.java) {
            JsSourceEngine.normalizeJsResult(result, job)
        }
    }

    @Test
    fun `passes content string through unchanged`() {
        val (result, _) = evaluate(
            "function content() { return '第一段\\n第二段'; }",
            "content()",
        )

        assertEquals("第一段\n第二段", JsSourceEngine.normalizeJsResult(result))
    }

    @Test
    fun `maps undefined to null`() {
        val (result, _) = evaluate("function noop() {}", "noop()")
        assertNull(JsSourceEngine.normalizeJsResult(result))
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
    ): Pair<Any?, ScriptBindings> {
        val bindings = buildScriptBindings { target ->
            args.forEach { (key, value) -> target[key] = value }
        }
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        RhinoScriptEngine.eval(script, scope)
        return RhinoScriptEngine.eval(expression, scope) to scope
    }
}
