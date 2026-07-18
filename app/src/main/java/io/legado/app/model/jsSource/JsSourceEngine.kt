package io.legado.app.model.jsSource

import androidx.collection.LruCache
import com.script.CompiledScript
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.JsExtensions
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.getShareScope
import io.legado.app.utils.GSON
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Wrapper
import kotlin.coroutines.CoroutineContext

class JsSourceEngine(
    private val source: BookSource,
    private val coroutineContext: CoroutineContext? = null,
) : JsExtensions {

    override fun getSource(): BaseSource = source

    override fun getTag(): String = source.getTag()

    fun callFunction(name: String, args: List<Pair<String, Any?>>): String? {
        val scope = buildScope(args)
        if (ScriptableObject.getProperty(scope, name) !is Function) {
            throw NoStackTraceException("JS源缺少函数 $name")
        }
        val expression = "$name(${args.joinToString(", ") { it.first }})"
        val result = compile(expression).eval(scope, coroutineContext)
        return normalizeJsResult(result, scope, coroutineContext)
    }

    fun callFunctionIfExists(name: String, args: List<Pair<String, Any?>>): String? {
        val scope = buildScope(args)
        if (ScriptableObject.getProperty(scope, name) !is Function) {
            return null
        }
        val expression = "$name(${args.joinToString(", ") { it.first }})"
        val result = compile(expression).eval(scope, coroutineContext)
        return normalizeJsResult(result, scope, coroutineContext)
    }

    private fun buildScope(args: List<Pair<String, Any?>>): Scriptable {
        val mainJs = source.mainJs
        if (mainJs.isNullOrBlank()) {
            throw NoStackTraceException("mainJs 为空,不是JS源")
        }
        val bindings = buildScriptBindings {
            it["java"] = this
            it["source"] = source
            it["baseUrl"] = source.getKey()
            it["cookie"] = CookieStore
            it["cache"] = CacheManager
            args.forEach { (key, value) -> it[key] = value }
        }
        val sharedScope = source.jsLib?.takeIf { it.isNotBlank() }?.let {
            source.getShareScope(coroutineContext)
        }
        val scope = if (sharedScope == null) {
            RhinoScriptEngine.getRuntimeScope(bindings)
        } else {
            bindings.apply { prototype = sharedScope }
        }
        compile(mainJs).eval(scope, coroutineContext)
        return scope
    }

    companion object {
        private const val RESULT_BINDING = "__legadoJsSourceResult"
        private val scriptCache = LruCache<String, CompiledScript>(64)

        private fun compile(script: String): CompiledScript {
            scriptCache[script]?.let { return it }
            return RhinoScriptEngine.compile(script).also { scriptCache.put(script, it) }
        }

        fun normalizeJsResult(
            result: Any?,
            scope: Scriptable,
            coroutineContext: CoroutineContext? = null,
        ): String? {
            var value = result
            if (value is Wrapper) {
                value = value.unwrap()
            }
            return when {
                value == null || value is Undefined -> null
                value is String -> value
                value is CharSequence -> value.toString()
                value is Scriptable -> {
                    ScriptableObject.putProperty(scope, RESULT_BINDING, value)
                    val json = compile("JSON.stringify($RESULT_BINDING)")
                        .eval(scope, coroutineContext)
                    when (json) {
                        null, is Undefined -> null
                        else -> json.toString()
                    }
                }

                else -> GSON.toJson(value)
            }
        }
    }
}
