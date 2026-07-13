package io.legado.app.help.webView

import io.legado.app.constant.AppConst
import io.legado.app.help.http.CookieManager.cookieJarHeader

internal data class WebViewRequestConfig(
    val userAgent: String,
    val additionalHeaders: Map<String, String>
)

/**
 * User-Agent belongs to WebSettings so redirects and subresources use it consistently.
 * CookieJar is an internal OkHttp switch and must never be sent to a website.
 */
internal fun Map<String, String>?.toWebViewRequestConfig(
    defaultUserAgent: String
): WebViewRequestConfig {
    val exactUserAgent = this?.entries?.firstOrNull {
        it.key == AppConst.UA_NAME && it.value.isNotBlank()
    }?.value
    val userAgent = exactUserAgent ?: this?.entries?.firstOrNull {
        it.key.equals(AppConst.UA_NAME, ignoreCase = true) && it.value.isNotBlank()
    }?.value ?: defaultUserAgent

    val additionalHeaders = LinkedHashMap<String, String>()
    this?.forEach { (name, value) ->
        if (!name.equals(AppConst.UA_NAME, ignoreCase = true)
            && !name.equals(cookieJarHeader, ignoreCase = true)
        ) {
            additionalHeaders[name] = value
        }
    }
    return WebViewRequestConfig(userAgent, additionalHeaders)
}
