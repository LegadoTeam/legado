package io.legado.app.lib.cronet

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.Keep
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.utils.printOnDebug
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

@Keep
@Suppress("unused")
class CronetInterceptor(private val cookieJar: CookieJar) : Interceptor {

    @Throws(IOException::class)
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
        val cronetException: Throwable
        try {
            val builder: Request.Builder = original.newBuilder()
            //移除Keep-Alive,手动设置会导致400 BadRequest
            builder.removeHeader("Keep-Alive")
            builder.removeHeader("Accept-Encoding")

            // https://github.com/gedoor/legado/issues/5025#issuecomment-2851156500
            if (!original.isHttps &&
                original.header("User-Agent")?.startsWith("Mozilla", true) == true
            ) {
                val referer = original.header("Referer")
                if (referer != null && referer.startsWith("https:", true)) {
                    builder.header("Referer", "http" + referer.substring(5))
                }
            }

            var newReq = builder.build()

            if (newReq.header(cookieJarHeader) != null) {
                newReq = CookieManager.loadRequest(newReq)
            }

            return proceedWithCronet(newReq, chain.call(), chain.readTimeoutMillis())
                ?: throw cronetUnavailableException("Cronet request could not be built")
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            cronetException = e
            if (!e.message.toString().contains("ERR_CERT_", true)
                && !e.message.toString().contains("ERR_SSL_", true)
            ) {
                e.printOnDebug()
            }
            throw CronetUnavailableException("Cronet request failed", cronetException)
        }
    }

    @SuppressLint("ObsoleteSdkInt")
    @Throws(IOException::class)
    private fun proceedWithCronet(request: Request, call: Call, readTimeoutMillis: Int): Response? {
        val callBack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NewCallBack(request, call, readTimeoutMillis)
        } else {
            OldCallback(request, call, readTimeoutMillis)
        }
        buildRequest(request, callBack)?.let {
            return callBack.waitForDone(it)
        }
        return null
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
