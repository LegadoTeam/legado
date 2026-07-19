package com.script.rhino

import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.Function
import org.htmlunit.corejs.javascript.NativeJavaArray
import org.htmlunit.corejs.javascript.NativeJavaList
import org.htmlunit.corejs.javascript.NativeJavaMap
import org.htmlunit.corejs.javascript.NativeJavaMethod
import org.htmlunit.corejs.javascript.NativeJavaObject
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.lc.type.TypeInfo
import org.htmlunit.corejs.javascript.lc.type.TypeInfoFactory

open class CatchableNativeJavaObject(
    scope: Scriptable?,
    javaObject: Any,
    staticType: TypeInfo,
) : NativeJavaObject(scope, javaObject, staticType) {

    constructor(scope: Scriptable?, javaObject: Any, staticType: Class<*>?) :
        this(scope, javaObject, staticType.toTypeInfo())

    private val methodCache = CatchableJavaMethodCache()

    override fun get(name: String, start: Scriptable): Any? {
        return catchJavaInvocation {
            methodCache.wrap(name, super.get(name, start))
        }
    }

    override fun put(name: String, start: Scriptable, value: Any?) {
        catchJavaInvocation {
            super.put(name, start, value)
        }
    }
}

internal class CatchableNativeJavaList(
    scope: Scriptable?,
    javaObject: Any,
    staticType: TypeInfo,
) : NativeJavaList(scope, javaObject, staticType) {

    private val methodCache = CatchableJavaMethodCache()

    override fun get(name: String, start: Scriptable): Any? {
        return catchJavaInvocation {
            methodCache.wrap(name, super.get(name, start))
        }
    }

    override fun put(name: String, start: Scriptable, value: Any?) {
        catchJavaInvocation {
            super.put(name, start, value)
        }
    }
}

internal class CatchableNativeJavaMap(
    scope: Scriptable?,
    javaObject: Any,
    staticType: TypeInfo,
) : NativeJavaMap(scope, javaObject, staticType) {

    private val methodCache = CatchableJavaMethodCache()

    override fun get(name: String, start: Scriptable): Any? {
        return catchJavaInvocation {
            methodCache.wrap(name, super.get(name, start))
        }
    }

    override fun put(name: String, start: Scriptable, value: Any?) {
        catchJavaInvocation {
            super.put(name, start, value)
        }
    }
}

internal class CatchableNativeJavaArray(
    scope: Scriptable?,
    javaObject: Any,
    staticType: TypeInfo,
) : NativeJavaArray(scope, javaObject, staticType) {

    private val methodCache = CatchableJavaMethodCache()

    override fun get(name: String, start: Scriptable): Any? {
        return catchJavaInvocation {
            methodCache.wrap(name, super.get(name, start))
        }
    }

    override fun put(name: String, start: Scriptable, value: Any?) {
        catchJavaInvocation {
            super.put(name, start, value)
        }
    }
}

internal class CatchableJavaMethodCache {

    private val wrappers = HashMap<String, Pair<NativeJavaMethod, Function>>()

    fun wrap(name: String, value: Any?): Any? {
        if (value !is NativeJavaMethod) return value
        return synchronized(wrappers) {
            val cached = wrappers[name]
            if (cached?.first === value) {
                cached.second
            } else {
                CatchableJavaFunction(value).also { wrappers[name] = value to it }
            }
        }
    }
}

private class CatchableJavaFunction(
    private val function: Function,
) : Function by function {

    override fun call(
        context: Context,
        scope: Scriptable,
        thisObject: Scriptable,
        arguments: Array<Any>,
    ): Any? {
        return catchJavaInvocation {
            function.call(context, scope, thisObject, arguments)
        }
    }

    override fun construct(
        context: Context,
        scope: Scriptable,
        arguments: Array<Any>,
    ): Scriptable {
        return catchJavaInvocation {
            function.construct(context, scope, arguments)
        }
    }
}

internal inline fun <T> catchJavaInvocation(block: () -> T): T {
    try {
        return block()
    } catch (error: RuntimeException) {
        val cause = error.cause
        if (cause != null && error.message?.startsWith("Exception invoking ") == true) {
            throw Context.throwAsScriptRuntimeEx(cause)
        }
        throw error
    }
}

internal fun Class<*>?.toTypeInfo(): TypeInfo {
    return this?.let { TypeInfoFactory.GLOBAL.create(it) } ?: TypeInfo.NONE
}
