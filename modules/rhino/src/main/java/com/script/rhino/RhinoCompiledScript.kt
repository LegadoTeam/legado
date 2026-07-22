package com.script.rhino

import com.script.CompiledScript
import com.script.ScriptException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.ContinuationPending
import org.htmlunit.corejs.javascript.JavaScriptException
import org.htmlunit.corejs.javascript.RhinoException
import org.htmlunit.corejs.javascript.Script
import org.htmlunit.corejs.javascript.VarScope
import java.io.IOException
import kotlin.coroutines.CoroutineContext

internal class RhinoCompiledScript(
    private val script: Script
) : CompiledScript() {

    override fun eval(scope: VarScope, coroutineContext: CoroutineContext?): Any? {
        val cx = Context.enter() as RhinoContext
        val previousCoroutineContext = cx.coroutineContext
        if (coroutineContext != null && coroutineContext[Job] != null) {
            cx.coroutineContext = coroutineContext
        }
        cx.allowScriptRun = true
        cx.recursiveCount++
        val result: Any?
        try {
            cx.checkRecursive()
            val ret = script.exec(cx, scope, RhinoScriptEngine.topLevelThis(scope))
            result = RhinoScriptEngine.unwrapReturnValue(ret)
        } catch (re: RhinoException) {
            val line = if (re.lineNumber() == 0) -1 else re.lineNumber()
            val msg: String = if (re is JavaScriptException) {
                re.value.toString()
            } else {
                re.toString()
            }
            val se = ScriptException(msg, re.sourceName(), line)
            se.initCause(re)
            throw se
        } finally {
            cx.coroutineContext = previousCoroutineContext
            cx.allowScriptRun = false
            cx.recursiveCount--
            Context.exit()
        }
        return result
    }

    override suspend fun evalSuspend(scope: VarScope): Any? {
        val cx = Context.enter() as RhinoContext
        Context.exit()
        var ret: Any?
        withContext(RhinoContextElement(cx)) {
            cx.allowScriptRun = true
            cx.recursiveCount++
            try {
                cx.checkRecursive()
                try {
                    ret = cx.executeScriptWithContinuations(script, scope)
                } catch (e: ContinuationPending) {
                    var pending = e
                    while (true) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val suspendFunction = pending.applicationState as suspend () -> Any?
                            val functionResult = suspendFunction()
                            val continuation = pending.continuation
                            ret = cx.resumeContinuation(continuation, scope, functionResult)
                            break
                        } catch (e: ContinuationPending) {
                            pending = e
                        }
                    }
                }
            } catch (re: RhinoException) {
                val line = if (re.lineNumber() == 0) -1 else re.lineNumber()
                val msg: String = if (re is JavaScriptException) {
                    re.value.toString()
                } else {
                    re.toString()
                }
                val se = ScriptException(msg, re.sourceName(), line)
                se.initCause(re)
                throw se
            } catch (var14: IOException) {
                throw ScriptException(var14)
            } finally {
                cx.allowScriptRun = false
                cx.recursiveCount--
            }
        }
        return RhinoScriptEngine.unwrapReturnValue(ret)
    }

}
