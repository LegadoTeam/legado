package io.legado.app.lib.cronet

import androidx.annotation.Keep
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.http.receiveHeaders
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Keep
@Suppress("unused")
class CronetCoroutineInterceptor(private val cookieJar: CookieJar) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.call().isCanceled()) {
            throw IOException("Canceled")
        }
        val original: Request = chain.request()
        if (!AppConfig.isCronet) return chain.proceed(original)
        // Cronet is the selected transport. Do not silently switch to OkHttp.
        if (getCronetEngineOrNull() == null) {
            throw cronetUnavailableException("Cronet engine is unavailable")
        }
        return try {
            val builder: Request.Builder = original.newBuilder()
            //移除Keep-Alive,手动设置会导致400 BadRequest
            builder.removeHeader("Keep-Alive")
            builder.removeHeader("Accept-Encoding")
            if (cookieJar != CookieJar.NO_COOKIES) {
                val cookieStr = getCookie(original.url)
                //设置Cookie
                if (cookieStr.length > 3) {
                    builder.addHeader("Cookie", cookieStr)
                }
            }

            val newReq = builder.build()
            val timeout = chain.call().timeout().timeoutNanos() / 1000000
            runBlocking() {
                if (timeout > 0) {
                    withTimeout(timeout) {
                        proceedWithCronet(newReq, chain.call(), chain.readTimeoutMillis()).also { response ->
                            cookieJar.receiveHeaders(newReq.url, response.headers)
                        }
                    }
                } else {
                    proceedWithCronet(newReq, chain.call(), chain.readTimeoutMillis()).also { response ->
                        cookieJar.receiveHeaders(newReq.url, response.headers)
                    }
                }
            }

        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            if (!e.message.toString().contains("ERR_CERT_", true)
                && !e.message.toString().contains("ERR_SSL_", true)
            ) {
                e.printOnDebug()
            }
            throw CronetUnavailableException("Cronet request failed", e)
        }

    }


    private suspend fun proceedWithCronet(
        request: Request,
        call: Call,
        readTimeoutMillis: Int
    ): Response =
        suspendCancellableCoroutine<Response> { coroutine ->

            val callBack = object : AbsCallBack(request, call, readTimeoutMillis) {
                override fun waitForDone(urlRequest: UrlRequest): Response {
                    TODO("Not yet implemented")
                }

                override fun onError(error: IOException) {
                    coroutine.resumeWithException(error)
                }

                override fun onSuccess(response: Response) {
                    coroutine.resume(response)
                }

                override fun onCanceled(request: UrlRequest?, info: UrlResponseInfo?) {
                    super.onCanceled(request, info)
                    coroutine.cancel()
                }


            }

            val req = buildRequest(request, callBack)
                ?: throw cronetUnavailableException("Cronet request could not be built")
            req.start()
            coroutine.invokeOnCancellation {
                req.cancel()
            }


        }


    /** Returns a 'Cookie' HTTP request header with all cookies, like `a=b; c=d`. */
    private fun getCookie(url: HttpUrl): String = buildString {
        val cookies = cookieJar.loadForRequest(url)
        cookies.forEachIndexed { index, cookie ->
            if (index > 0) append("; ")
            append(cookie.name).append('=').append(cookie.value)
        }
    }
}
