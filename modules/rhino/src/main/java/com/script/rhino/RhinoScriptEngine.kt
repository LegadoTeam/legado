package com.script.rhino

import com.script.CompiledScript
import com.script.ScriptBindings
import com.script.ScriptException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.htmlunit.corejs.javascript.Callable
import org.htmlunit.corejs.javascript.ConsString
import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.ContextFactory
import org.htmlunit.corejs.javascript.ContinuationPending
import org.htmlunit.corejs.javascript.JavaScriptException
import org.htmlunit.corejs.javascript.RhinoException
import org.htmlunit.corejs.javascript.Script
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.ScriptableObject
import org.htmlunit.corejs.javascript.TopLevel
import org.htmlunit.corejs.javascript.Undefined
import org.htmlunit.corejs.javascript.VarScope
import org.htmlunit.corejs.javascript.Wrapper
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import kotlin.coroutines.CoroutineContext

/**
 * Rhino 求值引擎单例:eval/compile 唯一入口。首次触达装配全局 ContextFactory
 * (ES6/解释模式/ClassShutter/WrapFactory/指令观察);doTopCall 两个重载统一落
 * allowScriptRun 闸门与协程取消检查——脚本只能经本引擎入口运行。
 */
object RhinoScriptEngine {

    private const val SOURCE_NAME = "<Unknown source>"

    fun initialize() = Unit

    fun eval(js: String, bindingsConfig: ScriptBindings.() -> Unit = {}): Any? {
        val bindings = ScriptBindings()
        Context.enter()
        try {
            bindings.apply(bindingsConfig)
        } finally {
            Context.exit()
        }
        return eval(js, bindings)
    }

    @Throws(ScriptException::class)
    fun eval(js: String, scope: VarScope): Any? {
        return eval(StringReader(js), scope, null)
    }

    @Throws(ScriptException::class)
    fun eval(js: String, scope: VarScope, coroutineContext: CoroutineContext?): Any? {
        return eval(StringReader(js), scope, coroutineContext)
    }

    @Throws(ScriptException::class)
    fun eval(
        reader: Reader,
        scope: VarScope,
        coroutineContext: CoroutineContext? = null
    ): Any? {
        val cx = Context.enter() as RhinoContext
        val previousCoroutineContext = cx.coroutineContext
        if (coroutineContext != null && coroutineContext[Job] != null) {
            cx.coroutineContext = coroutineContext
        }
        cx.allowScriptRun = true
        cx.recursiveCount++
        val ret: Any?
        var source = ""
        try {
            cx.checkRecursive()
            source = reader.readText()
            val script = cx.compileWithCompatibility(source, SOURCE_NAME, 1, scope)
            ret = script.exec(cx, scope, topLevelThis(scope))
        } catch (re: RhinoException) {
            val line = if (re.lineNumber() == 0) -1 else re.lineNumber()
            val baseMsg: String = if (re is JavaScriptException) {
                re.value.toString()
            } else {
                re.toString()
            }
            val se = ScriptException(
                buildErrorMessage(baseMsg, source, line, re.columnNumber()),
                re.sourceName(),
                line,
            )
            se.initCause(re)
            throw se
        } catch (var14: IOException) {
            throw ScriptException(var14)
        } finally {
            cx.coroutineContext = previousCoroutineContext
            cx.allowScriptRun = false
            cx.recursiveCount--
            Context.exit()
        }
        return unwrapReturnValue(ret)
    }

    @Throws(ScriptException::class)
    suspend fun evalSuspend(js: String, scope: VarScope): Any? {
        return evalSuspend(StringReader(js), scope)
    }

    @Throws(ContinuationPending::class)
    suspend fun evalSuspend(reader: Reader, scope: VarScope): Any? {
        val cx = Context.enter() as RhinoContext
        Context.exit()
        var ret: Any?
        withContext(RhinoContextElement(cx)) {
            cx.allowScriptRun = true
            cx.recursiveCount++
            var source = ""
            try {
                cx.checkRecursive()
                source = reader.readText()
                val script = cx.compileWithCompatibility(source, SOURCE_NAME, 1, scope)
                try {
                    ret = cx.executeScriptWithContinuations(script, scope)
                } catch (e: ContinuationPending) {
                    var pending = e
                    while (true) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val suspendFunction = pending.applicationState as suspend () -> Any?
                            val continuation = pending.continuation
                            ret = cx.resumeContinuation(continuation, scope, suspendFunction())
                            break
                        } catch (e: ContinuationPending) {
                            pending = e
                        }
                    }
                }
            } catch (re: RhinoException) {
                val line = if (re.lineNumber() == 0) -1 else re.lineNumber()
                val baseMsg: String = if (re is JavaScriptException) {
                    re.value.toString()
                } else {
                    re.toString()
                }
                val se = ScriptException(
                    buildErrorMessage(baseMsg, source, line, re.columnNumber()),
                    re.sourceName(),
                    line,
                )
                se.initCause(re)
                throw se
            } catch (var14: IOException) {
                throw ScriptException(var14)
            } finally {
                cx.allowScriptRun = false
                cx.recursiveCount--
            }
        }
        return unwrapReturnValue(ret)
    }

    /** 全新一套标准对象顶层作用域,可作 chainTo 的父层复用 */
    fun newStandardTopLevel(): TopLevel {
        val cx = Context.enter()
        try {
            return cx.initStandardObjects()
        } finally {
            Context.exit()
        }
    }

    /** 对齐旧语义:每次调用挂接一套全新标准对象,脚本对内建原型的改动不跨调用泄漏 */
    fun getRuntimeScope(bindings: ScriptBindings): ScriptBindings {
        bindings.chainTo(newStandardTopLevel())
        return bindings
    }

    @Throws(ScriptException::class)
    fun compile(script: String): CompiledScript {
        return this.compile(StringReader(script) as Reader)
    }

    @Throws(ScriptException::class)
    fun compile(script: Reader): CompiledScript {
        val cx = Context.enter()
        val ret: RhinoCompiledScript
        try {
            val source = script.readText()
            val scr = (cx as RhinoContext).compileWithCompatibility(source, SOURCE_NAME, 1)
            ret = RhinoCompiledScript(scr)
        } catch (var9: Exception) {
            throw ScriptException(var9)
        } finally {
            Context.exit()
        }
        return ret
    }

    fun unwrapReturnValue(result: Any?): Any? {
        var result1 = result
        if (result1 is Wrapper) {
            result1 = result1.unwrap()
        }
        if (result1 is ConsString) {
            result1 = result1.toString()
        }
        return if (result1 is Undefined) null else result1
    }

    private fun buildErrorMessage(
        baseMessage: String,
        source: String,
        errorLine: Int,
        errorColumn: Int,
    ): String {
        if (errorLine <= 0) return baseMessage
        val lines = source.split('\n')
        val errorIndex = errorLine - 1
        if (errorIndex !in lines.indices) return baseMessage

        val startIndex = maxOf(0, errorIndex - 1)
        val endIndex = minOf(lines.lastIndex, errorIndex + 1)
        val lineNumberWidth = (endIndex + 1).toString().length
        return buildString {
            append(baseMessage)
            append("\nSource context:\n")
            for (index in startIndex..endIndex) {
                val marker = if (index == errorIndex) ">" else " "
                val lineContent = lines[index].removeSuffix("\r")
                append(marker)
                    .append(' ')
                    .append((index + 1).toString().padStart(lineNumberWidth))
                    .append(": ")
                    .append(lineContent)
                    .append('\n')
                if (index == errorIndex && errorColumn > 0) {
                    append(" ".repeat(4 + lineNumberWidth + (errorColumn - 1).coerceAtMost(400)))
                        .append('^')
                        .append('\n')
                }
            }
        }.trimEnd()
    }

    internal fun topLevelThis(scope: VarScope): Scriptable {
        return ScriptableObject.getTopLevelScope(scope).globalThis
    }

    init {
        ContextFactory.initGlobal(object : ContextFactory() {

            override fun makeContext(): Context {
                val cx = RhinoContext(this)
                cx.languageVersion = Context.VERSION_ES6
                cx.setInterpretedMode(true)
                cx.setClassShutter(RhinoClassShutter)
                cx.wrapFactory = RhinoWrapFactory
                cx.instructionObserverThreshold = 10000
                cx.maximumInterpreterStackDepth = 1000
                return cx
            }

            override fun hasFeature(cx: Context, featureIndex: Int): Boolean {
                @Suppress("UNUSED_EXPRESSION")
                return when (featureIndex) {
                    Context.FEATURE_E4X -> true
                    Context.FEATURE_ENABLE_JAVA_MAP_ACCESS -> true
                    // 非严格裸调用的 this 取当次顶层调用作用域的 globalThis:
                    // jsLib 函数经 this.java/this.cache 访问书源执行环境的惯用法依赖此语义
                    Context.FEATURE_LEGADO_DYNAMIC_DEFAULT_THIS -> true
                    // 间接 eval((0,eval)/别名调用)与 Function 构造器在当次顶层调用作用域
                    // 求值:被 eval 的书源代码经此可见 java/cookie 等运行时绑定
                    Context.FEATURE_LEGADO_DYNAMIC_EVAL_REALM -> true
                    else -> super.hasFeature(cx, featureIndex)
                }
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                if (cx is RhinoContext) {
                    cx.ensureActive()
                }
            }

            override fun doTopCall(
                callable: Callable,
                cx: Context,
                scope: VarScope,
                thisObj: Scriptable?,
                args: Array<Any>
            ): Any? {
                try {
                    ensureScriptRunAllowed(cx)
                    return super.doTopCall(callable, cx, scope, thisObj, args)
                } catch (e: RhinoInterruptError) {
                    throw e.cause
                }
            }

            override fun doTopCall(
                script: Script,
                cx: Context,
                scope: VarScope,
                thisObj: Scriptable?
            ): Any? {
                try {
                    ensureScriptRunAllowed(cx)
                    return super.doTopCall(script, cx, scope, thisObj)
                } catch (e: RhinoInterruptError) {
                    throw e.cause
                }
            }

            private fun ensureScriptRunAllowed(cx: Context) {
                if (cx is RhinoContext) {
                    if (!cx.allowScriptRun) {
                        error("Not allow run script in unauthorized way.")
                    }
                    cx.ensureActive()
                }
            }
        })
    }

}
