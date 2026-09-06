package io.legado.app.ui.rss.read

import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RssWebResourceProxyTest {

    @Test
    fun proxiesMediaSubresourcesButNotPagesOrNonNetworkRequests() {
        assertTrue(
            RssWebResourceProxy.shouldProxy(
                "https://cdn.example/video/poster",
                "GET",
                false,
                mapOf("Accept" to "image/avif,image/*"),
                null,
            )
        )
        assertTrue(
            RssWebResourceProxy.shouldProxy(
                "https://cdn.example/stream",
                "GET",
                false,
                mapOf("Sec-Fetch-Dest" to "video", "Range" to "bytes=0-"),
                null,
            )
        )
        assertFalse(
            RssWebResourceProxy.shouldProxy(
                "https://example.com/article",
                "GET",
                true,
                mapOf("Accept" to "text/html"),
                null,
            )
        )
        assertFalse(
            RssWebResourceProxy.shouldProxy(
                "data:text/html,<p>cached</p>",
                "GET",
                false,
                mapOf("Accept" to "*/*"),
                null,
            )
        )
        assertFalse(
            RssWebResourceProxy.shouldProxy(
                "https://example.com/file.css",
                "POST",
                false,
                mapOf("Accept" to "text/css"),
                null,
            )
        )
    }

    @Test
    fun keepsSourceAndWebViewHeadersWhileRemovingTransportHeaders() {
        val headers = RssWebResourceProxy.requestHeaders(
            sourceHeaders = mapOf(
                "Referer" to "https://source.example/",
                "CookieJar" to "true",
                "Cookie" to "source=one",
                "user-agent" to "source-agent",
            ),
            webViewHeaders = mapOf(
                "Range" to "bytes=0-99",
                "Accept-Encoding" to "gzip",
                "Cookie" to "web=two",
                "User-Agent" to "web-agent",
            ),
            cookie = "source=one; web=two",
        )

        assertEquals("https://source.example/", headers["Referer"])
        assertEquals("bytes=0-99", headers["Range"])
        assertEquals("source=one; web=two", headers["Cookie"])
        assertEquals("web-agent", headers["User-Agent"])
        assertFalse(headers.keys.count { it.equals("User-Agent", true) } > 1)
        assertFalse(headers.keys.any { it.equals("CookieJar", true) })
        assertFalse(headers.keys.any { it.equals("Accept-Encoding", true) })
    }

    @Test
    fun preservesRangeMetadataAndDoesNotExposeHopByHopHeaders() {
        val result = RssWebResourceProxy.responseHeaders(
            Headers.headersOf(
                "Content-Range", "bytes 0-99/200",
                "Content-Length", "100",
                "Accept-Ranges", "bytes",
                "Content-Encoding", "gzip",
                "Set-Cookie", "session=one",
            )
        )

        assertEquals("bytes 0-99/200", result["Content-Range"])
        assertEquals("100", result["Content-Length"])
        assertEquals("bytes", result["Accept-Ranges"])
        assertEquals("gzip", result["Content-Encoding"])
        assertFalse(result.keys.any { it.equals("Set-Cookie", true) })
        assertTrue(RssWebResourceProxy.supportsStatus(206))
        assertFalse(RssWebResourceProxy.supportsStatus(301))
    }

    @Test
    fun activityUsesTheCronetSubresourceProxyContract() {
        val source = java.io.File(
            "src/main/java/io/legado/app/ui/rss/read/ReadRssActivity.kt"
        ).readText()
        assertTrue(source.contains("RssWebResourceProxy.shouldProxy"))
        assertTrue(source.contains("RssProxyResponseInputStream(response, body)"))
        assertTrue(source.contains("webResponse.setStatusCodeAndReasonPhrase"))
    }
}
