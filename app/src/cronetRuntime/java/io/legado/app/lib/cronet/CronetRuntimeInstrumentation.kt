package io.legado.app.lib.cronet

import android.app.Activity
import android.app.Instrumentation
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Base64
import androidx.annotation.Keep
import androidx.preference.PreferenceManager
import io.legado.app.BuildConfig
import io.legado.app.constant.PreferKey
import io.legado.app.help.http.Cronet
import io.legado.app.help.http.getProxyClient
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.source
import org.chromium.base.ContextUtils
import org.chromium.net.impl.CronetUrlRequestContext
import org.chromium.net.X509Util
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Keep
class CronetRuntimeInstrumentation : Instrumentation() {
    private var fixtureCa: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        fixtureCa = arguments?.getString("fixtureCa")
        start()
    }

    override fun onStart() {
        super.onStart()
        try {
            waitForIdleSync()
            // Trust only the ephemeral CI fixture, before Cronet's first native load.
            val certificate = Base64.decode(requireNotNull(fixtureCa), Base64.DEFAULT)
            ContextUtils.initApplicationContext(targetContext.applicationContext)
            X509Util.addTestRootCertificate(certificate)
            X509Util.setTestRootCertificateForBuiltin(certificate)
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
        check(preferences.edit().putBoolean(PreferKey.cronet, false).commit())
        val ordinaryRequests = AtomicInteger()
        // Use the actual startup client, created while the switch is off, throughout all toggles.
        val sharedClient = okHttpClient.newBuilder().addNetworkInterceptor { chain ->
            check(!preferences.getBoolean(PreferKey.cronet, false)) {
                "Enabling Cronet did not update the existing production client"
            }
            ordinaryRequests.incrementAndGet()
            chain.proceed(chain.request())
        }.build()
        val proxyClient = getProxyClient("http://127.0.0.1:8888")
        check(Cronet.interceptor !in proxyClient.interceptors) {
            "A proxy client created while Cronet was disabled retained its interceptor"
        }
        val nativeName = "libcronet.${BuildConfig.Cronet_Version}.so"
        val executor = Executors.newSingleThreadExecutor()
        val client = OkHttpClient.Builder()
            .addInterceptor(CronetInterceptor(CookieJar.NO_COOKIES))
            .addNetworkInterceptor { error("Selected Cronet fell back to OkHttp") }
            .callTimeout(120, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        try {
            // The first real network request is authenticated WebDAV with Cronet enabled,
            // including on the second invocation after the script force-stops the process.
            verifyWebDavDownloads(preferences)
            verifyTlsWebDavDownloads(preferences)
            ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 180_000
                val payload = "Cronet 上传\n\n验证"
                val served = executor.submit {
                    (List(5) { "GET" to "" } + ("POST" to payload)).forEach { (method, body) ->
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
                listOf(false, true, false, true).forEachIndexed { index, enabled ->
                    check(preferences.edit().putBoolean(PreferKey.cronet, enabled).commit())
                    sharedClient.newCall(request.build()).execute().use { response ->
                        assertEquals(200, response.code)
                        assertEquals("GET:", response.body.string())
                    }
                    assertEquals(if (index < 2) 1 else 2, ordinaryRequests.get())
                    if (enabled) {
                        check(Cronet.interceptor !in getProxyClient("http://127.0.0.1:8888").interceptors)
                    }
                }
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
            // A clean install and a force-stopped restart must both use the APK's native library.
            val dataDir = File(targetContext.applicationInfo.dataDir)
            val componentDir = File(dataDir, "app_cronet")
            val downloadCache = File(targetContext.cacheDir, "so_download")
            check(componentDir.walkTopDown().none { it.isFile }) { "Cronet downloaded a private component" }
            check(downloadCache.walkTopDown().none { it.isFile }) { "Cronet created a native download cache" }
            val packagePaths = listOf(targetContext.applicationInfo.sourceDir) +
                targetContext.applicationInfo.splitSourceDirs.orEmpty().toList()
            val nativeDir = File(targetContext.applicationInfo.nativeLibraryDir).canonicalPath
            val mappings = File("/proc/self/maps").readLines().filter { line ->
                line.contains("r-xp") && (line.contains("$nativeDir/$nativeName") ||
                    packagePaths.any { path -> line.contains(path) })
            }
            check(mappings.isNotEmpty()) { "No executable APK/native library mapping found" }
            val evidence = File(targetContext.getExternalFilesDir(null), "cronet-runtime/storage.txt")
            evidence.parentFile!!.mkdirs()
            evidence.writeText("nativeSource=apk\nversion=$version\n" +
                "webDav=authenticated,encoded-paths,cronet-first,on-off-on\n" +
                "productionClientToggle=off,on,off,on\nordinaryRequests=${ordinaryRequests.get()}\n" +
                "downloadedComponents=0\n" + mappings.joinToString("\n"))
            return "$version; productionClientToggle=off,on,off,on; nativeSource=apk; " +
                "webDav=authenticated,encoded-paths,cronet-first,on-off-on; downloadedComponents=0"

        } finally {
            preferences.edit().apply {
                if (hadPreference) putBoolean(PreferKey.cronet, previous) else remove(PreferKey.cronet)
            }.commit()
            executor.shutdownNow()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun verifyTlsWebDavDownloads(preferences: SharedPreferences) {
        val authorization = Authorization("runtime-user", "runtime-password")
        val names = listOf("中文书名：测试？_作者.json", "ascii_author.json")
        val evidence = File(targetContext.getExternalFilesDir(null), "cronet-runtime/webdav-tls.jsonl")
        evidence.parentFile!!.mkdirs()
        evidence.appendText("{\"event\":\"process-start\"}\n")
        val failures = mutableListOf<String>()
        for (port in listOf(19443, 19444)) {
            for (enabled in listOf(true, false, true)) {
                check(preferences.edit().putBoolean(PreferKey.cronet, enabled).commit())
                for (route in listOf("dav", "redirect/same", "redirect/cross")) {
                    for (name in names) {
                        val url = "https://localhost:$port/$route/legado/bookProgress/$name".toHttpUrl()
                        val result = runCatching {
                            runBlocking { WebDav(url.toString(), authorization).download() }
                        }
                        val error = result.exceptionOrNull()
                        val record = JSONObject().put("cronet", enabled).put("url", url.toString())
                            .put("status", (error as? WebDavException)?.responseCode ?: if (error == null) 200 else -1)
                        if (error != null) {
                            record.put("error", error.toString())
                            evidence.appendText("$record\n")
                            failures += "cronet=$enabled $url: $error"
                            continue
                        }
                        val wire = JSONObject(requireNotNull(result.getOrThrow()).toString(Charsets.UTF_8))
                        record.put("wire", wire)
                        evidence.appendText("$record\n")
                        val cross = route == "redirect/cross"
                        val targetPort = if (cross) { if (port == 19443) 19444 else 19443 } else port
                        val targetHost = if (cross) "127.0.0.1" else "localhost"
                        val targetPath = ("https://$targetHost:$targetPort/" +
                            (if (cross) "signed" else "dav") + "/legado/bookProgress/$name").toHttpUrl().encodedPath
                        assertEquals("GET", wire.getString("method"))
                        assertEquals(targetPath, wire.getString("path"))
                        assertEquals("$targetHost:$targetPort", wire.getString("authority"))
                        assertEquals(if (cross) "" else authorization.data, wire.getString("authorization"))
                        assertEquals(0, wire.getInt("bodyBytes"))
                        assertEquals(if (targetPort == 19443) "2.0" else "1.1", wire.getString("httpVersion"))
                        if (targetPort == 19443) assertEquals("h2", wire.getString("alpn"))
                    }
                }
                val missing = runCatching {
                    runBlocking { WebDav("https://localhost:$port/dav/missing.json", authorization).download() }
                }.exceptionOrNull()
                assertEquals(404, (missing as? WebDavException)?.responseCode)
            }
        }
        check(failures.isEmpty()) { "TLS WebDAV regressions:\n${failures.joinToString("\n")}" }
    }

    private fun verifyWebDavDownloads(preferences: SharedPreferences) {
        val authorization = Authorization("runtime-user", "runtime-password")
        val names = listOf("中文书名：测试？_作者.json", "ascii_author.json")
        val progress = """{"durChapterIndex":12,"durChapterPos":34}"""
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 30_000
                val base = "http://127.0.0.1:${server.localPort}/dav/legado/bookProgress/"
                val enabled = listOf(true, false, true)
                val served = executor.submit {
                    enabled.forEach { _ ->
                        names.forEach { name ->
                            server.accept().use { socket ->
                                socket.soTimeout = 30_000
                                val input = socket.getInputStream().source().buffer()
                                assertEquals("GET ${(base + name).toHttpUrl().encodedPath} HTTP/1.1",
                                    input.readUtf8LineStrict())
                                val headers = mutableListOf<Pair<String, String>>()
                                while (true) {
                                    val header = input.readUtf8LineStrict()
                                    if (header.isEmpty()) break
                                    headers += header.substringBefore(':').lowercase() to header.substringAfter(':').trim()
                                }
                                assertEquals(listOf(authorization.data), headers.filter { it.first == "authorization" }.map { it.second })
                                assertEquals(listOf("127.0.0.1:${server.localPort}"), headers.filter { it.first == "host" }.map { it.second })
                                assertEquals(0L, headers.firstOrNull { it.first == "content-length" }?.second?.toLong() ?: 0L)
                                val bytes = progress.toByteArray(Charsets.UTF_8)
                                socket.getOutputStream().apply {
                                    write(("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n" +
                                        "Content-Type: application/json\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                                        .toByteArray(Charsets.US_ASCII))
                                    write(bytes)
                                    flush()
                                }
                            }
                        }
                    }
                }
                enabled.forEach { useCronet ->
                    check(preferences.edit().putBoolean(PreferKey.cronet, useCronet).commit())
                    names.forEach { name ->
                        val actual = runBlocking { WebDav(base + name, authorization).download() }
                        assertEquals(progress, actual.toString(Charsets.UTF_8))
                    }
                }
                served.get(30, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun assertEquals(expected: Any?, actual: Any?) {
        check(expected == actual) { "Expected <$expected>, got <$actual>" }
    }
}
