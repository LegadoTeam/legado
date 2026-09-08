package io.legado.app.lib.cronet

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.system.Os
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
import org.chromium.net.impl.CronetUrlRequestContext
import org.chromium.net.impl.CronetLibraryLoader
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
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
        val componentDir = targetContext.getDir("cronet", 0)
        val nativeName = "libcronet.${BuildConfig.Cronet_Version}.so"
        val cachedBefore = componentDir.walkTopDown().any { it.isFile && it.name == nativeName }
        val cachedMtime = componentDir.walkTopDown().firstOrNull { it.isFile && it.name == nativeName }?.lastModified()
        // Race real cold downloads/install callers, then retry normal native initialization after real I/O failure.
        val callers = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val installs = (1..8).map {
                callers.submit<Boolean> {
                    start.await()
                    CronetLoader.preDownload()
                    CronetLoader.install()
                }
            }
            start.countDown()
            installs.forEach { check(it.get(120, TimeUnit.SECONDS)) { "Concurrent Cronet install failed" } }
        } finally {
            callers.shutdownNow()
        }
        val installed = componentDir.walkTopDown().single { it.isFile && it.name == nativeName }
        if (!cachedBefore) {
            // Seed an obsolete ABI/version so the final one-file check also verifies migration cleanup.
            File(componentDir, "obsolete-abi/libcronet.old.so").apply {
                parentFile!!.mkdirs()
                writeText("obsolete native fixture")
            }
            val abiDir = installed.parentFile!!
            try {
                Os.chmod(abiDir.absolutePath, 0)
                val failure = runCatching { CronetLibraryLoader.ensureInitialized(targetContext) }.exceptionOrNull()
                check(failure is UnsatisfiedLinkError) { "Expected an actual failed native load, got $failure" }
                check(File("/proc/self/maps").readLines().none { it.contains(nativeName) }) {
                    "Failure case unexpectedly loaded Cronet"
                }
            } finally {
                Os.chmod(abiDir.absolutePath, 448) // 0700; no reflection or fabricated Cronet state.
            }
        }
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
            val engine = requireNotNull(cronetEngine)
            check(engine is CronetUrlRequestContext) { "Cronet selected a non-native engine: ${engine.javaClass}" }
            val version = engine.versionString
            check(version.contains(BuildConfig.Cronet_Version)) { "Unexpected Cronet engine: $version" }
            check(preferences.getBoolean(PreferKey.cronet, false)) { "Cronet preference changed" }
            val native = componentDir.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.single()
            check(native.name == nativeName) { "Unexpected cached native library: $native" }
            val metadata = targetContext.assets.open("cronet.json").bufferedReader().use {
                JSONObject(it.readText())
            }
            val digest = MessageDigest.getInstance("MD5").digest(native.readBytes())
            assertEquals(metadata.getString(native.parentFile!!.name), "%032x".format(BigInteger(1, digest)))
            check(File("/proc/self/maps").useLines { lines ->
                lines.any { it.contains(nativeName) && File(it.substringAfterLast(' ')).canonicalFile == native.canonicalFile }
            }) {
                "The downloaded Cronet library is not mapped in this process"
            }
            val downloadCache = File(targetContext.cacheDir, "so_download")
            check(downloadCache.walkTopDown().none { it.isFile }) { "The download left a duplicate native library" }
            if (cachedBefore) assertEquals(cachedMtime, native.lastModified())
            val storage = componentDir.walkTopDown().filter { it.isFile }.toList()
            check(storage == listOf(native)) { "Unexpected component files: $storage" }
            val evidence = File(targetContext.getExternalFilesDir(null), "cronet-runtime/storage.txt")
            evidence.parentFile!!.mkdirs()
            evidence.writeText("cachedBefore=$cachedBefore\nconcurrentInstallers=8\n" +
                "loadFailureRecovery=${!cachedBefore}\ncomponentFiles=${storage.size}\n" +
                "componentBytes=${storage.sumOf { it.length() }}\nnativeMtime=${native.lastModified()}\n" +
                "nativeFile=${native.canonicalPath}\n" +
                File("/proc/self/maps").readLines().filter { it.contains(nativeName) }.joinToString("\n"))
            RssImageRuntimeRegression.verify(this)
            return "$version; cachedBefore=$cachedBefore; concurrentInstallers=8; " +
                "loadFailureRecovery=${!cachedBefore}; componentFiles=1; " +
                "nativeBytes=${native.length()}; nativeMtime=${native.lastModified()}; nativeFile=$native"
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
