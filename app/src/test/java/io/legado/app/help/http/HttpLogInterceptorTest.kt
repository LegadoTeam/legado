package io.legado.app.help.http

import io.legado.app.constant.AppLog
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class HttpLogInterceptorTest {

    @Test
    fun sanitizerRedactsCredentialsHeadersQueryAndBodies() {
        val url = HttpLogSanitizer.redactUrl(
            "https://user:pass@example.test/token/path-secret?token=abc&q=ok&api_key=xyz".toHttpUrl()
        )
        val headers = HttpLogSanitizer.formatHeaders(
            Headers.Builder()
                .add("Authorization", "Bearer secret")
                .add("Cookie", "session=secret")
                .add("X-Trace", "visible")
                .build()
        )
        val body = HttpLogSanitizer.body(
            "{\"password\":\"secret\",\"value\":\"visible\"}".toByteArray(),
            "application/json".toMediaType(),
            truncated = false,
        )

        assertFalse(url.contains("user"))
        assertFalse(url.contains("pass"))
        assertFalse(url.contains("abc"))
        assertFalse(url.contains("xyz"))
        assertFalse(url.contains("path-secret"))
        assertTrue(url.contains("q=ok"))
        assertFalse(headers.contains("Bearer secret"))
        assertFalse(headers.contains("session=secret"))
        assertTrue(headers.contains("X-Trace: visible"))
        assertFalse(body.contains("secret"))
        assertTrue(body.contains("visible"))
    }

    @Test
    fun interceptorRecordsDecompressedSanitizedTextWithoutChangingResponse() {
        val rawResponse = "{\"message\":\"ok\",\"token\":\"response-secret\"}"
        val compressed = gzip(rawResponse.toByteArray())
        var record: HttpLogRecord? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(HttpLogInterceptor({ true }) { record = it })
            .addInterceptor(DecompressInterceptor)
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Encoding", "gzip")
                    .header("Set-Cookie", "session=response-secret")
                    .body(compressed.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val request = Request.Builder()
            .url("https://example.test/path?token=query-secret")
            .header("Authorization", "Bearer request-secret")
            .post(
                "{\"password\":\"body-secret\"}"
                    .toRequestBody("application/json".toMediaType())
            )
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(rawResponse, response.body.string())
        }

        val captured = record
        assertNotNull(captured)
        assertEquals(200, captured?.statusCode)
        assertFalse(captured?.url.orEmpty().contains("query-secret"))
        assertFalse(captured?.requestHeaders.orEmpty().contains("request-secret"))
        assertFalse(captured?.requestBody.orEmpty().contains("body-secret"))
        assertFalse(captured?.responseHeaders.orEmpty().contains("response-secret"))
        assertFalse(captured?.responseBody.orEmpty().contains("response-secret"))
        assertTrue(captured?.responseBody.orEmpty().contains("ok"))
    }

    @Test
    fun appClientPlacesLoggerOutsideDecompression() {
        val source = listOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
            .resolve("io/legado/app/help/http/HttpHelper.kt")
            .readText()
        val logger = source.indexOf("builder.addInterceptor(HttpLogInterceptor())")
        val cronet = source.indexOf("if (AppConfig.isCronet)")
        val decompressor = source.indexOf("builder.addInterceptor(DecompressInterceptor)")

        assertTrue(logger >= 0)
        assertTrue(cronet > logger)
        assertTrue(decompressor > logger)
    }

    @Test
    fun storeReturnsNewestRecordsWithAClampedLimit() {
        HttpLogStore.clear()
        try {
            repeat(60) { index ->
                HttpLogStore.add(
                    HttpLogRecord(
                        id = index.toLong(),
                        time = index.toLong(),
                        method = "GET",
                        path = "/$index",
                        url = "https://example.test/$index",
                        statusCode = 200,
                        duration = index.toLong(),
                        requestHeaders = "",
                        requestBody = "",
                        responseHeaders = "",
                        responseBody = "",
                        error = null,
                    )
                )
            }

            assertEquals(listOf(59L, 58L), HttpLogStore.latest(2).map { it.id })
            assertEquals(1, HttpLogStore.latest(0).size)
            assertEquals(50, HttpLogStore.latest(100).size)
            assertNotNull(HttpLogStore.get(59L))
        } finally {
            HttpLogStore.clear()
            AppLog.clear()
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        return ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(bytes) }
            output.toByteArray()
        }
    }
}
