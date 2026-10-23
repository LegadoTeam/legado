package io.legado.app.lib.cronet

import java.net.IDN
import java.net.InetAddress
import java.util.Locale

internal data class CronetRequestConfig(
    val followRedirects: Boolean? = null,
    val dnsHost: String? = null,
    val dnsAddress: String? = null,
)

internal fun buildHostResolverRules(
    targetHost: String,
    hostAddresses: Map<String, List<InetAddress>>,
    requestConfig: CronetRequestConfig? = null,
): String? {
    val canonicalTarget = canonicalResolverHost(targetHost) ?: return null
    var address = hostAddresses.entries.firstNotNullOfOrNull { (host, addresses) ->
        if (canonicalResolverHost(host) != canonicalTarget) return@firstNotNullOfOrNull null
        addresses.firstOrNull()?.hostAddress?.let(::formatResolverAddress)
    }

    val requestHost = requestConfig?.dnsHost?.let(::canonicalResolverHost)
    val requestAddress = requestConfig?.dnsAddress?.let(::formatResolverAddress)
    if (requestHost == canonicalTarget && requestAddress != null) {
        address = requestAddress
    }

    return address?.let { "MAP $canonicalTarget $it" }
}

private fun canonicalResolverHost(value: String): String? {
    val host = value.trim().trimEnd('.')
    if (host.isEmpty()) return null
    val ascii = runCatching { IDN.toASCII(host) }.getOrNull()?.lowercase(Locale.ROOT)
        ?: return null
    if (ascii.isEmpty() || ascii.any { it.isWhitespace() || it == ',' }) return null
    return ascii
}

private fun formatResolverAddress(value: String): String? {
    val address = value.trim().removePrefix("[").removeSuffix("]")
    if (address.isEmpty() || '%' in address || address.any(Char::isWhitespace)) return null
    return if (':' in address) "[$address]" else address
}
