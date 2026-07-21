package com.script.rhino

import kotlinx.coroutines.ThreadContextElement
import org.htmlunit.corejs.javascript.Context
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class RhinoContextElement(private val cx: Context) :
    ThreadContextElement<Boolean>,
    AbstractCoroutineContextElement(Key) {

    override fun updateThreadContext(context: CoroutineContext): Boolean {
        val current = Context.getCurrentContext()
        return when {
            current == null -> {
                @Suppress("DEPRECATION")
                Context.enter(cx)
                true
            }
            current === cx -> false
            else -> error("线程已绑定其他 Rhino Context，无法切换")
        }
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Boolean) {
        if (oldState) {
            Context.exit()
        }
    }

    companion object Key : CoroutineContext.Key<RhinoContextElement>
}
