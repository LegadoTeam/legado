package io.legado.app.model.analyzeRule

import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit

private const val MIN_DERIVED_CALL_TIMEOUT_MILLIS = 60_000L
private const val MAX_OKHTTP_TIMEOUT_MILLIS = Int.MAX_VALUE.toLong()

internal fun parseRequestTimeoutMillis(value: Any?): Long? {
    val timeout = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is Float, is Double -> value.toDouble().takeIf {
            it.isFinite() && it % 1.0 == 0.0 && it <= Long.MAX_VALUE.toDouble()
        }?.toLong()
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
    return timeout?.takeIf { it in 1..MAX_OKHTTP_TIMEOUT_MILLIS }
}

internal fun parseBooleanOption(value: Any?): Boolean? {
    return when (value) {
        is Boolean -> value
        is Number -> when (value.toDouble()) {
            0.0 -> false
            1.0 -> true
            else -> null
        }
        is String -> when (value.trim().lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
        else -> null
    }
}

internal fun parseDnsIpAddresses(value: String): List<InetAddress> {
    val values = value.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
    if (values.isEmpty()) invalidDnsIp()
    return values.map { parseIpLiteral(it) ?: invalidDnsIp() }
}

internal fun buildScopedDns(
    targetHost: String,
    addresses: List<InetAddress>,
    fallback: Dns,
): Dns {
    require(targetHost.isNotBlank()) { "dnsIp requires a valid HTTP host" }
    require(addresses.isNotEmpty()) { "dnsIp requires at least one IP address" }
    return Dns { hostname ->
        if (hostname.equals(targetHost, ignoreCase = true)) {
            addresses
        } else {
            fallback.lookup(hostname)
        }
    }
}

internal fun validateDnsIpProxyCompatibility(proxy: String?, dnsIp: String?) {
    require(proxy.isNullOrBlank() || dnsIp.isNullOrBlank()) {
        "dnsIp cannot be used together with a proxy"
    }
}

internal fun shouldReturnRedirectBeforeWebView(
    followRedirects: Boolean?,
    responseCode: Int,
): Boolean = followRedirects == false && responseCode in 300..399

internal fun derivedCallTimeoutMillis(readTimeoutMillis: Long): Long {
    val doubled = if (readTimeoutMillis > MAX_OKHTTP_TIMEOUT_MILLIS / 2) {
        MAX_OKHTTP_TIMEOUT_MILLIS
    } else {
        readTimeoutMillis * 2
    }
    return maxOf(MIN_DERIVED_CALL_TIMEOUT_MILLIS, doubled)
}

internal fun buildRequestClient(
    baseClient: OkHttpClient,
    readTimeoutMillis: Long?,
    callTimeoutMillis: Long?,
    followRedirects: Boolean?,
    targetHost: String?,
    dnsAddresses: List<InetAddress>?,
    interceptorToRemove: Interceptor? = null,
): OkHttpClient {
    if (
        readTimeoutMillis == null &&
        callTimeoutMillis == null &&
        followRedirects == null &&
        dnsAddresses == null
    ) {
        return baseClient
    }
    return baseClient.newBuilder().apply {
        if (interceptorToRemove != null) {
            interceptors().remove(interceptorToRemove)
        }
        followRedirects?.let {
            followRedirects(it)
            followSslRedirects(it)
        }
        readTimeoutMillis?.let {
            readTimeout(it, TimeUnit.MILLISECONDS)
            if (callTimeoutMillis == null) {
                callTimeout(derivedCallTimeoutMillis(it), TimeUnit.MILLISECONDS)
            }
        }
        callTimeoutMillis?.let {
            callTimeout(it, TimeUnit.MILLISECONDS)
        }
        dnsAddresses?.let {
            dns(buildScopedDns(requireNotNull(targetHost), it, baseClient.dns))
        }
    }.build()
}

private fun parseIpLiteral(value: String): InetAddress? {
    val unwrapped = when {
        value.startsWith('[') && value.endsWith(']') -> value.substring(1, value.lastIndex)
        value.startsWith('[') || value.endsWith(']') -> return null
        else -> value
    }
    if (unwrapped.any(Char::isWhitespace) || '%' in unwrapped) return null
    if (':' in unwrapped) {
        return runCatching { InetAddress.getByName(unwrapped) }.getOrNull()
    }
    val parts = unwrapped.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.any { char -> !char.isDigit() } }) {
        return null
    }
    val address = ByteArray(4)
    parts.forEachIndexed { index, part ->
        val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        address[index] = octet.toByte()
    }
    return InetAddress.getByAddress(address)
}

private fun invalidDnsIp(): Nothing =
    throw IllegalArgumentException("dnsIp must contain only IPv4 or IPv6 addresses")
