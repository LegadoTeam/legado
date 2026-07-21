package com.script.rhino

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.htmlunit.corejs.javascript.Context
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

val rhinoContext: RhinoContext
    get() = Context.getCurrentContext() as RhinoContext

val rhinoContextOrNull: RhinoContext?
    get() = Context.getCurrentContext() as? RhinoContext

@Suppress("LEAKED_IN_PLACE_LAMBDA", "WRONG_INVOCATION_KIND")
@OptIn(ExperimentalContracts::class)
inline fun <T> suspendContinuation(crossinline block: suspend CoroutineScope.() -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    // HtmlUnit 5.3 continuations retain interpreter state tied to one Context;
    // crossing coroutine dispatchers with that state can leave the script suspended.
    return runBlocking { block() }
}

inline fun <T> runScriptWithContext(context: CoroutineContext, block: () -> T): T {
    val rhinoContext = enterRhinoContext()
    val previousCoroutineContext = rhinoContext.coroutineContext
    rhinoContext.coroutineContext = context.minusKey(ContinuationInterceptor)
    try {
        return block()
    } finally {
        rhinoContext.coroutineContext = previousCoroutineContext
        Context.exit()
    }
}

suspend inline fun <T> runScriptWithContext(block: () -> T): T {
    return runScriptWithContext(coroutineContext, block)
}

@PublishedApi
internal fun enterRhinoContext(): RhinoContext {
    RhinoScriptEngine.initialize()
    val context = Context.enter()
    if (context is RhinoContext) {
        return context
    }
    Context.exit()
    throw IllegalStateException(
        "线程已绑定非 RhinoContext: ${context.javaClass.name}"
    )
}
