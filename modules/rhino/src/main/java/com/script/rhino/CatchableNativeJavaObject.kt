package com.script.rhino

import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.Function
import org.htmlunit.corejs.javascript.NativeJavaArray
import org.htmlunit.corejs.javascript.NativeJavaList
import org.htmlunit.corejs.javascript.NativeJavaMap
import org.htmlunit.corejs.javascript.NativeJavaMethod
import org.htmlunit.corejs.javascript.NativeJavaObject
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.Undefined
import org.htmlunit.corejs.javascript.VarScope
import org.htmlunit.corejs.javascript.Wrapper
import org.htmlunit.corejs.javascript.lc.type.TypeInfo
import org.htmlunit.corejs.javascript.lc.type.TypeInfoFactory

open class CatchableNativeJavaObject(
    scope: VarScope?,
    javaObject: Any,
    staticType: TypeInfo,
) : NativeJavaObject(scope, javaObject, staticType) {

    constructor(scope: VarScope?, javaObject: Any, staticType: Class<*>?) :
        this(
            scope,
            javaObject,
            staticType?.let(TypeInfoFactory.GLOBAL::create) ?: TypeInfo.NONE,
        )

    private val methodCache = CatchableJavaMethodCache(this)

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
    scope: VarScope?,
    javaObject: Any,
    staticType: TypeInfo,
    private val declaredElementType: TypeInfo? = null,
) : NativeJavaList(scope, javaObject, staticType) {

    private val methodCache = CatchableJavaMethodCache(this)

    @Suppress("UNCHECKED_CAST")
    private val mutableList = javaObject as MutableList<Any?>

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

    override fun get(index: Int, start: Scriptable): Any? {
        val elementType = declaredElementType ?: return super.get(index, start)
        if (index !in mutableList.indices) return Undefined.instance
        val value = mutableList[index]
        val context = Context.getCurrentContext() ?: return value
        return context.wrapFactory.wrap(context, parentScope, value, elementType)
    }

    override fun put(index: Int, start: Scriptable, value: Any?) {
        val elementType = declaredElementType ?: return super.put(index, start, value)
        if (index < 0) return super.put(index, start, value)
        val javaValue = Context.jsToJava(value, elementType)
        if (index == mutableList.size) {
            mutableList.add(javaValue)
            return
        }
        ensureCapacity(index + 1)
        mutableList[index] = javaValue
    }

    private fun ensureCapacity(minCapacity: Int) {
        (mutableList as? ArrayList<Any?>)?.ensureCapacity(minCapacity)
        while (mutableList.size < minCapacity) {
            mutableList.add(null)
        }
    }
}

internal class CatchableNativeJavaMap(
    scope: VarScope?,
    javaObject: Any,
    staticType: TypeInfo,
) : NativeJavaMap(scope, javaObject, staticType) {

    private val methodCache = CatchableJavaMethodCache(this)

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
    scope: VarScope?,
    javaObject: Any,
    staticType: TypeInfo,
) : NativeJavaArray(scope, javaObject, staticType) {

    private val methodCache = CatchableJavaMethodCache(this)

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

internal class CatchableJavaMethodCache(
    private val receiver: Scriptable,
) {

    private val wrappers = HashMap<String, Pair<NativeJavaMethod, Function>>()

    fun wrap(name: String, value: Any?): Any? {
        if (value !is NativeJavaMethod) return value
        return synchronized(wrappers) {
            val cached = wrappers[name]
            if (cached?.first === value) {
                cached.second
            } else {
                CatchableJavaFunction(value, receiver).also { wrappers[name] = value to it }
            }
        }
    }
}

private class CatchableJavaFunction(
    private val function: Function,
    private val receiver: Scriptable,
) : Function by function {

    override fun call(
        context: Context,
        scope: VarScope,
        thisObject: Scriptable,
        arguments: Array<Any>,
    ): Any? {
        return catchJavaInvocation {
            val target = if (thisObject.hasJavaReceiver()) thisObject else receiver
            function.call(context, scope, target, arguments)
        }
    }

    override fun construct(
        context: Context,
        scope: VarScope,
        arguments: Array<Any>,
    ): Scriptable {
        return catchJavaInvocation {
            function.construct(context, scope, arguments)
        }
    }
}

private fun Scriptable.hasJavaReceiver(): Boolean {
    var current: Scriptable? = this
    while (current != null) {
        if (current is Wrapper) return true
        current = current.prototype
    }
    return false
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
