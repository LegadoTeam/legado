package com.script.rhino

import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.VarScope

fun interface JavaObjectWrapFactory {

    fun wrap(scope: VarScope?, javaObject: Any, staticType: Class<*>?): Scriptable

}
