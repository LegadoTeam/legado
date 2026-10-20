package io.legado.app

import com.script.ScriptBindings
import com.script.rhino.RhinoContext
import com.script.rhino.RhinoScriptEngine
import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.Function
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.ScriptableObject
import org.htmlunit.corejs.javascript.TopLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RhinoJsonInitializationTest {

    @Test
    fun nativeJsonIsCompleteBeforeStandardScopeReturns() {
        RhinoScriptEngine.initialize()
        val global = RecordingGlobal()
        val cx = Context.enter()
        try {
            assertTrue(cx is RhinoContext)
            cx.initStandardObjects(TopLevel(global), false)

            // Observe publication directly: looking up JSON here would initialize a cold scope.
            assertNotNull("JSON was still lazy when its parent scope was returned", global.json)
            val json = requireNotNull(global.json)
            assertTrue(ScriptableObject.getProperty(json, "parse") is Function)
            assertTrue(ScriptableObject.getProperty(json, "stringify") is Function)
        } finally {
            Context.exit()
        }
    }

    @Test
    fun concurrentFirstJsonCallsUseTheSameParent() {
        val threads = 8
        val executor = Executors.newFixedThreadPool(threads)
        try {
            repeat(10) {
                val parent = RhinoScriptEngine.newStandardTopLevel()
                val children = List(threads) { ScriptBindings().apply { chainTo(parent) } }
                val start = CyclicBarrier(threads)
                val results = children.map { child ->
                    executor.submit<Any?> {
                        start.await(10, TimeUnit.SECONDS)
                        RhinoScriptEngine.eval(
                            "JSON.stringify({value: JSON.parse('{\"value\":1}').value})",
                            child,
                        )
                    }
                }
                results.forEach { result ->
                    assertEquals("{\"value\":1}", result.get(10, TimeUnit.SECONDS))
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun sourceCanStillOverrideJsonInItsSharedLibrary() {
        val parent = RhinoScriptEngine.getRuntimeScope(ScriptBindings())
        RhinoScriptEngine.eval("var JSON = { stringify: function() { return 'custom' } }", parent)
        val child = ScriptBindings().apply { chainTo(parent) }

        assertEquals("custom", RhinoScriptEngine.eval("JSON.stringify({value:1})", child))
    }

    @Test
    fun independentStandardScopesKeepSeparateJsonObjects() {
        val first = RhinoScriptEngine.newStandardTopLevel()
        val second = RhinoScriptEngine.newStandardTopLevel()
        assertNotSame(
            ScriptableObject.getProperty(first, "JSON"),
            ScriptableObject.getProperty(second, "JSON"),
        )
        val firstChild = ScriptBindings().apply { chainTo(first) }
        val secondChild = ScriptBindings().apply { chainTo(second) }
        RhinoScriptEngine.eval("JSON.stringify = function() { return 'first' }", firstChild)

        assertEquals("first", RhinoScriptEngine.eval("JSON.stringify({value:1})", firstChild))
        assertEquals("{\"value\":1}", RhinoScriptEngine.eval("JSON.stringify({value:1})", secondChild))
    }

    private class RecordingGlobal : TopLevel.GlobalThis() {
        var json: Scriptable? = null

        override fun defineProperty(name: String, value: Any?, attributes: Int) {
            super.defineProperty(name, value, attributes)
            if (name == "JSON" && value is Scriptable) json = value
        }
    }
}
