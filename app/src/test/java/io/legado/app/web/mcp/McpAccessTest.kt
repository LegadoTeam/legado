package io.legado.app.web.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class McpAccessTest {

    @Test
    fun hostAndOriginAllowListsIncludeLoopbackAndLan() {
        val address = InetAddress.getByName("192.168.3.9")
        val hosts = McpAccess.allowedHosts(listOf(address, address))

        assertEquals(listOf("localhost", "127.0.0.1", "[::1]", "192.168.3.9"), hosts)
        assertEquals(
            listOf(
                "http://localhost",
                "http://127.0.0.1",
                "http://[::1]",
                "http://192.168.3.9",
            ),
            McpAccess.allowedOrigins(hosts),
        )
    }

    @Test
    fun endpointUsesLanAddressOrLoopbackFallback() {
        assertEquals(
            listOf("http://192.168.3.9:1236/mcp"),
            McpAccess.endpointUrls(listOf(InetAddress.getByName("192.168.3.9")), 1236),
        )
        assertEquals(
            listOf("http://127.0.0.1:1236/mcp"),
            McpAccess.endpointUrls(emptyList(), 1236),
        )
        assertTrue(McpAccess.TOKEN_HEADER.isNotBlank())
    }
}
