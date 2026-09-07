package io.legado.app.lib.cronet

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.PreferenceManager
import io.legado.app.BuildConfig
import io.legado.app.constant.PreferKey
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.source
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Keep
class CronetRuntimeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        try {
            waitForIdleSync()
            val version = verifyNativeRequests()
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "CRONET_RUNTIME_PASSED $version; native GET/POST verified\n")
            })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", error.stackTraceToString())
            })
        }
    }

    private fun verifyNativeRequests(): String {
        check(!BuildConfig.DEBUG) { "This regression must exercise release shrinking" }
        val preferences = PreferenceManager.getDefaultSharedPreferences(targetContext)
        val hadPreference = preferences.contains(PreferKey.cronet)
        val previous = preferences.getBoolean(PreferKey.cronet, false)
        val executor = Executors.newSingleThreadExecutor()
        val client = OkHttpClient.Builder()
            .addInterceptor(CronetInterceptor(CookieJar.NO_COOKIES))
            .addNetworkInterceptor { error("Selected Cronet fell back to OkHttp") }
            .callTimeout(120, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        try {
            check(preferences.edit().putBoolean(PreferKey.cronet, true).commit())
            ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 180_000
                val payload = "Cronet 上传\n\n验证"
                val served = executor.submit {
                    listOf("GET" to "", "POST" to payload).forEach { (method, body) ->
                        server.accept().use { socket ->
                            socket.soTimeout = 30_000
                            val input = socket.getInputStream().source().buffer()
                            assertEquals("$method /runtime HTTP/1.1", input.readUtf8LineStrict())
                            var length = 0L
                            while (true) {
                                val header = input.readUtf8LineStrict()
                                if (header.isEmpty()) break
                                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                                    length = header.substringAfter(':').trim().toLong()
                                }
                            }
                            assertEquals(body, input.readUtf8(length))
                            val response = "$method:$body".toByteArray(Charsets.UTF_8)
                            socket.getOutputStream().apply {
                                write(("HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\n" +
                                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.US_ASCII))
                                write(response)
                                flush()
                            }
                        }
                    }
                }
                val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/runtime")
                client.newCall(request.build()).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("GET:", response.body.string())
                }
                client.newCall(request.post(payload.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    .build()).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("POST:$payload", response.body.string())
                }
                served.get(30, TimeUnit.SECONDS)
            }
            val version = requireNotNull(cronetEngine).versionString
            check(version.contains(BuildConfig.Cronet_Version)) { "Unexpected Cronet engine: $version" }
            val library = "libcronet.${BuildConfig.Cronet_Version}.so"
            check(File("/proc/self/maps").useLines { lines -> lines.any { it.contains(library) } }) {
                "Native library was not mapped: $library"
            }
            check(preferences.getBoolean(PreferKey.cronet, false)) { "Cronet preference changed" }
            return version
        } finally {
            preferences.edit().apply {
                if (hadPreference) putBoolean(PreferKey.cronet, previous) else remove(PreferKey.cronet)
            }.commit()
            executor.shutdownNow()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun assertEquals(expected: Any?, actual: Any?) {
        check(expected == actual) { "Expected <$expected>, got <$actual>" }
    }
}
