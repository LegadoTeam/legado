package com.script.rhino

import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.VarScope

class ReadOnlyJavaObject(scope: VarScope?, javaObject: Any, staticType: Class<*>?) :
    CatchableNativeJavaObject(scope, javaObject, staticType) {

    override fun has(name: String, start: Scriptable): Boolean {
        if (name.length > 3 && name.startsWith("set")) {
            val name = name.substring(3).replaceFirstChar { it.lowercase() }
            if (super.has(name, start)) {
                return false
            }
        }
        return super.has(name, start)
    }

    override fun get(name: String, start: Scriptable): Any? {
        if (name.length > 3 && name.startsWith("set")) {
            val name = name.substring(3).replaceFirstChar { it.lowercase() }
            if (super.has(name, start)) {
                return NOT_FOUND
            }
        }
        return super.get(name, start)
    }

    override fun put(
        name: String,
        start: Scriptable,
        value: Any?
    ) {
        // do nothing
    }

    companion object {
        val factory = JavaObjectWrapFactory { scope, javaObject, staticType ->
            ReadOnlyJavaObject(scope, javaObject, staticType)
        }
    }

}
