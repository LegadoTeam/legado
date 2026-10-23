@file:Keep
@file:Suppress("DEPRECATION")

package io.legado.app.lib.cronet

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.DebugLog
import io.legado.app.utils.externalCache
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.Request
import org.chromium.net.CronetEngine.Builder.HTTP_CACHE_DISABLED
import org.chromium.net.CronetEngine.Builder.HTTP_CACHE_DISK
import org.chromium.net.CronetException
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.UploadDataProvider
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.json.JSONObject
import splitties.init.appCtx
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

internal const val BUFFER_SIZE = 32 * 1024
private const val CRONET_CACHE_BYTES = 50L * 1024 * 1024
private const val MAX_RESOLVER_ENGINES = 6

@Volatile
private var cronetEngineFailure: Throwable? = null

private data class ResolverEngineEntry(
    val engine: ExperimentalCronetEngine,
    var leases: Int = 0,
)

private val resolverEngineLock = Any()
private val resolverEngineCache = LinkedHashMap<String, ResolverEngineEntry>(8, 0.75f, true)

private class CronetEngineLease(
    val engine: ExperimentalCronetEngine,
    private val resolverRules: String? = null,
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (resolverRules != null && released.compareAndSet(false, true)) {
            releaseResolverEngine(resolverRules)
        }
    }
}

val cronetEngine: ExperimentalCronetEngine? by lazy {
    try {
        CronetLoader.preDownload()
        createCronetEngine(buildExperimentalOptions(), useDiskCache = true)
    } catch (e: Throwable) {
        cronetEngineFailure = e
        AppLog.put("初始化cronetEngine出错", e)
        null
    }
}

internal fun isCronetAvailable(): Boolean {
    return try {
        CronetLoader.installWithRetry()
    } catch (e: Throwable) {
        cronetEngineFailure = e
        AppLog.put("初始化cronetEngine出错", e)
        false
    }
}

internal fun getCronetEngineOrNull(): ExperimentalCronetEngine? {
    // Cronet remains the preferred transport when it initializes successfully.
    // This guard only protects users carrying a broken pre-fix build from a crash.
    return if (isCronetAvailable()) cronetEngine else null
}

internal fun cronetUnavailableException(message: String): CronetUnavailableException =
    CronetUnavailableException(message, cronetEngineFailure)

internal fun buildExperimentalOptions(hostResolverRules: String? = null): String {
    val options = JSONObject()

    hostResolverRules?.takeIf { it.isNotBlank() }?.let { rules ->
        options.put(
            "HostResolverRules",
            JSONObject().put("host_resolver_rules", rules),
        )
    }

    // 启用DnsHttpsSvcb更容易迁移到http3
    val dnsSvcb = JSONObject()
    dnsSvcb.put("enable", true)
    dnsSvcb.put("enable_insecure", true)
    dnsSvcb.put("use_alpn", true)
    options.put("UseDnsHttpsSvcb", dnsSvcb)

    options.put("AsyncDNS", JSONObject().put("enable", true))
    return options.toString()
}

private fun createCronetEngine(
    experimentalOptions: String,
    useDiskCache: Boolean,
): ExperimentalCronetEngine {
    val builder = ExperimentalCronetEngine.Builder(appCtx).apply {
        // Cronet 152 loads through the version-pinned Java adaptation in cronet-loader.gradle.
        if (useDiskCache) {
            setStoragePath(appCtx.externalCache.absolutePath)
            enableHttpCache(HTTP_CACHE_DISK, CRONET_CACHE_BYTES)
        } else {
            // Resolver-specific engines must not share the default disk-cache storage path.
            enableHttpCache(HTTP_CACHE_DISABLED, 0)
        }
        enableQuic(true)
        enableHttp2(true)
        enablePublicKeyPinningBypassForLocalTrustAnchors(true)
        enableBrotli(true)
        setExperimentalOptions(experimentalOptions)
    }
    return builder.build().also {
        DebugLog.d("Cronet Version:", it.versionString)
    }
}

private fun acquireCronetEngine(request: Request): CronetEngineLease? {
    val resolverRules = buildHostResolverRules(
        request.url.host,
        AppConfig.addressCache,
        request.tag(CronetRequestConfig::class.java),
    )
    if (resolverRules == null) {
        return getCronetEngineOrNull()?.let { CronetEngineLease(it) }
    }

    return try {
        if (!isCronetAvailable()) return null
        synchronized(resolverEngineLock) {
            resolverEngineCache[resolverRules]?.let { entry ->
                entry.leases += 1
                return@synchronized CronetEngineLease(entry.engine, resolverRules)
            }

            val engine = createCronetEngine(
                buildExperimentalOptions(resolverRules),
                useDiskCache = false,
            )
            resolverEngineCache[resolverRules] = ResolverEngineEntry(engine, leases = 1)
            trimResolverEngineCacheLocked()
            CronetEngineLease(engine, resolverRules)
        }
    } catch (e: Throwable) {
        cronetEngineFailure = e
        AppLog.put("初始化带域名解析规则的cronetEngine出错", e)
        null
    }
}

private fun releaseResolverEngine(resolverRules: String) {
    synchronized(resolverEngineLock) {
        resolverEngineCache[resolverRules]?.let { entry ->
            entry.leases = (entry.leases - 1).coerceAtLeast(0)
        }
        trimResolverEngineCacheLocked()
    }
}

private fun trimResolverEngineCacheLocked() {
    while (resolverEngineCache.size > MAX_RESOLVER_ENGINES) {
        val candidate = resolverEngineCache.entries.firstOrNull { (_, entry) ->
            entry.leases == 0
        } ?: return

        resolverEngineCache.remove(candidate.key)
        val engine = candidate.value.engine
        okHttpClient.dispatcher.executorService.execute {
            runCatching { engine.shutdown() }
                .onFailure { AppLog.put("关闭旧Cronet解析引擎出错", it) }
        }
    }
}

fun buildRequest(request: Request, callback: UrlRequest.Callback): UrlRequest? {
    val headers: Headers = request.headers
    val requestBody = request.body
    val engineLease = acquireCronetEngine(request) ?: return null
    val releasingCallback = ReleasingCronetCallback(callback, engineLease)

    return try {
        engineLease.engine.newUrlRequestBuilder(
            request.url.toString(),
            releasingCallback,
            okHttpClient.dispatcher.executorService
        ).apply {
            setHttpMethod(request.method)
            allowDirectExecutor()
            headers.forEachIndexed { index, _ ->
                if (headers.name(index) == cookieJarHeader) return@forEachIndexed
                addHeader(headers.name(index), headers.value(index))
            }
            if (requestBody != null) {
                val contentType: MediaType? = requestBody.contentType()
                if (contentType != null) {
                    addHeader("Content-Type", contentType.toString())
                } else {
                    addHeader("Content-Type", "text/plain")
                }
                val provider: UploadDataProvider = if (requestBody.contentLength() > BUFFER_SIZE) {
                    LargeBodyUploadProvider(requestBody, okHttpClient.dispatcher.executorService)
                } else {
                    BodyUploadProvider(requestBody)
                }
                provider.use {
                    setUploadDataProvider(it, okHttpClient.dispatcher.executorService)
                }
            }
        }.build()
    } catch (e: Throwable) {
        engineLease.release()
        throw e
    }
}

private class ReleasingCronetCallback(
    private val delegate: UrlRequest.Callback,
    private val engineLease: CronetEngineLease,
) : UrlRequest.Callback() {

    override fun onRedirectReceived(
        request: UrlRequest,
        info: UrlResponseInfo,
        newLocationUrl: String,
    ) {
        delegate.onRedirectReceived(request, info, newLocationUrl)
    }

    override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
        delegate.onResponseStarted(request, info)
    }

    override fun onReadCompleted(
        request: UrlRequest,
        info: UrlResponseInfo,
        byteBuffer: ByteBuffer,
    ) {
        delegate.onReadCompleted(request, info, byteBuffer)
    }

    override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
        try {
            delegate.onSucceeded(request, info)
        } finally {
            engineLease.release()
        }
    }

    override fun onFailed(
        request: UrlRequest,
        info: UrlResponseInfo?,
        error: CronetException,
    ) {
        try {
            delegate.onFailed(request, info, error)
        } finally {
            engineLease.release()
        }
    }

    override fun onCanceled(request: UrlRequest?, info: UrlResponseInfo?) {
        try {
            delegate.onCanceled(request, info)
        } finally {
            engineLease.release()
        }
    }
}
