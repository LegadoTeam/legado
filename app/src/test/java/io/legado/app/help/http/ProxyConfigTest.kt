package io.legado.app.help.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.IDN

class ProxyConfigTest {

    @Test
    fun parsesSupportedProxyProtocols() {
        assertEquals(
            ProxyConfig(ProxyProtocol.HTTP, "proxy.example", 8080),
            parseProxyConfig("http://proxy.example:8080"),
        )
        assertEquals(
            ProxyConfig(ProxyProtocol.SOCKS4, "127.0.0.1", 1080),
            parseProxyConfig("socks4://127.0.0.1:1080"),
        )
        assertEquals(
            ProxyConfig(ProxyProtocol.SOCKS5, "localhost", 1080),
            parseProxyConfig("SOCKS5://localhost:1080"),
        )
    }

    @Test
    fun preservesLegacyHttpAuthenticationFormat() {
        val config = parseProxyConfig("http://127.0.0.1:8080@用户名@密@码")

        assertEquals(ProxyProtocol.HTTP, config.protocol)
        assertEquals("127.0.0.1", config.host)
        assertEquals(8080, config.port)
        assertEquals(ProxyCredentials("用户名", "密@码"), config.credentials)
    }

    @Test
    fun parsesStandardHttpAuthenticationAndPercentEncoding() {
        val config = parseProxyConfig("http://user:p%40ss%3Aword@proxy.example:3128")
        val unicodeConfig = parseProxyConfig("http://用户名:密码@127.0.0.1:1080")

        assertEquals("proxy.example", config.host)
        assertEquals(ProxyCredentials("user", "p@ss:word"), config.credentials)
        assertEquals(ProxyCredentials("用户名", "密码"), unicodeConfig.credentials)
    }

    @Test
    fun parsesIpv6HostsWithAndWithoutBrackets() {
        val bracketed = parseProxyConfig("http://[2001:db8::1]:8080")
        val unbracketed = parseProxyConfig("http://2001:db8::1:8080")
        val legacy = parseProxyConfig("http://2001:db8::1:8080@user@password")

        assertEquals("2001:db8::1", bracketed.host)
        assertEquals(bracketed, unbracketed)
        assertEquals("2001:db8::1", legacy.host)
        assertEquals(ProxyCredentials("user", "password"), legacy.credentials)
    }

    @Test
    fun preservesInternalAndInternationalizedHostNames() {
        assertEquals("my_proxy", parseProxyConfig("http://my_proxy:8080").host)
        assertEquals(
            IDN.toASCII("代理.local"),
            parseProxyConfig("http://代理.local:8080").host,
        )
    }

    @Test
    fun acceptsPortBounds() {
        assertEquals(1, parseProxyConfig("http://proxy.example:1").port)
        assertEquals(65535, parseProxyConfig("http://proxy.example:65535").port)
    }

    @Test
    fun rejectsInvalidProxyConfigurationsWithoutEchoingCredentials() {
        val invalid = listOf(
            "proxy.example:8080",
            "https://proxy.example:8080",
            "http://proxy.example",
            "http://proxy.example:0",
            "http://proxy.example:65536",
            "http://proxy.example:abc",
            "http://proxy.example:8080/path",
            "http://proxy.example:8080?query=1",
            "http://proxy.example:8080#fragment",
            "http://:password@proxy.example:8080",
            "http://[fe80::1%25eth0]:8080",
        )

        invalid.forEach { value ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                parseProxyConfig(value)
            }
            assertFalse(error.message.orEmpty().contains(value))
        }

        val credentialError = assertThrows(IllegalArgumentException::class.java) {
            parseProxyConfig("http://user:secret@proxy.example:8080/path")
        }
        assertFalse(credentialError.message.orEmpty().contains("secret"))
    }

    @Test
    fun rejectsSocksAuthentication() {
        assertThrows(IllegalArgumentException::class.java) {
            parseProxyConfig("socks5://user:password@proxy.example:1080")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseProxyConfig("socks4://proxy.example:1080@user@password")
        }
    }

    @Test
    fun proxyAuthenticationRetriesOnlyFirstChallenge() {
        assertTrue(shouldRetryProxyAuthentication(407, false, 1))
        assertFalse(shouldRetryProxyAuthentication(407, true, 1))
        assertFalse(shouldRetryProxyAuthentication(407, false, 2))
        assertFalse(shouldRetryProxyAuthentication(401, false, 1))
        assertNull(parseProxyConfig("http://proxy.example:8080").credentials)
    }
}
