package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckSourceEndpointTest {

    @Test
    fun `http and https use their default ports`() {
        assertEquals("example.com" to 80, parseCheckSourceEndpoint("http://example.com/path"))
        assertEquals("example.com" to 443, parseCheckSourceEndpoint("https://example.com/path"))
        assertEquals("example.com" to 443, parseCheckSourceEndpoint("HTTPS://EXAMPLE.COM/path"))
    }

    @Test
    fun `explicit ports and ipv6 hosts are preserved`() {
        assertEquals(
            "example.com" to 8443,
            parseCheckSourceEndpoint("https://example.com:8443/path")
        )
        assertEquals(
            "2001:db8::1" to 443,
            parseCheckSourceEndpoint("https://[2001:db8::1]/path")
        )
    }

    @Test
    fun `path query and fragment do not change the endpoint`() {
        assertEquals(
            "example.com" to 443,
            parseCheckSourceEndpoint("https://example.com/path?q=/next#fragment")
        )
    }

    @Test
    fun `non http and invalid urls are rejected`() {
        assertNull(parseCheckSourceEndpoint("ftp://example.com/file"))
        assertNull(parseCheckSourceEndpoint("httpx://example.com/path"))
        assertNull(parseCheckSourceEndpoint("https:///missing-host"))
        assertNull(parseCheckSourceEndpoint("https://example.com:99999/path"))
    }
}
