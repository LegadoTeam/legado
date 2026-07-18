package io.legado.app.help.http

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object HttpLogSanitizer {

    private const val REDACTED = "[redacted]"
    private const val BINARY_BODY = "[binary body omitted]"
    private const val TRUNCATED = "[truncated]"
    private val sensitiveNames = setOf(
        "authorization",
        "proxyauthorization",
        "cookie",
        "setcookie",
        "password",
        "passwd",
        "token",
        "accesstoken",
        "refreshtoken",
        "secret",
        "apikey",
        "key",
        "signature",
        "sign",
    )
    private val jsonSecretPattern = Regex(
        "(?i)(\\\"(?:password|passwd|token|access[_-]?token|refresh[_-]?token|secret|authorization|cookie|api[_-]?key|sign|signature)\\\"\\s*:\\s*\\\")([^\\\"]*)(\\\")"
    )
    private val parameterSecretPattern = Regex(
        "(?i)((?:^|[?&\\s])(?:password|passwd|token|access[_-]?token|refresh[_-]?token|secret|authorization|cookie|api[_-]?key|sign|signature)=)([^&\\s]*)"
    )
    private val headerSecretPattern = Regex(
        "(?i)((?:^|[\\r\\n\\s])(?:authorization|proxy-authorization|cookie|set-cookie)\\s*:\\s*)([^\\r\\n]*)"
    )
    private val bearerPattern = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+={0,2}")
    private val basicPattern = Regex("(?i)(basic\\s+)[A-Za-z0-9+/]+={0,2}")

    fun redactUrl(url: HttpUrl): String {
        return redactHttpUrl(url).toString()
    }

    fun redactPath(url: HttpUrl): String {
        return redactHttpUrl(url).encodedPath
    }

    private fun redactHttpUrl(url: HttpUrl): HttpUrl {
        val builder = url.newBuilder()
        if (url.username.isNotEmpty()) builder.username(REDACTED)
        if (url.password.isNotEmpty()) builder.password(REDACTED)
        url.pathSegments.forEachIndexed { index, segment ->
            if (index > 0 && isSensitive(url.pathSegments[index - 1])) {
                builder.setPathSegment(index, REDACTED)
            } else if (isCredentialLike(segment)) {
                builder.setPathSegment(index, REDACTED)
            }
        }
        url.queryParameterNames.forEach { name ->
            if (isSensitive(name)) {
                builder.setQueryParameter(name, REDACTED)
            }
        }
        return builder.build()
    }

    fun formatHeaders(headers: Headers): String {
        return (0 until headers.size).joinToString("\n") { index ->
            val name = headers.name(index)
            val value = if (isSensitive(name)) REDACTED else headers.value(index)
            "$name: $value"
        }
    }

    fun body(bytes: ByteArray, mediaType: MediaType?, truncated: Boolean): String {
        if (bytes.isEmpty()) return ""
        if (!isText(mediaType) && !looksLikeText(bytes)) return BINARY_BODY
        val charset = mediaType?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
        val text = redactFreeText(bytes.toString(charset))
        return if (truncated) "$text\n$TRUNCATED" else text
    }

    fun redactUrlOrFreeText(text: String): String {
        return text.toHttpUrlOrNull()?.let(::redactUrl) ?: redactFreeText(text)
    }

    fun redactFreeText(text: String): String {
        return text
            .replace(headerSecretPattern) { match ->
                "${match.groupValues[1]}$REDACTED"
            }
            .replace(bearerPattern) { match ->
                "${match.groupValues[1]}$REDACTED"
            }
            .replace(basicPattern) { match ->
                "${match.groupValues[1]}$REDACTED"
            }
            .replace(jsonSecretPattern) { match ->
                "${match.groupValues[1]}$REDACTED${match.groupValues[3]}"
            }
            .replace(parameterSecretPattern) { match ->
                "${match.groupValues[1]}$REDACTED"
            }
    }

    private fun isSensitive(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        return normalized in sensitiveNames ||
            normalized.endsWith("authorization") ||
            normalized.endsWith("cookie") ||
            normalized.endsWith("password") ||
            normalized.endsWith("token") ||
            normalized.endsWith("secret") ||
            normalized.endsWith("apikey") ||
            normalized.endsWith("signature") ||
            normalized.endsWith("sign")
    }

    private fun isCredentialLike(value: String): Boolean {
        return value.count { it == '.' } == 2 && value.length >= 32
    }

    private fun isText(mediaType: MediaType?): Boolean {
        mediaType ?: return false
        val subtype = mediaType.subtype.lowercase(Locale.ROOT)
        return mediaType.type.equals("text", ignoreCase = true) ||
            subtype.contains("json") ||
            subtype.contains("xml") ||
            subtype.contains("html") ||
            subtype.contains("javascript") ||
            subtype.contains("x-www-form-urlencoded")
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        val text = bytes.toString(StandardCharsets.UTF_8)
        if (text.contains('\uFFFD')) return false
        return text.take(256).none { char ->
            char.isISOControl() && !char.isWhitespace()
        }
    }
}
