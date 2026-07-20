package io.legado.app

import com.script.ScriptBindings
import com.script.rhino.CatchableNativeJavaObject
import com.script.rhino.ProtectedNativeJavaClass
import com.script.rhino.ReadOnlyJavaObject
import com.script.rhino.RhinoScriptEngine
import com.script.rhino.RhinoWrapFactory
import org.intellij.lang.annotations.Language
import org.junit.Assert
import org.junit.Test
import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.NativeJavaClass
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.ScriptableObject
import org.htmlunit.corejs.javascript.Wrapper

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
    fun constForInAndBlockScopesRemainIndependent() {
        val script = """
            const params = { first: 1, second: 2 };
            const values = [];
            for (const key in params) {
                values.push(key + ':' + params[key]);
            }
            if (params.first === 1) {
                const result = 'left';
                values.push(result);
            } else {
                const result = 'right';
                values.push(result);
            }
            values.join('|');
        """.trimIndent()

        Assert.assertEquals(
            "first:1|second:2|left",
            RhinoScriptEngine.eval(script, ScriptBindings()),
        )
    }

    @Test
    fun javaInvocationFailuresRemainCatchableByScripts() {
        RhinoWrapFactory.register(FactoryBridge::class.java, ReadOnlyJavaObject.factory)
        val bindings = ScriptBindings().apply {
            this["bridge"] = ThrowingBridge()
        }
        Context.enter()
        try {
            bindings.put(
                "staticBridge",
                bindings,
                ProtectedNativeJavaClass(bindings, StaticThrowingBridge::class.java),
            )
        } finally {
            Context.exit()
        }
        Assert.assertTrue(
            ScriptableObject.getProperty(bindings, "bridge") is CatchableNativeJavaObject
        )
        val cases = listOf(
            "bridge.fail()",
            "bridge.child().fail()",
            "bridge.children()[0].fail()",
            "bridge.childMap().item.fail()",
            "bridge.childArray()[0].fail()",
            "staticBridge.failure = 'changed'",
            "java.lang.Integer.parseInt('invalid')",
        )

        cases.forEach { expression ->
            val script = """
                try {
                    $expression
                    'missed'
                } catch (error) {
                    'caught'
                }
            """.trimIndent()
            Assert.assertEquals(expression, "caught", RhinoScriptEngine.eval(script, bindings))
        }

        Assert.assertEquals(
            true,
            RhinoScriptEngine.eval("bridge.hidden() === null", bindings),
        )
        Assert.assertEquals(
            "undefined",
            RhinoScriptEngine.eval("typeof bridge.factoryChild().setValue", bindings),
        )
    }

    @Test
    fun javaMethodsRemainCallableInsideWithAndEvalScopes() {
        val bindings = ScriptBindings().apply {
            this["bridge"] = ThrowingBridge()
        }
        val script = """
            var directType = typeof bridge.child;
            var directCall = bridge.child() != null;
            var evalType = (function() {
                with (bridge) {
                    return eval('typeof child');
                }
            })();
            var evalCall = (function() {
                with (bridge) {
                    return eval('child() != null');
                }
            })();
            [directType, directCall, evalType, evalCall].join(':');
        """.trimIndent()

        Assert.assertEquals(
            "function:true:function:true",
            RhinoScriptEngine.eval(script, bindings),
        )
    }

    @Test
    fun nestedEvalKeepsDynamicallyLoadedFunctionsVisible() {
        val cases = listOf(
            "eval(\"function gzip(value) { return 'fn:' + value; }\"); gzip('ok')" to
                "fn:ok",
            "eval(\"var gzip = function(value) { return 'var:' + value; };\"); " +
                "eval(\"gzip('ok')\")" to "var:ok",
            "eval(\"let gzip = function(value) { return 'let:' + value; };\"); " +
                "eval(\"gzip('ok')\")" to "let:ok",
            "eval(\"const gzip = function(value) { return 'const:' + value; };\"); " +
                "eval(\"gzip('ok')\")" to "const:ok",
        )

        cases.forEach { (script, expected) ->
            Assert.assertEquals(expected, RhinoScriptEngine.eval(script, ScriptBindings()))
        }
    }

    @Test
    fun withScopedConstRemainsVisibleForLegacySources() {
        val script = """
            var javaImport = {};
            with (javaImport) {
                const gzip = function(value) { return 'gzip:' + value; };
            }
            gzip('ok');
        """.trimIndent()

        Assert.assertEquals("gzip:ok", RhinoScriptEngine.eval(script, ScriptBindings()))
    }

    @Test
    fun withCompatibilityOnlyRewritesDirectDeclarations() {
        val script = """
            var javaImport = {};
            with (javaImport) {
                function scoped() {
                    var before = typeof value;
                    { const value = 'inner'; }
                    return before + ':' + typeof value;
                }
                for (const key in { first: 1, second: 2 }) {}
            }
            scoped() + '|' + typeof key;
        """.trimIndent()

        Assert.assertEquals(
            "undefined:undefined|undefined",
            RhinoScriptEngine.eval(script, ScriptBindings()),
        )
    }

    @Test
    fun e4xDescendantExpressionsRemainSupported() {
        val script = """
            var data = <root><item_null><text>ok</text></item_null></root>;
            String(data..item_null.text);
        """.trimIndent()

        Assert.assertEquals("ok", RhinoScriptEngine.eval(script, ScriptBindings()))
    }

    @Test
    fun typeInfoWrappingUsesRegisteredFactories() {
        RhinoWrapFactory.register(FactoryBridge::class.java, ReadOnlyJavaObject.factory)
        val bindings = ScriptBindings().apply {
            this["factoryBridge"] = FactoryBridge()
        }

        Assert.assertTrue(
            ScriptableObject.getProperty(bindings, "factoryBridge") is ReadOnlyJavaObject
        )
        Assert.assertEquals(
            "undefined",
            RhinoScriptEngine.eval("typeof factoryBridge.setValue", bindings),
        )
    }

    @Test
    fun nestedJavaClassesKeepWrapperSemantics() {
        RhinoScriptEngine.initialize()
        val context = Context.enter()
        try {
            val scope = context.initStandardObjects()
            val mapClass = ProtectedNativeJavaClass(scope, Class.forName("java.util.Map"))
            val entry = mapClass.get("Entry", mapClass)

            Assert.assertTrue(entry is Wrapper)
            Assert.assertTrue(entry is NativeJavaClass)
        } finally {
            Context.exit()
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

    class ThrowingBridge {
        fun fail(): Nothing = throw IllegalStateException("expected failure")

        fun child() = ThrowingChild()

        fun children(): List<ThrowingChild> = listOf(ThrowingChild())

        fun childMap(): Map<String, ThrowingChild> = mapOf("item" to ThrowingChild())

        fun childArray(): Array<ThrowingChild> = arrayOf(ThrowingChild())

        fun factoryChild() = FactoryBridge()

        fun hidden(): Any = HiddenClassLoader()
    }

    class ThrowingChild {
        fun fail(): Nothing = throw IllegalStateException("expected child failure")
    }

    class HiddenClassLoader : ClassLoader()

    class FactoryBridge {
        var value: String = "initial"
    }

    class StaticThrowingBridge {
        companion object {
            @get:JvmStatic
            @set:JvmStatic
            var failure: String
                get() = "initial"
                set(@Suppress("UNUSED_PARAMETER") value) {
                    throw IllegalStateException("expected static setter failure")
                }
        }
    }
}
