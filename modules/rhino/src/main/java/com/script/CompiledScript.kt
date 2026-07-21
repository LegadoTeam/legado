package com.script

import org.htmlunit.corejs.javascript.VarScope
import kotlin.coroutines.CoroutineContext

abstract class CompiledScript {

    @Throws(ScriptException::class)
    fun eval(scope: VarScope): Any? {
        return eval(scope, null)
    }

    @Throws(ScriptException::class)
    abstract fun eval(scope: VarScope, coroutineContext: CoroutineContext?): Any?

    @Throws(ScriptException::class)
    abstract suspend fun evalSuspend(scope: VarScope): Any?

}
