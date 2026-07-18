package io.legado.app

import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import org.intellij.lang.annotations.Language
import org.junit.Assert
import org.junit.Test
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

class JsEngineCapabilitiesTest {

    @Test
    fun currentRhinoSupportsStableModernSyntax() {
        val cases = listOf(
            "const suffix = 'ok'; `rhino-${'$'}{suffix}`" to "rhino-ok",
            "var {left, right} = {left: 2, right: 3}; " +
                "var [first, second] = [4, 5]; '' + (left + right + first + second)" to "14",
            "function valueOr(value = 7) { return value }; '' + valueOr()" to "7",
            "var config = {nested: {value: 5}}; " +
                "'' + (config.missing?.value ?? config.nested?.value)" to "5",
            "var key = 'n'; var value = {[key]: 4, double() { return this[key] * 2 }}; " +
                "'' + value.double()" to "8",
        )

        cases.forEach { (js, expected) ->
            Assert.assertEquals(
                "Unexpected result for: $js",
                expected,
                RhinoScriptEngine.eval(js, ScriptBindings()),
            )
        }
    }

    @Test
    fun topLevelDeclarationsRemainVisibleToSourceExtraction() {
        listOf("var", "let", "const").forEach { declaration ->
            val scope = RhinoScriptEngine.getRuntimeScope(ScriptBindings())
            RhinoScriptEngine.eval("$declaration source = {value: 1}", scope)

            Assert.assertNotSame(
                "Top-level $declaration declaration is no longer visible",
                Scriptable.NOT_FOUND,
                ScriptableObject.getProperty(scope, "source"),
            )
        }
    }

    @Test
    fun restParametersDoNotImplySpreadSyntaxSupport() {
        val supported = """
            function collect(head, ...tail) {
                return head + ':' + tail.join('-')
            }
            collect('x', 1, 2)
        """.trimIndent()
        Assert.assertEquals("x:1-2", RhinoScriptEngine.eval(supported, ScriptBindings()))

        assertUnsupported("function add(a, b) { return a + b }; add(...[1, 2])")
        assertUnsupported("var [head, ...tail] = [1, 2, 3]; tail.length")
    }

    @Test
    fun logicalAssignmentOperatorsPreserveShortCircuitSemantics() {
        val cases = listOf(
            "var a = null, b = 0; a ??= 5; b ??= 6; a + ':' + b" to "5:0",
            "var a = 1, b = 0; a &&= 7; b &&= 8; a + ':' + b" to "7:0",
            "var a = 1, b = 0; a ||= 7; b ||= 8; a + ':' + b" to "1:8",
        )

        cases.forEach { (js, expected) ->
            Assert.assertEquals(expected, RhinoScriptEngine.eval(js, ScriptBindings()))
        }
    }

    @Test
    fun jsoupIsReachableFromJsScope() {
        @Language("js")
        val js = """
            var doc = org.jsoup.Jsoup.parse(
                '<div><a class="t">first</a><a class="t">second</a></div>'
            )
            var values = []
            for (var element of doc.select('a.t')) {
                values.push(element.text())
            }
            values.join(',')
        """.trimIndent()

        Assert.assertEquals("first,second", RhinoScriptEngine.eval(js, ScriptBindings()))
    }

    private fun assertUnsupported(js: String) {
        val result = runCatching { RhinoScriptEngine.eval(js, ScriptBindings()) }
        Assert.assertTrue("Current Rhino unexpectedly accepted: $js", result.isFailure)
    }
}
