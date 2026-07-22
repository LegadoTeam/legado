package io.legado.app.web.mcp

import java.net.InetAddress

object McpAccess {

    const val PATH = "/mcp"
    const val TOKEN_HEADER = "X-Legado-Token"

    fun allowedHosts(addresses: List<InetAddress>): List<String> = buildList {
        add("localhost")
        add("127.0.0.1")
        add("[::1]")
        addresses.mapTo(this) { it.hostAddress }
    }.distinct()

    fun allowedOrigins(hosts: List<String>): List<String> {
        return hosts.map { "http://$it" }
    }

    fun endpointUrls(addresses: List<InetAddress>, port: Int): List<String> {
        val hosts = addresses.map { it.hostAddress }.ifEmpty { listOf("127.0.0.1") }
        return hosts.map { "http://$it:$port$PATH" }
    }
}
