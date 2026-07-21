package com.script

import com.script.rhino.RhinoScriptEngine
import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.NativeObject
import org.htmlunit.corejs.javascript.ScriptableObject
import org.htmlunit.corejs.javascript.TopLevel

/**
 * 脚本求值作用域:根标准对象的 isolate(对齐上游 TopLevel.createIsolate 范式)。
 * 写入(含脚本顶层 var/const 与 set 注入的绑定)落在自有 globalThis,
 * 名字解析经 globalThis 原型链回落父层;各实例互不可见。
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
    fun chainTo(parent: TopLevel) {
        globalThis.prototype = parent.globalThis
        copyBuiltins(parent, false)
        copyAssociatedValue(parent)
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

        private val root: TopLevel by lazy {
            RhinoScriptEngine.initialize() // 先装配全局 ContextFactory 再进 Context
            val cx = Context.enter()
            try {
                cx.initStandardObjects()
            } finally {
                Context.exit()
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
    }

}
