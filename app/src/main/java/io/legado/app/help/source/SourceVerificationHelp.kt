package io.legado.app.help.source

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.association.VerificationCodeActivity
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.utils.isMainThread
import io.legado.app.utils.startActivity
import kotlinx.coroutines.isActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import splitties.init.appCtx
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class VerificationFlightRegistry {

    internal class Lease internal constructor(
        internal val flight: Flight,
        internal val owner: Boolean
    )

    internal class Flight {
        val done = CountDownLatch(1)
        @Volatile
        var error: Throwable? = null
        @Volatile
        var abandoned = false
        var users = 0
    }

    private val flights = ConcurrentHashMap<String, Flight>()

    fun acquire(key: String): Lease {
        var owner = false
        val flight = flights.compute(key) { _, current ->
            if (current == null || current.done.count == 0L) {
                owner = true
                Flight().also { it.users = 1 }
            } else {
                current.apply { users++ }
            }
        }!!
        return Lease(flight, owner)
    }

    fun await(
        lease: Lease,
        waitNanos: Long,
        isActive: () -> Boolean = { true },
    ): VerificationFlightWaitResult {
        val deadline = System.nanoTime() + waitNanos
        while (lease.flight.done.count != 0L) {
            if (!isActive()) throw CancellationException("source verification cancelled")
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) {
                throw NoStackTraceException("source verification timed out")
            }
            lease.flight.done.await(minOf(remaining, SECONDS.toNanos(1)), NANOSECONDS)
        }
        lease.flight.error?.let { throw it }
        return if (lease.flight.abandoned) {
            VerificationFlightWaitResult.Retry
        } else {
            VerificationFlightWaitResult.Completed
        }
    }

    fun complete(lease: Lease) {
        lease.flight.done.countDown()
    }

    fun fail(lease: Lease, error: Throwable) {
        lease.flight.error = error
        lease.flight.done.countDown()
    }

    fun abandon(lease: Lease) {
        lease.flight.abandoned = true
        lease.flight.done.countDown()
    }

    fun release(key: String, lease: Lease): Boolean {
        var removed = false
        flights.computeIfPresent(key) { _, current ->
            if (current !== lease.flight) {
                current
            } else {
                current.users--
                if (current.users == 0) {
                    removed = true
                    null
                } else {
                    current
                }
            }
        }
        return removed
    }
}

internal enum class VerificationFlightWaitResult {
    Completed,
    Retry,
}

internal sealed class VerificationResult {
    data class Response(val value: Pair<String, String>) : VerificationResult()
    object Refetch : VerificationResult()
}

internal fun canJoinVerificationFlight(
    useBrowser: Boolean,
    refetchAfterSuccess: Boolean,
    html: String?
) = useBrowser && refetchAfterSuccess && html == null

internal fun verificationFlightKey(
    sourceKey: String,
    sourceType: Int,
    url: String,
): String? {
    val matcher = AnalyzeUrl.paramPattern.matcher(url)
    val requestUrl = if (matcher.find()) url.substring(0, matcher.start()) else url
    val httpUrl = requestUrl.trim().toHttpUrlOrNull() ?: return null
    return listOf(
        sourceType.toString(),
        sourceKey,
        httpUrl.scheme,
        httpUrl.host,
        httpUrl.port.toString(),
    ).joinToString("\u0000")
}

/**
 * 源验证
 */
object SourceVerificationHelp {

    private class VerificationAttempt(val waiter: Thread) {
        val result = AtomicReference<Pair<String, String>?>(null)
        var closeUi: (() -> Unit)? = null
    }

    private val flightWaitTime = 5.minutes.inWholeNanoseconds
    private val waitPollTime = 1.seconds.inWholeNanoseconds
    private val verificationFlights = VerificationFlightRegistry()
    private val verificationAttempts = ConcurrentHashMap<String, VerificationAttempt>()
    private val verificationUiLock = ReentrantLock()

    internal fun registerVerificationAttempt(waiter: Thread): String {
        val key = UUID.randomUUID().toString()
        verificationAttempts[key] = VerificationAttempt(waiter)
        return key
    }

    internal fun attachVerificationUi(
        verificationResultKey: String?,
        closeUi: () -> Unit,
    ): Boolean {
        verificationResultKey ?: return true
        val attempt = verificationAttempts[verificationResultKey] ?: return false
        synchronized(attempt) {
            if (verificationAttempts[verificationResultKey] !== attempt) return false
            attempt.closeUi = closeUi
            return true
        }
    }

    internal fun cancelVerificationAttempt(verificationResultKey: String) {
        val attempt = verificationAttempts.remove(verificationResultKey) ?: return
        val closeUi = synchronized(attempt) {
            attempt.closeUi.also { attempt.closeUi = null }
        }
        closeUi?.invoke()
    }

    private fun lockVerificationUi(coroutineContext: CoroutineContext?) {
        while (true) {
            if (coroutineContext?.isActive == false) {
                throw CancellationException("source verification cancelled")
            }
            if (verificationUiLock.tryLock(1, SECONDS)) return
        }
    }

    /**
     * 获取书源验证结果
     * 图片验证码 防爬 滑动验证码 点击字符 等等
     */
    internal fun getVerificationResult(
        source: BaseSource?,
        url: String,
        title: String,
        useBrowser: Boolean,
        refetchAfterSuccess: Boolean = true,
        html: String? = null,
        coroutineContext: CoroutineContext? = null,
    ): VerificationResult {
        source
            ?: throw NoStackTraceException("getVerificationResult parameter source cannot be null")
        require(url.length < 64 * 1024) { "getVerificationResult parameter url too long" }
        check(!isMainThread) { "getVerificationResult must be called on a background thread" }

        val flightKey = if (canJoinVerificationFlight(useBrowser, refetchAfterSuccess, html)) {
            verificationFlightKey(source.getKey(), source.getSourceType(), url)
        } else {
            null
        }
        if (flightKey == null) {
            lockVerificationUi(coroutineContext)
            return try {
                VerificationResult.Response(
                    waitForVerification(
                        source,
                        url,
                        title,
                        useBrowser,
                        refetchAfterSuccess,
                        html,
                        coroutineContext,
                    )
                )
            } finally {
                verificationUiLock.unlock()
            }
        }

        while (true) {
            val lease = verificationFlights.acquire(flightKey)
            if (!lease.owner) {
                val waitResult = try {
                    verificationFlights.await(lease, flightWaitTime) {
                        coroutineContext?.isActive != false
                    }
                } finally {
                    verificationFlights.release(flightKey, lease)
                }
                if (waitResult == VerificationFlightWaitResult.Retry) continue
                return VerificationResult.Refetch
            }

            try {
                lockVerificationUi(coroutineContext)
                return try {
                    val result = waitForVerification(
                        source,
                        url,
                        title,
                        useBrowser,
                        refetchAfterSuccess,
                        html,
                        coroutineContext,
                    )
                    verificationFlights.complete(lease)
                    VerificationResult.Response(result)
                } finally {
                    verificationUiLock.unlock()
                }
            } catch (error: Throwable) {
                if (error is CancellationException || error is InterruptedException) {
                    verificationFlights.abandon(lease)
                } else {
                    verificationFlights.fail(lease, error)
                }
                throw error
            } finally {
                verificationFlights.release(flightKey, lease)
            }
        }
    }

    private fun waitForVerification(
        source: BaseSource,
        url: String,
        title: String,
        useBrowser: Boolean,
        refetchAfterSuccess: Boolean,
        html: String?,
        coroutineContext: CoroutineContext?,
    ): Pair<String, String> {
        val sourceKey = source.getKey()
        val verificationResultKey = registerVerificationAttempt(Thread.currentThread())
        try {
            if (!useBrowser) {
                appCtx.startActivity<VerificationCodeActivity> {
                    putExtra("imageUrl", url)
                    putExtra("sourceOrigin", sourceKey)
                    putExtra("sourceName", source.getTag())
                    putExtra("sourceType", source.getSourceType())
                    putExtra("verificationResultKey", verificationResultKey)
                }
            } else {
                startBrowser(
                    source,
                    url,
                    title,
                    true,
                    refetchAfterSuccess,
                    html,
                    verificationResultKey,
                )
            }

            var waitUserInput = false
            while (getResult(verificationResultKey) == null) {
                if (coroutineContext?.isActive == false) {
                    throw CancellationException("source verification cancelled")
                }
                if (Thread.interrupted()) {
                    throw InterruptedException("source verification interrupted")
                }
                if (!waitUserInput && html == null) {
                    AppLog.putDebug("等待返回验证结果...")
                    waitUserInput = true
                }
                LockSupport.parkNanos(this, waitPollTime)
            }
            val result = getResult(verificationResultKey)
                ?: throw NoStackTraceException("验证结果为空")
            if (result.second.isEmpty()) throw NoStackTraceException("验证结果为空")
            return result
        } catch (error: Throwable) {
            if (error is CancellationException || error is InterruptedException) {
                cancelVerificationAttempt(verificationResultKey)
            }
            throw error
        } finally {
            clearResult(verificationResultKey)
        }
    }

    /**
     * 启动内置浏览器
     * @param saveResult 保存网页源代码到数据库
     */
    fun startBrowser(
        source: BaseSource?,
        url: String,
        title: String,
        saveResult: Boolean? = false,
        refetchAfterSuccess: Boolean? = true,
        html: String? = null,
        verificationResultKey: String? = null,
    ) {
        source ?: throw NoStackTraceException("startBrowser parameter source cannot be null")
        require(url.length < 64 * 1024) { "startBrowser parameter url too long" }
        require(saveResult != true || verificationResultKey != null) {
            "verificationResultKey is required when saveResult is enabled"
        }
        appCtx.startActivity<WebViewActivity> {
            putExtra("title", title)
            putExtra("url", url)
            putExtra("sourceOrigin", source.getKey())
            putExtra("sourceName", source.getTag())
            putExtra("sourceType", source.getSourceType())
            putExtra("sourceVerificationEnable", saveResult)
            putExtra("refetchAfterSuccess", refetchAfterSuccess)
            putExtra("html", html)
            putExtra("verificationResultKey", verificationResultKey)
        }
    }


    fun checkResult(verificationResultKey: String?) {
        verificationResultKey ?: return
        val attempt = verificationAttempts[verificationResultKey] ?: return
        attempt.result.compareAndSet(null, "" to "")
        LockSupport.unpark(attempt.waiter)
    }

    fun setResult(verificationResultKey: String?, result: String, url: String = "") {
        verificationResultKey ?: return
        verificationAttempts[verificationResultKey]?.result?.compareAndSet(null, url to result)
    }

    fun getResult(verificationResultKey: String?): Pair<String, String>? {
        verificationResultKey ?: return null
        return verificationAttempts[verificationResultKey]?.result?.get()
    }

    fun clearResult(verificationResultKey: String?) {
        verificationResultKey ?: return
        val attempt = verificationAttempts.remove(verificationResultKey) ?: return
        synchronized(attempt) {
            attempt.closeUi = null
        }
    }
}
