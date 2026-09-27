package io.legado.app.help.webView

import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewRequestConfigTest {

    private val defaultUserAgent = "default-agent"

    @Test
    fun usesDefaultUserAgentWhenHeadersAreMissing() {
        val config = null.toWebViewRequestConfig(defaultUserAgent)

        assertEquals(defaultUserAgent, config.userAgent)
        assertEquals(emptyMap<String, String>(), config.additionalHeaders)
    }

    @Test
    fun extractsUserAgentWithoutCaseSensitivity() {
        val config = mapOf("user-agent" to "source-agent")
            .toWebViewRequestConfig(defaultUserAgent)

        assertEquals("source-agent", config.userAgent)
        assertEquals(emptyMap<String, String>(), config.additionalHeaders)
    }

    @Test
    fun prefersCanonicalUserAgentWhenDuplicateCasesExist() {
        val config = linkedMapOf(
            "user-agent" to "lowercase-agent",
            "User-Agent" to "canonical-agent"
        ).toWebViewRequestConfig(defaultUserAgent)

        assertEquals("canonical-agent", config.userAgent)
    }

    @Test
    fun fallsBackWhenConfiguredUserAgentIsBlank() {
        val config = mapOf("USER-AGENT" to "  ")
            .toWebViewRequestConfig(defaultUserAgent)

        assertEquals(defaultUserAgent, config.userAgent)
        assertEquals(emptyMap<String, String>(), config.additionalHeaders)
    }

    @Test
    fun removesOnlyWebViewManagedAndInternalHeaders() {
        val headers = linkedMapOf(
            "User-Agent" to "source-agent",
            "cookiejar" to "enabled",
            "PrOxY" to "socks5://127.0.0.1:1080",
            "Cookie" to "session=one",
            "Authorization" to "Bearer token",
            "X-Requested-With" to "source-client",
            "Sec-Fetch-Site" to "same-origin"
        )
        val original = LinkedHashMap(headers)

        val config = headers.toWebViewRequestConfig(defaultUserAgent)

        assertEquals(
            linkedMapOf(
                "Cookie" to "session=one",
                "Authorization" to "Bearer token",
                "X-Requested-With" to "source-client",
                "Sec-Fetch-Site" to "same-origin"
            ),
            config.additionalHeaders
        )
        assertEquals(original, headers)
    }

    @Test
    fun skipsPreloadJsForKnownNonHtmlDownloads() {
        assertTrue(shouldInjectPreloadJs("text/html; charset=utf-8".toMediaType(), null))
        assertTrue(shouldInjectPreloadJs("application/xhtml+xml".toMediaType(), null))
        assertTrue(shouldInjectPreloadJs(null, null))
        assertTrue(
            shouldInjectPreloadJs(
                "text/html".toMediaType(),
                "inline; filename=attachment.html"
            )
        )
        assertFalse(shouldInjectPreloadJs("application/octet-stream".toMediaType(), null))
        assertFalse(
            shouldInjectPreloadJs(
                "text/html".toMediaType(),
                "attachment; filename=page.html"
            )
        )
    }
}
