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
    private var initialRangeOffset = response.initialRangeOffset()

    // WebView seeks the request Range before inspecting our 206 response headers. Cronet's
    // body already starts there, so acknowledge that initial seek without discarding it twice.
    override fun skip(byteCount: Long): Long {
        if (byteCount <= 0) return 0
        val alreadySkipped = minOf(byteCount, initialRangeOffset)
        initialRangeOffset -= alreadySkipped
        return alreadySkipped + if (byteCount > alreadySkipped) {
            super.skip(byteCount - alreadySkipped)
        } else 0
    }

    // available() describes buffered bytes, not the full resource size. WebView otherwise
    // uses it to reject an absolute Range offset that exceeds the remaining partial body.
    override fun available(): Int = if (response.code == 206) 0 else super.available()

    // WebView may query available() and read() again after EOF; it owns close().
    override fun read(): Int = try {
        initialRangeOffset = 0
        super.read()
    } catch (error: Throwable) {
        close()
        throw error
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        if (length > 0) initialRangeOffset = 0
        super.read(buffer, offset, length)
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

    private fun Response.initialRangeOffset(): Long {
        if (code != 206) return 0
        val requested = REQUEST_RANGE.matchEntire(request.header("Range")?.trim().orEmpty())
            ?: return 0
        val returned = CONTENT_RANGE.matchEntire(header("Content-Range")?.trim().orEmpty())
            ?: return 0
        val start = returned.groupValues[1].toLongOrNull() ?: return 0
        val end = returned.groupValues[2].toLongOrNull() ?: return 0
        if (start > end || requested.groupValues[1].toLongOrNull() != start) return 0
        val total = returned.groupValues[3]
        if (total != "*" && (total.toLongOrNull()?.let { it > end } != true)) return 0
        val requestedEnd = requested.groupValues[2]
        if (requestedEnd.isNotEmpty()
            && (requestedEnd.toLongOrNull()?.let { it >= end } != true)) return 0
        return start
    }

    private companion object {
        val REQUEST_RANGE = Regex("bytes=(\\d+)-(\\d*)", RegexOption.IGNORE_CASE)
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
    }
}
