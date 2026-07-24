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
}
