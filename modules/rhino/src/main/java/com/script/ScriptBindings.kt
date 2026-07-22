package com.script

import com.script.rhino.RhinoScriptEngine
import org.htmlunit.corejs.javascript.ConcurrentNativeObject
import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.NativeObject
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.ScriptableObject
import org.htmlunit.corejs.javascript.TopLevel

class SharedGlobalStateHandle internal constructor(
    internal val key: String,
    internal val generation: Long,
)

/**
 * 脚本求值作用域:根标准对象的 isolate(对齐上游 TopLevel.createIsolate 范式)。
 * 顶层声明和注入绑定保留在当前作用域;指定共享键时,显式 globalThis/global
 * 指向来源级真实全局对象,恢复旧版书源跨规则保存配置状态的语义。
 */
class ScriptBindings : TopLevel(newIsolateGlobal()) {

    init {
        copyBuiltins(root, false)
        copyAssociatedValue(root)
    }

    /**
     * 把本作用域挂到 [parent] 之下:父层定义(jsLib/CryptoJS 共享库或
     * 全新标准对象)对本作用域可读,写入仍落本作用域。
     */
    fun chainTo(parent: TopLevel, sharedGlobalState: SharedGlobalStateHandle? = null) {
        copyBuiltins(parent, false)
        copyAssociatedValue(parent)
        if (sharedGlobalState == null) {
            globalThis.prototype = parent.globalThis
            return
        }

        val sharedGlobal = getSharedGlobal(sharedGlobalState, parent.globalThis)
        globalThis.prototype = sharedGlobal
        globalThis.put("globalThis", globalThis, sharedGlobal)
        globalThis.setAttributes("globalThis", ScriptableObject.DONTENUM)
        globalThis.put("global", globalThis, sharedGlobal)
        globalThis.setAttributes("global", ScriptableObject.DONTENUM)
    }

    operator fun set(key: String, value: Any?) {
        Context.enter()
        try {
            put(key, this, Context.javaToJS(value, this))
        } finally {
            Context.exit()
        }
    }

    operator fun set(index: Int, value: Any?) {
        Context.enter()
        try {
            put(index, this, Context.javaToJS(value, this))
        } finally {
            Context.exit()
        }
    }

    fun put(key: String, value: Any?) {
        set(key, value)
    }

    companion object {

        private val sharedGlobalLock = Any()
        private val sharedGlobals = HashMap<String, SharedGlobalObject>()
        private val sharedGlobalGenerations = HashMap<String, Long>()

        private val root: TopLevel by lazy {
            RhinoScriptEngine.initialize()
            val cx = Context.enter()
            try {
                cx.initStandardObjects()
            } finally {
                Context.exit()
            }
        }

        fun getSharedGlobalStateHandle(key: String): SharedGlobalStateHandle {
            return synchronized(sharedGlobalLock) {
                SharedGlobalStateHandle(
                    key,
                    sharedGlobalGenerations.getOrPut(key) { 0L },
                )
            }
        }

        fun removeSharedGlobalStates(keyPrefix: String) {
            invalidateSharedGlobals { it.startsWith(keyPrefix) }
        }

        fun removeSharedGlobalState(handle: SharedGlobalStateHandle) {
            invalidateSharedGlobals { it == handle.key }
        }

        fun removeSharedGlobalStatesBySource(
            sourceClassName: String,
            sourceHash: String,
        ) {
            val keySuffix = ":$sourceClassName:$sourceHash"
            invalidateSharedGlobals { it.endsWith(keySuffix) }
        }

        fun clearSharedGlobalStates() {
            invalidateSharedGlobals { true }
        }

        private fun getSharedGlobal(
            handle: SharedGlobalStateHandle,
            parent: Scriptable,
        ): SharedGlobalObject {
            return synchronized(sharedGlobalLock) {
                val generation = sharedGlobalGenerations.getOrPut(handle.key) { 0L }
                if (generation != handle.generation) {
                    SharedGlobalObject(parent)
                } else {
                    sharedGlobals.getOrPut(handle.key) { SharedGlobalObject(parent) }
                }
            }
        }

        private inline fun invalidateSharedGlobals(matches: (String) -> Boolean) {
            synchronized(sharedGlobalLock) {
                (sharedGlobalGenerations.keys + sharedGlobals.keys)
                    .filter(matches)
                    .toSet()
                    .forEach { key ->
                        sharedGlobalGenerations[key] =
                            (sharedGlobalGenerations[key] ?: 0L) + 1L
                        sharedGlobals.remove(key)
                    }
            }
        }

        private fun newIsolateGlobal(): ScriptableObject {
            val global = NativeObject()
            global.prototype = root.globalThis
            global.parentScope = null
            global.put("globalThis", global, global)
            global.setAttributes("globalThis", ScriptableObject.DONTENUM)
            global.put("global", global, global)
            global.setAttributes("global", ScriptableObject.DONTENUM)
            return global
        }

        private class SharedGlobalObject(parent: Scriptable) : ConcurrentNativeObject() {

            init {
                prototype = parent
                parentScope = null
                put("globalThis", this, this)
                setAttributes("globalThis", ScriptableObject.DONTENUM)
                put("global", this, this)
                setAttributes("global", ScriptableObject.DONTENUM)
            }
        }
    }
}
