package com.script

import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.NativeObject
import org.htmlunit.corejs.javascript.ScriptableObject
import org.htmlunit.corejs.javascript.xmlimpl.XMLLoaderImpl

class ScriptBindings : NativeObject() {

    companion object {
        private val xmlLoader = XMLLoaderImpl()

        private val topLevelScope: ScriptableObject by lazy {
            val cx = Context.enter()
            try {
                cx.initStandardObjects()
            } finally {
                Context.exit()
            }
        }
    }

    init {
        prototype = topLevelScope
        xmlLoader.load(this, false)
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

}
