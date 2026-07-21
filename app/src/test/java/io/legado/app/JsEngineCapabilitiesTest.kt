package io.legado.app

import com.script.CompiledScript
import com.script.ScriptBindings
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
    fun legacyNestedConstReadAfterBlockRemainsVisible() {
        val script = """
            with ({}) {}
            function getParagraphsReviewByPage(comments) {
                var html = '';
                var refferContent = '';
                if (comments.length > 0) {
                    const paraContent = comments[0].expand.para_src_content || '';
                    refferContent = paraContent;
                }
                return JSON.stringify({
                    html: html,
                    paraContent: paraContent,
                    refferContent: refferContent
                });
            }
            getParagraphsReviewByPage([{
                expand: { para_src_content: 'paragraph' }
            }]);
        """.trimIndent()

        Assert.assertEquals(
            """{"html":"","paraContent":"paragraph","refferContent":"paragraph"}""",
            RhinoScriptEngine.eval(script, ScriptBindings()),
        )
    }

    @Test
    fun legacyConstDoesNotShadowRuntimeBindings() {
        val bindings = ScriptBindings().apply {
            this["book"] = "global"
        }
        val script = """
            with ({}) {}
            function readBook() {
                if (false) {
                    const book = 'local';
                }
                return book;
            }
            readBook();
        """.trimIndent()

        Assert.assertEquals("global", RhinoScriptEngine.eval(script, bindings))
    }

    @Test
    fun cachedCompilationDoesNotCaptureCallerBindings() {
        val bridge = CompileBridge()
        val compileScope = ScriptBindings().apply {
            this["bridge"] = bridge
            this["source"] = """
                with ({}) {}
                function readBook() {
                    if (false) {
                        const book = 'local';
                    }
                    return book;
                }
                readBook();
            """.trimIndent()
        }
        RhinoScriptEngine.eval("bridge.compile(source)", compileScope)

        val runScope = ScriptBindings().apply {
            this["book"] = "global"
        }
        Assert.assertEquals("global", bridge.compiled.eval(runScope))
    }

    @Test
    fun legacyConstRewriteSkipsResolvedAndNonValueNames() {
        val script = """
            with ({}) {}
            function scopedValues() {
                let value = 'outer';
                if (true) {
                    const value = 'inner';
                    const hidden = 'block';
                }
                return ({value: 1}).value + ':' + value + ':' + typeof (hidden);
            }
            scopedValues();
        """.trimIndent()

        Assert.assertEquals(
            "1:outer:undefined",
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
    fun dynamicEvalRealmKeepsJavaCryptoCallable() {
        val bindings = ScriptBindings().apply {
            this["java"] = MethodBridge()
        }
        val script = """
            var indirect = eval;
            [
                (0, eval)("var crypto = java.createSymmetricCrypto; crypto('tuple')"),
                indirect("java.createSymmetricCrypto('alias')"),
                new Function("var crypto = java.createSymmetricCrypto; return crypto('ctor')")()
            ].join(':');
        """.trimIndent()

        Assert.assertEquals(
            "tuple:alias:ctor",
            RhinoScriptEngine.eval(script, bindings),
        )
    }

    @Test
    fun optionalCatchBindingCompilesWithLegacyWithConst() {
        RhinoScriptEngine.compile(
            """
                with ({}) { const marker = 1; }
                try { throw marker; } catch {}
            """.trimIndent()
        )
    }

    @Test
    fun dynamicCompilationNormalizesNestedLegacyWithConst() {
        val script = """
            var indirect = eval;
            var viaEval = indirect(
                "function legacyEval() { " +
                "if (true) { with ({}) { const marker = 21; } } " +
                "try { throw 1; } catch {} return marker * 2; } legacyEval()"
            );
            var viaFunction = new Function(
                "if (true) { with ({}) { const marker = 6; } } " +
                "try { throw 1; } catch {} return marker * 7;"
            )();
            viaEval + ':' + viaFunction;
        """.trimIndent()

        Assert.assertEquals("42:42", RhinoScriptEngine.eval(script, ScriptBindings()))
    }

    @Test
    fun dynamicCompatibilityParsingPreservesEvalSyntaxErrors() {
        val script = """
            try {
                eval("with ({}) { const broken = ; }");
                'not-caught';
            } catch (error) {
                error.name;
            }
        """.trimIndent()

        Assert.assertEquals("SyntaxError", RhinoScriptEngine.eval(script, ScriptBindings()))
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
        val scope = RhinoScriptEngine.newStandardTopLevel()
        val context = Context.enter()
        try {
            val mapClass = ProtectedNativeJavaClass(scope, Class.forName("java.util.Map"))
            val entry = mapClass.get("Entry", mapClass)

            Assert.assertTrue(entry is Wrapper)
            Assert.assertTrue(entry is NativeJavaClass)
        } finally {
            Context.exit()
        }
    }

    @Test
    fun restAndSpreadSyntaxRemainSupported() {
        val supported = """
            function collect(head, ...tail) {
                return head + ':' + tail.join('-')
            }
            collect('x', 1, 2)
        """.trimIndent()
        Assert.assertEquals("x:1-2", RhinoScriptEngine.eval(supported, ScriptBindings()))

        assertUnsupported("function add(a, b) { return a + b }; add(...[1, 2])")
        Assert.assertEquals(
            2.0,
            RhinoScriptEngine.eval(
                "var [head, ...tail] = [1, 2, 3]; tail.length",
                ScriptBindings(),
            ),
        )
    }

    @Test
    fun readOnlyJavaMethodsKeepTheirDetachedReceiver() {
        RhinoWrapFactory.register(FactoryBridge::class.java, ReadOnlyJavaObject.factory)
        val bindings = ScriptBindings().apply {
            this["factoryBridge"] = FactoryBridge()
        }

        Assert.assertEquals(
            "initial",
            RhinoScriptEngine.eval(
                "var readValue = factoryBridge.getValue; readValue()",
                bindings,
            ),
        )
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

    class CompileBridge {
        lateinit var compiled: CompiledScript

        fun compile(source: String) {
            compiled = RhinoScriptEngine.compile(source)
        }
    }

    class ThrowingChild {
        fun fail(): Nothing = throw IllegalStateException("expected child failure")
    }

    class MethodBridge {
        fun createSymmetricCrypto(value: String): String = value
        fun createSymmetricCrypto(value: String, suffix: String): String = value + suffix
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
