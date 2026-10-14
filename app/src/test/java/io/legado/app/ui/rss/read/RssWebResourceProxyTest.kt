package io.legado.app.ui.rss.read

import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RssWebResourceProxyTest {

    @Test
    fun webViewAbsoluteSeekDoesNotSkipAnAlreadyRangedResponseTwice() {
        for (range in listOf("bytes=100-", "bytes=100-105")) {
            rangeStream(range, "bytes 100-105/200").use { stream ->
                assertEquals(0, stream.available())
                assertEquals(40L, stream.skip(40))
                assertEquals(60L, stream.skip(60))
                assertEquals('a'.code, stream.read())
                assertEquals(2L, stream.skip(2))
                assertEquals("def", stream.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun directReadsAndSuffixRangesDoNotAcquireAnExtraOffset() {
        for (range in listOf("bytes=100-", "bytes=-6")) {
            rangeStream(range, "bytes 100-105/106").use { stream ->
                assertEquals(0, stream.available())
                assertEquals('a'.code, stream.read())
                assertEquals(2L, stream.skip(2))
                assertEquals("def", stream.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun onlyMatchingValidPartialResponsesAcknowledgeWebViewSeek() {
        val invalid = listOf(
            "bytes=100-" to "bytes 101-106/200",
            "bytes=100-104" to "bytes 100-105/200",
            "bytes=100-" to "bytes 100-99/200",
            "bytes=100-" to "bytes 100-105/105",
            "bytes=100-,200-" to "bytes 100-105/300",
            "bytes=100-" to "bytes 100-105/999999999999999999999",
            "bytes=100-999999999999999999999" to "bytes 100-105/200",
            "bytes=100-" to "invalid",
        )
        invalid.forEach { (range, returned) ->
            rangeStream(range, returned).use { stream ->
                assertEquals(1L, stream.skip(1))
                assertEquals('b'.code, stream.read())
            }
        }
        rangeStream("bytes=100-", "bytes 100-105/200", code = 200).use { stream ->
            assertEquals(1L, stream.skip(1))
            assertEquals('b'.code, stream.read())
        }
    }

    @Test
    fun largeOffsetsRemainLongAndSkipPastInitialOffsetConsumesOnlyTheExcess() {
        rangeStream("bytes=3000000000-", "bytes 3000000000-3000000005/*").use { stream ->
            assertEquals(0L, stream.skip(-1))
            assertEquals(0L, stream.skip(0))
            assertEquals(3000000002L, stream.skip(3000000002L))
            assertEquals("cdef", stream.readBytes().toString(Charsets.UTF_8))
        }
    }

    private fun rangeStream(range: String, returned: String, code: Int = 206): RssProxyResponseInputStream {
        val body = Buffer().writeUtf8("abcdef").asResponseBody()
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.com/video.mp4").header("Range", range).build())
            .protocol(Protocol.HTTP_1_1).code(code).message("OK")
            .header("Content-Range", returned).body(body).build()
        return RssProxyResponseInputStream(response, body)
    }

    @Test
    fun webViewCanQueryAndReadAfterEofUntilItClosesTheResponse() {
        var closeCount = 0
        val source = object : ForwardingSource(Buffer().writeUtf8("complete image bytes")) {
            override fun close() {
                closeCount++
                super.close()
            }
        }
        val body = source.buffer().asResponseBody()
        val response = Response.Builder().request(Request.Builder().url("https://example.com/image.png").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        val stream = RssProxyResponseInputStream(response, body)

        assertEquals("complete image bytes", stream.readBytes().toString(Charsets.UTF_8))
        assertEquals(0, closeCount)
        assertEquals(0, stream.available())
        assertEquals(-1, stream.read())
        assertEquals(-1, stream.read(ByteArray(8), 0, 8))
        stream.close()
        stream.close()
        assertEquals(1, closeCount)
    }

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

    @Test
    fun activityDoesNotReadWebViewStateFromTheResourceInterceptorThread() {
        val source = java.io.File(
            "src/main/java/io/legado/app/ui/rss/read/ReadRssActivity.kt"
        ).readText()
        val interceptor = source.substringAfter(
            "private suspend fun getProxiedResource",
        ).substringBefore("private suspend fun getModifiedContentWithJs")

        assertTrue(source.contains("@Volatile\n    private var currentPageUrl: String? = null"))
        assertTrue(source.contains("currentPageUrl = url"))
        assertTrue(interceptor.contains("currentPageUrl"))
        assertFalse(interceptor.contains("currentWebView.url"))
    }
}
