package io.legado.app

import com.script.ScriptBindings
import com.script.ScriptException
import com.script.rhino.RhinoScriptEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RhinoScriptErrorContextTest {

    @Test
    fun evalErrorIncludesSourceContext() {
        val script = """
            var prefix = 'ok';
            throw 'boom';
            prefix;
        """.trimIndent()

        val exception = try {
            RhinoScriptEngine.eval(script, ScriptBindings())
            error("Expected JavaScript evaluation to fail")
        } catch (error: ScriptException) {
            error
        }

        assertEquals(2, exception.lineNumber)
        assertEquals(-1, exception.columnNumber)
        assertTrue(exception.message.contains("> 2: throw 'boom';"))
        assertTrue(exception.message.contains("  1: var prefix = 'ok';"))
    }

    @Test
    fun evalSuspendErrorIncludesSourceContext() = runBlocking {
        val script = """
            var prefix = 'ok';
            throw 'boom';
        """.trimIndent()

        val exception = try {
            RhinoScriptEngine.evalSuspend(script, ScriptBindings())
            error("Expected suspended JavaScript evaluation to fail")
        } catch (error: ScriptException) {
            error
        }

        assertEquals(2, exception.lineNumber)
        assertTrue(exception.message.contains("> 2: throw 'boom';"))
    }

    @Test
    fun compileErrorKeepsLineAndColumn() {
        val script = """
            var prefix = 'ok';
            var broken = ;
        """.trimIndent()

        val exception = try {
            RhinoScriptEngine.compile(script)
            error("Expected JavaScript compilation to fail")
        } catch (error: ScriptException) {
            error
        }

        assertEquals(2, exception.lineNumber)
        assertTrue(exception.columnNumber > 0)
        assertTrue(exception.message.contains("> 2: var broken = ;"))
    }

    @Test
    fun compiledScriptErrorUsesDefiningSourceContext() {
        val definitions = """
            function inner() {
                var value = null;
                return value.missing();
            }
            function outer() {
                return inner();
            }
        """.trimIndent()
        val scope = RhinoScriptEngine.getRuntimeScope(ScriptBindings())
        RhinoScriptEngine.compile(definitions).eval(scope)

        val exception = try {
            RhinoScriptEngine.compile("outer();").eval(scope)
            error("Expected nested JavaScript evaluation to fail")
        } catch (error: ScriptException) {
            error
        }

        assertEquals(3, exception.lineNumber)
        assertTrue(exception.message.contains("> 3:     return value.missing();"))
        assertTrue(exception.message.contains("  2:     var value = null;"))
    }
}
