/*
 * Copyright (c) 2005, 2006, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.script.rhino

import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.NativeJavaPackage
import org.htmlunit.corejs.javascript.ScriptRuntime
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.VarScope
import org.htmlunit.corejs.javascript.WrapFactory
import org.htmlunit.corejs.javascript.lc.type.TypeInfo
import org.htmlunit.corejs.javascript.lc.type.TypeInfoFactory

/**
 * This wrap factory is used for security reasons. JSR 223 script
 * engine interface and JavaScript engine classes are run as bootstrap
 * classes. For example, java.lang.Class.forName method (when called without
 * class loader) uses caller's class loader. This may be exploited by script
 * authors to access classes otherwise not accessible. For example,
 * classes in sun.* namespace are normally not accessible to untrusted
 * code and hence should not be accessible to JavaScript run from
 * untrusted code.
 *
 * @author A. Sundararajan
 * @since 1.6
 */
object RhinoWrapFactory : WrapFactory() {

    private val factories = hashMapOf<Class<*>, JavaObjectWrapFactory>()

    override fun wrapAsJavaObject(
        cx: Context,
        scope: VarScope?,
        javaObject: Any,
        staticType: TypeInfo?
    ): Scriptable? {
        if (!RhinoClassShutter.visibleToScripts(javaObject)) {
            return null
        }
        val declaredType = staticType ?: TypeInfo.NONE
        val useRuntimeListType = javaObject.javaClass.name == JSoupElementsClassName &&
            List::class.java.isAssignableFrom(declaredType.asClass())
        val resolvedType = when {
            declaredType.shouldReplace() -> runtimeType(scope, javaObject)
            useRuntimeListType -> runtimeType(scope, javaObject)
            else -> declaredType
        }
        return wrapOrNull(scope, javaObject, resolvedType.asClass())
            ?: when {
                List::class.java.isAssignableFrom(resolvedType.asClass()) ->
                    CatchableNativeJavaList(
                        scope = scope,
                        javaObject = javaObject,
                        staticType = resolvedType,
                        declaredElementType = if (useRuntimeListType) declaredType.param(0) else null,
                    )

                Map::class.java.isAssignableFrom(resolvedType.asClass()) ->
                    CatchableNativeJavaMap(scope, javaObject, resolvedType)

                resolvedType.isArray -> CatchableNativeJavaArray(scope, javaObject, resolvedType)
                else -> CatchableNativeJavaObject(scope, javaObject, resolvedType)
            }
    }

    override fun wrapJavaClass(
        cx: Context,
        scope: VarScope,
        javaClass: Class<*>
    ): Scriptable {
        if (!RhinoClassShutter.visibleToScripts(javaClass)) {
            @Suppress("DEPRECATION")
            val pkg = NativeJavaPackage(javaClass.name, null)
            ScriptRuntime.setObjectProtoAndParent(pkg, scope)
            return pkg
        }
        return RhinoClassShutter.wrapJavaClass(scope, javaClass)
    }

    private fun wrapOrNull(
        scope: VarScope?,
        javaObject: Any,
        staticType: Class<*>?
    ): Scriptable? {
        return factories[javaObject.javaClass]?.wrap(scope, javaObject, staticType)
    }

    private fun runtimeType(scope: VarScope?, javaObject: Any): TypeInfo {
        val typeFactory = scope?.let {
            TypeInfoFactory.getOrElse(it, TypeInfoFactory.GLOBAL)
        } ?: TypeInfoFactory.GLOBAL
        return typeFactory.create(javaObject.javaClass)
    }

    fun register(clazz: Class<*>, factory: JavaObjectWrapFactory) {
        if (!factories.contains(clazz)) {
            factories.put(clazz, factory)
        }
    }

    private const val JSoupElementsClassName = "org.jsoup.select.Elements"

}
