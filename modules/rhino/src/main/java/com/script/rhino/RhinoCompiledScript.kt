package com.script.rhino

import com.script.CompiledScript
import com.script.ScriptException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.ContinuationPending
import org.htmlunit.corejs.javascript.RhinoException
import org.htmlunit.corejs.javascript.Script
import org.htmlunit.corejs.javascript.VarScope
import java.io.IOException
import kotlin.coroutines.CoroutineContext

internal class RhinoCompiledScript(
    private val script: Script,
    private val source: String,
    private val sourceName: String,
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
            throw RhinoScriptEngine.createScriptException(re, source, sourceName)
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
                throw RhinoScriptEngine.createScriptException(re, source, sourceName)
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
