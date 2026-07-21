package com.script.rhino

import org.htmlunit.corejs.javascript.NativeJavaClass
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.VarScope

class ProtectedNativeJavaClass(
    scope: VarScope,
    javaClass: Class<*>,
    private val protectedName: Set<String> = emptySet()
) : NativeJavaClass(scope, javaClass) {

    private val methodCache = CatchableJavaMethodCache(this)

    override fun has(
        name: String,
        start: Scriptable?
    ): Boolean {
        if (protectedName.contains(name)) {
            return false
        }
        return super.has(name, start)
    }

    override fun get(name: String, start: Scriptable?): Any? {
        if (protectedName.contains(name)) {
            return NOT_FOUND
        }
        return catchJavaInvocation {
            methodCache.wrap(name, super.get(name, start))
        }
    }

    override fun put(
        name: String,
        start: Scriptable?,
        value: Any?
    ) {
        if (protectedName.contains(name)) {
            return
        }
        catchJavaInvocation {
            super.put(name, start, value)
        }
    }

    override fun unwrap(): Any? {
        return javaObject.toString()
    }

}
