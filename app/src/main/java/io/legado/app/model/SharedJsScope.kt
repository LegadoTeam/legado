package io.legado.app.model

import androidx.collection.LruCache
import com.google.gson.reflect.TypeToken
import com.script.CompiledScript
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import kotlinx.coroutines.runBlocking
import org.htmlunit.corejs.javascript.BaseFunction
import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.ScriptableObject
import org.htmlunit.corejs.javascript.VarScope
import splitties.init.appCtx
import java.io.File
import java.lang.ref.WeakReference
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

object SharedJsScope {

    private val cacheFolder by lazy { File(appCtx.cacheDir, "shareJs") }
    private val aCache by lazy { ACache.get(cacheFolder) }

    private val scopeMap = LruCache<String, WeakReference<ScriptBindings>>(16)
    private val scopeLock = Any()
    private val scopeCreationLocks = ConcurrentHashMap<String, ScopeCreationLock>()
    private const val CRYPTO_JS_ASSET = "scripts/cryptojs.min.js"
    private const val SECURE_RANDOM_BINDING = "__legadoSecureRandomInt"

    @Volatile
    private var cryptoJsText: String? = null
    private val cryptoTextLock = Any()

    @Volatile
    private var cryptoJsScript: CompiledScript? = null
    private val cryptoCompileLock = Any()

    private val cryptoScopeMap = LruCache<CryptoScopeKey, WeakReference<ScriptBindings>>(16)
    private val cryptoScopeLock = Any()
    private val cryptoScopeCreationLocks =
        ConcurrentHashMap<CryptoScopeKey, ScopeCreationLock>()
    private val secureRandom by lazy { SecureRandom() }

    private fun loadCryptoJs(): String? {
        cryptoJsText?.let { return it }
        synchronized(cryptoTextLock) {
            cryptoJsText?.let { return it }
            return try {
                appCtx.assets.open(CRYPTO_JS_ASSET).bufferedReader().use { reader ->
                    reader.readText().also { cryptoJsText = it }
                }
            } catch (error: Exception) {
                val message = "加载CryptoJS失败: ${error.message}"
                runCatching {
                    Debug.log(message)
                    AppLog.putDebug(message, error)
                }
                null
            }
        }
    }

    fun getCryptoScope(owner: Any, coroutineContext: CoroutineContext?): ScriptBindings? {
        val cryptoJs = getCompiledCryptoJs() ?: return null
        val key = CryptoScopeKey(owner, Thread.currentThread())
        getCachedCryptoScope(key)?.let { return it }
        val creationLock = acquireScopeCreationLock(cryptoScopeCreationLocks, key)
        try {
            return synchronized(creationLock.monitor) {
                getCachedCryptoScope(key) ?: createScope().also { scope ->
                    evaluateCryptoJs(cryptoJs, scope, coroutineContext)
                    preventExtensions(scope)
                    synchronized(cryptoScopeLock) {
                        cryptoScopeMap.put(key, WeakReference(scope))
                    }
                }
            }
        } finally {
            releaseScopeCreationLock(cryptoScopeCreationLocks, key, creationLock)
        }
    }

    internal fun installCryptoJs(
        scope: ScriptBindings,
        coroutineContext: CoroutineContext?,
    ): Boolean {
        val cryptoJs = getCompiledCryptoJs() ?: return false
        evaluateCryptoJs(cryptoJs, scope, coroutineContext)
        return true
    }

    fun getScope(jsLib: String?, coroutineContext: CoroutineContext?): ScriptBindings? {
        if (jsLib.isNullOrBlank()) {
            return null
        }
        val key = MD5Utils.md5Encode(jsLib)
        getCachedScope(key)?.let { return it }
        val creationLock = acquireScopeCreationLock(scopeCreationLocks, key)
        try {
            return synchronized(creationLock.monitor) {
                getCachedScope(key) ?: createScope().also { scope ->
                    installCryptoJs(scope, coroutineContext)
                    evaluateJsLib(jsLib, scope, coroutineContext)
                    preventExtensions(scope)
                    synchronized(scopeLock) {
                        scopeMap.put(key, WeakReference(scope))
                    }
                }
            }
        } finally {
            releaseScopeCreationLock(scopeCreationLocks, key, creationLock)
        }
    }

    fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) {
            return
        }
        val key = MD5Utils.md5Encode(jsLib)
        val creationLock = acquireScopeCreationLock(scopeCreationLocks, key)
        try {
            synchronized(creationLock.monitor) {
                if (jsLib.isJsonObject()) {
                    val jsMap: Map<String, String> = GSON.fromJson(
                        jsLib,
                        TypeToken.getParameterized(
                            Map::class.java,
                            String::class.java,
                            String::class.java,
                        ).type,
                    )
                    jsMap.values.forEach { value ->
                        if (value.isAbsUrl()) {
                            val fileName = MD5Utils.md5Encode(value)
                            aCache.remove(fileName)
                        }
                    }
                }
                synchronized(scopeLock) {
                    scopeMap.remove(key)
                }
                ScriptBindings.removeSharedGlobalStates("$key:")
            }
        } finally {
            releaseScopeCreationLock(scopeCreationLocks, key, creationLock)
        }
    }

    private fun getCachedScope(key: String): ScriptBindings? {
        return synchronized(scopeLock) {
            val reference = scopeMap[key] ?: return@synchronized null
            reference.get() ?: run {
                scopeMap.remove(key)
                null
            }
        }
    }

    private fun <K : Any> acquireScopeCreationLock(
        locks: ConcurrentHashMap<K, ScopeCreationLock>,
        key: K,
    ): ScopeCreationLock {
        return synchronized(locks) {
            locks.getOrPut(key) { ScopeCreationLock() }
                .also { it.users++ }
        }
    }

    private fun <K : Any> releaseScopeCreationLock(
        locks: ConcurrentHashMap<K, ScopeCreationLock>,
        key: K,
        creationLock: ScopeCreationLock,
    ) {
        synchronized(locks) {
            creationLock.users--
            if (creationLock.users == 0 && locks[key] === creationLock) {
                locks.remove(key)
            }
        }
    }

    private fun getCachedCryptoScope(key: CryptoScopeKey): ScriptBindings? {
        return synchronized(cryptoScopeLock) {
            val reference = cryptoScopeMap[key] ?: return@synchronized null
            reference.get() ?: run {
                cryptoScopeMap.remove(key)
                null
            }
        }
    }

    private fun getCompiledCryptoJs(): CompiledScript? {
        cryptoJsScript?.let { return it }
        val cryptoJs = loadCryptoJs() ?: return null
        synchronized(cryptoCompileLock) {
            cryptoJsScript?.let { return it }
            return RhinoScriptEngine.compile(cryptoJs).also { cryptoJsScript = it }
        }
    }

    private fun createScope(): ScriptBindings {
        return RhinoScriptEngine.getRuntimeScope(ScriptBindings())
    }

    private fun evaluateCryptoJs(
        cryptoJs: CompiledScript,
        scope: ScriptBindings,
        coroutineContext: CoroutineContext?,
    ) {
        cryptoJs.eval(scope, coroutineContext)
        val secureRandomFunction = object : BaseFunction() {
            override fun call(
                cx: Context,
                callScope: VarScope,
                thisObj: Scriptable,
                args: Array<Any>,
            ): Any = secureRandom.nextInt().toDouble()
        }.apply {
            parentScope = scope
            prototype = ScriptableObject.getFunctionPrototype(scope)
        }
        ScriptableObject.putProperty(scope, SECURE_RANDOM_BINDING, secureRandomFunction)
        try {
            RhinoScriptEngine.eval(SECURE_RANDOM_PATCH, scope, coroutineContext)
        } finally {
            scope.delete(SECURE_RANDOM_BINDING)
        }
    }

    private fun evaluateJsLib(
        jsLib: String,
        scope: ScriptBindings,
        coroutineContext: CoroutineContext?,
    ) {
        if (jsLib.isJsonObject()) {
            val jsMap: Map<String, String> = GSON.fromJson(
                jsLib,
                TypeToken.getParameterized(
                    Map::class.java,
                    String::class.java,
                    String::class.java,
                ).type,
            )
            jsMap.values.forEach { value ->
                if (value.isAbsUrl()) {
                    val fileName = MD5Utils.md5Encode(value)
                    var js = aCache.getAsString(fileName)
                    if (js == null) {
                        js = runBlocking {
                            okHttpClient.newCallStrResponse {
                                url(value)
                            }.body
                        }
                        if (js != null) {
                            aCache.put(fileName, js)
                        } else {
                            throw NoStackTraceException("下载jsLib-${value}失败")
                        }
                    }
                    RhinoScriptEngine.eval(js, scope, coroutineContext)
                }
            }
        } else {
            RhinoScriptEngine.eval(jsLib, scope, coroutineContext)
        }
    }

    private fun preventExtensions(scope: ScriptBindings) {
        scope.globalThis.preventExtensions()
    }

    private class CryptoScopeKey(
        private val owner: Any,
        private val thread: Thread,
    ) {
        private val identityHash =
            31 * System.identityHashCode(owner) + System.identityHashCode(thread)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            return other is CryptoScopeKey &&
                identityHash == other.identityHash &&
                owner === other.owner &&
                thread === other.thread
        }
    }

    private class ScopeCreationLock {
        val monitor = Any()
        var users = 0
    }

    private const val SECURE_RANDOM_PATCH = """
        CryptoJS.lib.WordArray.random = (function(nextInt) {
            return function(nBytes) {
                var words = [];
                for (var i = 0; i < nBytes; i += 4) {
                    words.push(nextInt());
                }
                return CryptoJS.lib.WordArray.create(words, nBytes);
            };
        })(__legadoSecureRandomInt);
    """
}
