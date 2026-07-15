package io.legado.app.help.http

import java.net.IDN
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal enum class ProxyProtocol(val proxyType: Proxy.Type) {
    HTTP(Proxy.Type.HTTP),
    SOCKS4(Proxy.Type.SOCKS),
    SOCKS5(Proxy.Type.SOCKS),
}

internal data class ProxyCredentials(
    val username: String,
    val password: String,
) {
    override fun toString(): String = "ProxyCredentials(***)"
}

internal data class ProxyConfig(
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int,
    val credentials: ProxyCredentials? = null,
)

private val legacyProxyPattern = Regex(
    pattern = "^(http|socks4|socks5)://(.+):(\\d+)@([^@\\s]+)@(.+)$",
    option = RegexOption.IGNORE_CASE,
)

internal fun parseProxyConfig(rawProxy: String): ProxyConfig {
    val proxy = rawProxy.trim()
    if (proxy.isEmpty()) invalidProxy()

    legacyProxyPattern.matchEntire(proxy)?.let { match ->
        val protocol = parseProxyProtocol(match.groupValues[1])
        if (protocol != ProxyProtocol.HTTP) unsupportedSocksAuthentication()
        val credentials = ProxyCredentials(
            username = match.groupValues[4],
            password = match.groupValues[5],
        ).validated()
        return ProxyConfig(
            protocol = protocol,
            host = normalizeProxyHost(match.groupValues[2]),
            port = parseProxyPort(match.groupValues[3]),
            credentials = credentials,
        )
    }

    val uri = try {
        URI(proxy)
    } catch (_: URISyntaxException) {
        invalidProxy()
    }
    if (uri.isOpaque || uri.rawQuery != null || uri.rawFragment != null) invalidProxy()
    if (!uri.path.isNullOrEmpty() && uri.path != "/") invalidProxy()

    val protocol = parseProxyProtocol(uri.scheme)
    val authority = uri.rawAuthority ?: invalidProxy()
    val userInfoSeparator = authority.lastIndexOf('@')
    if (userInfoSeparator >= 0 && authority.indexOf('@') != userInfoSeparator) invalidProxy()
    val rawUserInfo = authority.takeIf { userInfoSeparator >= 0 }
        ?.substring(0, userInfoSeparator)
    val hostAndPort = authority.substring(userInfoSeparator + 1)
    val credentials = rawUserInfo?.let {
        if (protocol != ProxyProtocol.HTTP) unsupportedSocksAuthentication()
        val separator = it.indexOf(':')
        if (separator <= 0) invalidProxy()
        ProxyCredentials(
            username = decodeUserInfo(it.substring(0, separator)),
            password = decodeUserInfo(it.substring(separator + 1)),
        ).validated()
    }
    val (host, port) = parseProxyAddress(hostAndPort)
    return ProxyConfig(
        protocol = protocol,
        host = host,
        port = port,
        credentials = credentials,
    )
}

internal fun shouldRetryProxyAuthentication(
    responseCode: Int,
    hasAuthorizationHeader: Boolean,
    consecutiveChallenges: Int,
): Boolean = responseCode == 407 && !hasAuthorizationHeader && consecutiveChallenges == 1

private fun parseProxyProtocol(scheme: String?): ProxyProtocol {
    return when (scheme?.lowercase()) {
        "http" -> ProxyProtocol.HTTP
        "socks4" -> ProxyProtocol.SOCKS4
        "socks5" -> ProxyProtocol.SOCKS5
        else -> invalidProxy()
    }
}

private fun parseProxyAddress(value: String): Pair<String, Int> {
    if (value.isBlank()) invalidProxy()
    val host: String
    val port: String
    if (value.startsWith('[')) {
        val closingBracket = value.indexOf(']')
        if (closingBracket <= 1 || value.getOrNull(closingBracket + 1) != ':') invalidProxy()
        host = value.substring(0, closingBracket + 1)
        port = value.substring(closingBracket + 2)
    } else {
        val separator = value.lastIndexOf(':')
        if (separator <= 0) invalidProxy()
        host = value.substring(0, separator)
        port = value.substring(separator + 1)
    }
    return normalizeProxyHost(host) to parseProxyPort(port)
}

private fun normalizeProxyHost(value: String): String {
    val bracketed = value.startsWith('[') || value.endsWith(']')
    if (bracketed && !(value.startsWith('[') && value.endsWith(']'))) invalidProxy()
    val host = value.removeSurrounding("[", "]")
    if (
        host.isNullOrBlank() ||
        host.any { it.isWhitespace() || it.isISOControl() || it in "/@?#[]" } ||
        '%' in host
    ) {
        invalidProxy()
    }
    if (':' in host) {
        if (runCatching { InetAddress.getByName(host) }.getOrNull() == null) invalidProxy()
        return host
    }
    return try {
        IDN.toASCII(host).takeIf(String::isNotBlank) ?: invalidProxy()
    } catch (_: IllegalArgumentException) {
        invalidProxy()
    }
}

private fun parseProxyPort(value: String): Int =
    value.toIntOrNull()?.let(::parseProxyPort) ?: invalidProxyPort()

private fun parseProxyPort(value: Int): Int =
    value.takeIf { it in 1..65535 } ?: invalidProxyPort()

private fun ProxyCredentials.validated(): ProxyCredentials {
    if (username.isBlank() || username.any(Char::isISOControl) || password.any(Char::isISOControl)) {
        invalidProxy()
    }
    return this
}

private fun decodeUserInfo(value: String): String {
    return try {
        URLDecoder.decode(
            value.replace("+", "%2B"),
            StandardCharsets.UTF_8.name(),
        )
    } catch (_: IllegalArgumentException) {
        invalidProxy()
    }
}

private fun invalidProxy(): Nothing =
    throw IllegalArgumentException("Proxy must include a supported scheme, host, and port")

private fun invalidProxyPort(): Nothing =
    throw IllegalArgumentException("Proxy port must be between 1 and 65535")

private fun unsupportedSocksAuthentication(): Nothing =
    throw IllegalArgumentException("SOCKS proxy authentication is not supported")
