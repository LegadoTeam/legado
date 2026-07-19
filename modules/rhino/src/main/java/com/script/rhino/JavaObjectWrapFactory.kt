package com.script.rhino

import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.lc.type.TypeInfo

fun interface JavaObjectWrapFactory {

    fun wrap(scope: Scriptable?, javaObject: Any, staticType: TypeInfo): Scriptable

}
