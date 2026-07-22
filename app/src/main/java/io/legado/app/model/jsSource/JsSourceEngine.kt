package io.legado.app.model.jsSource

import androidx.collection.LruCache
import com.script.CompiledScript
import com.script.ScriptBindings
import com.script.buildScriptBindings
import com.script.rhino.RhinoContext
import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.JsExtensions
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.getShareScope
import io.legado.app.help.source.getSharedGlobalStateKey
import io.legado.app.model.SharedJsScope
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.NativeJSON
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.ScriptableObject
import org.htmlunit.corejs.javascript.Undefined
import org.htmlunit.corejs.javascript.Wrapper
import org.htmlunit.corejs.javascript.Function as JsFunction
import kotlin.coroutines.CoroutineContext

/**
 * 纯JS单文件源执行器(spec §2)。
 * 每次调用新建 scope(并发隔离):绑定 → 挂共享原型 → eval 主脚本 → eval 调用表达式。
 * 只走 eval 通道(allowScriptRun 正确开闸;invokeMethod 无先例且闸门不开,见 spec §7-4)。
 * args 以绑定进 scope,既是函数参数也是环境绑定,与声明式源 key/page/book 惯例一致。
 */
class JsSourceEngine(
    private val source: BookSource,
    private val coroutineContext: CoroutineContext? = null,
) : JsExtensions {

    override fun getSource(): BaseSource = source

    override fun getTag(): String = source.getTag()

    /** 调用顶层函数并归一化返回值;函数缺失抛出明确错误 */
    fun callFunction(name: String, args: List<Pair<String, Any?>>): String? {
        val scope = buildScope(args)
        if (ScriptableObject.getProperty(scope, name) !is JsFunction) {
            throw NoStackTraceException("JS源缺少函数 $name")
        }
        val callExpr = "$name(${args.joinToString(", ") { it.first }})"
        val raw = compile(callExpr).eval(scope, coroutineContext)
        return normalizeJsResult(raw, coroutineContext)
    }

    /** 调用可选顶层函数:缺失返回 null(单次 eval,不做 hasFunction 预探测) */
    fun callFunctionIfExists(name: String, args: List<Pair<String, Any?>>): String? {
        return callOptionalFunction(name, args).value
    }

    internal fun callOptionalFunction(
        name: String,
        args: List<Pair<String, Any?>>,
    ): OptionalCallResult {
        val scope = buildScope(args)
        if (ScriptableObject.getProperty(scope, name) !is JsFunction) {
            return OptionalCallResult(exists = false, value = null)
        }
        val callExpr = "$name(${args.joinToString(", ") { it.first }})"
        val raw = compile(callExpr).eval(scope, coroutineContext)
        return OptionalCallResult(
            exists = true,
            value = normalizeJsResult(raw, coroutineContext),
        )
    }

    internal data class OptionalCallResult(
        val exists: Boolean,
        val value: String?,
    )

    private fun buildScope(args: List<Pair<String, Any?>>): ScriptBindings {
        val mainJs = source.mainJs
        if (mainJs.isNullOrBlank()) throw NoStackTraceException("mainJs 为空,不是JS源")
        val bindings = buildScriptBindings { b ->
            b["java"] = this
            b["source"] = source
            b["sourceApi"] = source
            b["baseUrl"] = source.getKey()
            b["cookie"] = CookieStore
            b["cache"] = CacheManager
            args.forEach { (k, v) -> b[k] = v }
        }
        val sharedGlobalStateKey = source.getSharedGlobalStateKey()
        val shared = source.getShareScope(coroutineContext)
            ?: SharedJsScope.getCryptoScope(source, coroutineContext)
        val scope = if (shared == null) {
            RhinoScriptEngine.getRuntimeScope(bindings)
        } else {
            bindings.apply { chainTo(shared, sharedGlobalStateKey) }
        }
        compile(mainJs).eval(scope, coroutineContext)
        return scope
    }

    companion object {

        /**
         * 主脚本与调用表达式共用的编译缓存。AnalyzeRule 的先例是每个实例一个 HashMap +
         * getOrPutLimit(16)(实例私有,天然无并发问题);这里选 androidx LruCache(64) 是因为
         * scriptCache 挂在 companion object 上,被所有 JsSourceEngine 实例/线程全局共享,
         * 需要自带同步的实现,LruCache 内部方法自带 synchronized。多源并发下 16 格易被
         * 主脚本+调用表达式挤爆致反复重编译,扩容到 64。
         */
        private val scriptCache = LruCache<String, CompiledScript>(64)

        private fun compile(js: String): CompiledScript {
            scriptCache[js]?.let { return it }
            return RhinoScriptEngine.compile(js).also { scriptCache.put(js, it) }
        }

        /**
         * JS 返回值归一化(spec §2-5):String 原样;null/Undefined→null;
         * NativeObject/NativeArray 等 Scriptable 改走 JS 引擎自身 JSON.stringify(回退方案,见下);
         * 其余包装对象走 GSON——BaseSource.loginUi 先例。
         * [coroutineContext] 可选,转发给 stringifyScriptable 用于在 stringify 执行期间
         * (JS 侧自定义 toJSON/getter 可能耗时)传递协程取消信号。
         */
        fun normalizeJsResult(result: Any?, coroutineContext: CoroutineContext? = null): String? {
            var value = result
            if (value is Wrapper) value = value.unwrap()
            return when {
                value == null || value is Undefined -> null
                value is String -> value
                value is CharSequence -> value.toString()
                value is Scriptable ->
                    stringifyScriptable(value, coroutineContext) ?: GSON.toJson(value)

                else -> GSON.toJson(value)
            }
        }

        /**
         * GSON 反射不认识 Rhino 内部惰性类型:'u' + page 这类拼接产生 ConsString,嵌套在
         * NativeArray/NativeObject 属性里会被反射成 {left,right,length,isFlat} 内部字段。
         * 改走引擎自身序列化:直调公开静态入口 NativeJSON.stringify。序列化仍可能执行
         * 返回对象的 toJSON/getter,因此临时 Context 复用与正常 eval 相同的执行闸门和
         * 协程取消检查,保留书源手写 `JSON.stringify(...)` 的完整语义。
         *
         * 调用点在 eval 之外,无活跃 Context 可复用,自行 Context.enter()/exit() 进临时
         * Context,并注入 [coroutineContext](同 RhinoScriptEngine.eval 的注入/还原写法)。
         * stringify 执行抛异常(典型如循环引用)包装成 NoStackTraceException 明确抛出,
         * GSON 反射遇循环引用会栈溢出,明确报错优于栈炸;取不到顶层作用域时返回 null
         * 交给调用方回退 GSON。
         */
        private fun stringifyScriptable(
            value: Scriptable,
            coroutineContext: CoroutineContext? = null,
        ): String? {
            val topScope = value.parentScope?.let { ScriptableObject.getTopLevelScope(it) }
                ?: return null
            val cx = Context.enter() as RhinoContext
            val previousCoroutineContext = cx.coroutineContext
            val previousAllowScriptRun = cx.allowScriptRun
            if (coroutineContext != null && coroutineContext[Job] != null) {
                cx.coroutineContext = coroutineContext
            }
            cx.allowScriptRun = true
            cx.recursiveCount++
            try {
                cx.checkRecursive()
                val raw = try {
                    NativeJSON.stringify(cx, topScope, value, null, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw NoStackTraceException("JS返回值 JSON.stringify 失败: ${e.message}")
                }
                return RhinoScriptEngine.unwrapReturnValue(raw) as? String
            } finally {
                cx.recursiveCount--
                cx.allowScriptRun = previousAllowScriptRun
                cx.coroutineContext = previousCoroutineContext
                Context.exit()
            }
        }
    }
}
