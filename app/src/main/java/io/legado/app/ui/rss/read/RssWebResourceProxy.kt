package io.legado.app.ui.rss.read

import okhttp3.Headers
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.FilterInputStream

internal object RssWebResourceProxy {

    private val mediaDestinations = setOf("image", "video", "audio")
    private val mediaExtensions = setOf(
        ".aac", ".avi", ".avif", ".bmp", ".flac", ".gif", ".heic", ".heif", ".ico",
        ".jpeg", ".jpg", ".m4a", ".m4v", ".m3u8", ".mov", ".mp3", ".mp4", ".oga",
        ".ogg", ".opus", ".png", ".svg", ".ts", ".wav", ".webm", ".webp"
    )
    private val skippedRequestHeaders = setOf(
        "accept-encoding",
        "connection",
        "content-length",
        "cookie",
        "cookiejar",
        "host",
        "proxy",
        "transfer-encoding"
    )
    private val skippedResponseHeaders = setOf(
        "connection",
        "set-cookie",
        "transfer-encoding",
        "upgrade"
    )

    fun shouldProxy(
        url: String,
        method: String,
        isForMainFrame: Boolean,
        requestHeaders: Map<String, String>?,
        preloadUrl: String?
    ): Boolean {
        if (isForMainFrame || (!method.equals("GET", ignoreCase = true)
                    && !method.equals("HEAD", ignoreCase = true))
        ) return false
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") return false
        if (url == preloadUrl) return false

        val headers = requestHeaders.orEmpty()
        val destination = header(headers, "Sec-Fetch-Dest")?.trim()?.lowercase()
        if (destination in mediaDestinations) return true
        if (!header(headers, "Range").isNullOrBlank()) return true

        val accept = header(headers, "Accept")
            ?.split(',')
            ?.map { it.substringBefore(';').trim().lowercase() }
            .orEmpty()
        if (accept.any { it == "image/*" || it == "video/*" || it == "audio/*"
                || it.startsWith("image/") || it.startsWith("video/") || it.startsWith("audio/") }) {
            return true
        }

        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return mediaExtensions.any(path::endsWith)
    }

    fun requestHeaders(
        sourceHeaders: Map<String, String>,
        webViewHeaders: Map<String, String>?,
        cookie: String?
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        addHeaders(result, sourceHeaders)
        addHeaders(result, webViewHeaders.orEmpty())
        if (!cookie.isNullOrBlank()) result["Cookie"] = cookie
        return result
    }

    fun responseHeaders(headers: Headers): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        headers.names().forEach { name ->
            if (name.lowercase() !in skippedResponseHeaders) {
                result[name] = headers.values(name).joinToString(", ")
            }
        }
        return result
    }

    fun mimeType(contentType: String?): String? = contentType
        ?.substringBefore(';')
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    fun encoding(contentType: String?): String? = contentType
        ?.split(';')
        ?.asSequence()
        ?.drop(1)
        ?.map(String::trim)
        ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim(' ', '"', '\'')
        ?.takeIf(String::isNotEmpty)

    fun supportsStatus(code: Int): Boolean = code in 100..299 || code in 400..599

    fun reasonPhrase(message: String): String {
        val ascii = message.filter { it.code in 0x20..0x7e }.trim()
        return ascii.ifEmpty { "OK" }
    }

    private fun addHeaders(target: MutableMap<String, String>, headers: Map<String, String>) {
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()
                && name.lowercase() !in skippedRequestHeaders
            ) {
                target.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(target::remove)
                target[name] = value
            }
        }
    }

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

internal class RssProxyResponseInputStream(
    private val response: Response,
    body: ResponseBody,
) : FilterInputStream(body.byteStream()) {

    private var closed = false

    override fun read(): Int = try {
        super.read().also { if (it < 0) close() }
    } catch (error: Throwable) {
        close()
        throw error
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        super.read(buffer, offset, length).also { if (it < 0) close() }
    } catch (error: Throwable) {
        close()
        throw error
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            super.close()
        } finally {
            response.close()
        }
    }
}
