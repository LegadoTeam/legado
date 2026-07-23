package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkUtilsTest {

    @Test
    fun `base url excludes path query and fragment`() {
        assertEquals(
            "https://example.com",
            NetworkUtils.getBaseUrl("https://example.com/path?q=/next#fragment")
        )
        assertEquals(
            "https://example.com",
            NetworkUtils.getBaseUrl("https://example.com?query=value")
        )
        assertEquals(
            "https://example.com",
            NetworkUtils.getBaseUrl("https://example.com#fragment")
        )
    }

    @Test
    fun `short hosts do not hide the first path separator`() {
        assertEquals("http://a", NetworkUtils.getBaseUrl("http://a/path"))
        assertEquals(
            "https://example.com",
            NetworkUtils.getBaseUrl("https://example.com?next=/path")
        )
    }

    @Test
    fun `authority details are preserved`() {
        assertEquals(
            "https://user:pass@example.com:8443",
            NetworkUtils.getBaseUrl("https://user:pass@example.com:8443/path")
        )
        assertEquals(
            "https://example.com:443",
            NetworkUtils.getBaseUrl("https://example.com:443/path")
        )
        assertEquals(
            "http://[2001:db8::1]:8080",
            NetworkUtils.getBaseUrl("http://[2001:db8::1]:8080/path")
        )
        assertEquals(
            "https://例子.测试",
            NetworkUtils.getBaseUrl("https://例子.测试/path")
        )
        assertEquals(
            "HTTPS://EXAMPLE.COM",
            NetworkUtils.getBaseUrl("HTTPS://EXAMPLE.COM/path")
        )
    }

    @Test
    fun `invalid and unsupported urls are rejected`() {
        assertNull(NetworkUtils.getBaseUrl(null))
        assertNull(NetworkUtils.getBaseUrl(""))
        assertNull(NetworkUtils.getBaseUrl("ftp://example.com/file"))
        assertNull(NetworkUtils.getBaseUrl("httpx://example.com/path"))
        assertNull(NetworkUtils.getBaseUrl("https:///missing-host"))
        assertNull(NetworkUtils.getBaseUrl("not a url"))
    }
}
