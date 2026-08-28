package com.script.rhino

import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.VarScope

fun interface JavaObjectWrapFactory {

    fun wrap(scope: VarScope?, javaObject: Any, staticType: Class<*>?): Scriptable

}
