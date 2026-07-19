package com.script.rhino

import kotlinx.coroutines.ThreadContextElement
import org.htmlunit.corejs.javascript.Context
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class RhinoContextElement(private val context: Context) :
    ThreadContextElement<Boolean>,
    AbstractCoroutineContextElement(Key) {

    private val transferLock = ReentrantLock()

    override fun updateThreadContext(context: CoroutineContext): Boolean {
        val current = Context.getCurrentContext()
        if (current === this.context) {
            return false
        }
        check(current == null) { "Thread is already bound to another Rhino Context" }

        transferLock.lock()
        return try {
            @Suppress("DEPRECATION")
            Context.enter(this.context)
            true
        } catch (error: Throwable) {
            transferLock.unlock()
            throw error
        }
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Boolean) {
        if (oldState) {
            try {
                Context.exit()
            } finally {
                transferLock.unlock()
            }
        }
    }

    companion object Key : CoroutineContext.Key<RhinoContextElement>
}
