package io.legado.app.help.http

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpRetryTest {

    @Test
    fun redirectResponseIsReturnedWithoutRetry() = runBlocking {
        var callCount = 0
        val client = clientWithResponses {
            callCount++
            response(302, TrackingResponseBody("redirect"))
        }

        client.newCallResponse(retry = 2) { url("https://example.com") }.use {
            assertEquals(302, it.code)
        }
        assertEquals(1, callCount)
    }

    @Test
    fun responseIsClosedBeforeRetry() = runBlocking {
        var callCount = 0
        val firstBody = TrackingResponseBody("error")
        val client = clientWithResponses {
            callCount++
            if (callCount == 1) response(500, firstBody) else response(200, TrackingResponseBody("ok"))
        }

        client.newCallResponse(retry = 1) { url("https://example.com") }.use {
            assertEquals(200, it.code)
        }
        assertEquals(2, callCount)
        assertTrue(firstBody.closed)
    }

    @Test
    fun negativeRetryCountStillPerformsOneRequest() = runBlocking {
        var callCount = 0
        val client = clientWithResponses {
            callCount++
            response(500, TrackingResponseBody("error"))
        }

        client.newCallResponse(retry = -1) { url("https://example.com") }.close()
        assertEquals(1, callCount)
    }

    private fun clientWithResponses(response: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> response(chain.request()) })
            .build()

    private fun response(code: Int, body: ResponseBody): Response =
        Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(code.toString())
            .body(body)
            .build()

    private class TrackingResponseBody(content: String) : ResponseBody() {
        private val trackingSource = object : ForwardingSource(Buffer().writeUtf8(content)) {
            var isClosed = false

            override fun close() {
                isClosed = true
                super.close()
            }
        }
        private val bufferedSource = trackingSource.buffer()

        val closed: Boolean
            get() = trackingSource.isClosed

        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = -1

        override fun source(): BufferedSource = bufferedSource
    }
}
