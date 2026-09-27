package io.legado.app.lib.cronet

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.ui.rss.read.ReadRssActivity
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** CI-only: exercise the actual RSS WebView and selected native Cronet response stream. */
internal object RssImageRuntimeRegression {
    fun verify(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val workers = Executors.newCachedThreadPool()
        val seen = ConcurrentHashMap.newKeySet<String>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val origin = "http://127.0.0.1:${server.localPort}/rss-fixture"
        val source = RssSource(sourceUrl = origin, sourceName = "RSS image runtime",
            header = """{"X-Rss-Runtime":"native-required"}""")
        val paths = listOf("fixed", "chunked", "gzip", "redirect")
        val image = imageBytes()
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(image) }
        }.toByteArray()
        var activity: Activity? = null
        workers.submit {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (error: Exception) {
                    if (!server.isClosed) failures.add(error)
                    break
                }
                workers.submit {
                    socket.use {
                        try {
                            socket.soTimeout = 15_000
                            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val request = checkNotNull(input.readLine())
                            val path = request.split(' ')[1].substringBefore('?')
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = checkNotNull(input.readLine())
                                if (line.isEmpty()) break
                                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                            }
                            if (!path.startsWith("/rss-fixture/")) return@submit
                            check(headers["x-rss-runtime"] == "native-required") {
                                "Resource bypassed the RSS Cronet proxy: $path"
                            }
                            seen.add(path)
                            val output = socket.getOutputStream()
                            if (path.endsWith("redirect.png")) {
                                output.write(("HTTP/1.1 301 Moved Permanently\r\n" +
                                    "Location: $origin/redirected.png\r\nContent-Length: 0\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            } else {
                                val chunked = path.endsWith("chunked.png")
                                val gzip = path.endsWith("gzip.png")
                                val bytes = if (gzip) compressed else image
                                output.write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n" +
                                    "Cache-Control: no-store\r\nConnection: close\r\n" +
                                    (if (gzip) "Content-Encoding: gzip\r\n" else "") +
                                    (if (chunked) "Transfer-Encoding: chunked\r\n" else "Content-Length: ${bytes.size}\r\n") +
                                    "\r\n").toByteArray(Charsets.US_ASCII))
                                for (offset in bytes.indices step 8192) {
                                    val count = minOf(8192, bytes.size - offset)
                                    if (chunked) output.write("${count.toString(16)}\r\n".toByteArray())
                                    output.write(bytes, offset, count)
                                    if (chunked) output.write("\r\n".toByteArray())
                                    output.flush()
                                    SystemClock.sleep(10)
                                }
                                if (chunked) output.write("0\r\n\r\n".toByteArray())
                            }
                            output.flush()
                        } catch (error: Throwable) {
                            failures.add(error)
                        }
                    }
                }
            }
        }
        try {
            appDb.rssSourceDao.insert(source)
            val html = "<html><body>" + paths.joinToString("") {
                "<img id='fixture-$it' src='$origin/$it.png' width='128' height='96'>"
            } + "</body></html>"
            activity = instrumentation.startActivitySync(Intent(context, ReadRssActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("origin", origin)
                .putExtra("title", "RSS image runtime").putExtra("startHtml", html))
            var webView: WebView? = null
            instrumentation.runOnMainSync { webView = findWebView(activity!!.window.decorView) }
            val web = checkNotNull(webView)
            val report = File(context.getExternalFilesDir(null), "cronet-runtime").apply { mkdirs() }
            val deadline = SystemClock.uptimeMillis() + 30_000
            var stableSince = 0L
            var state = JSONObject()
            do {
                state = evaluate(instrumentation, web)
                File(report, "rss-images.json").writeText(state.toString(2))
                check(failures.isEmpty()) { "RSS fixture server failed: ${failures.firstOrNull()}" }
                val decoded = paths.all {
                    val item = state.optJSONObject(it)
                    item?.optBoolean("complete") == true && item.optInt("width") == 256 &&
                        item.optInt("height") == 192 && item.optString("pixel") == "64,128,192,255"
                }
                if (decoded) {
                    if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
                    if (SystemClock.uptimeMillis() - stableSince >= 3_000) break
                } else {
                    stableSince = 0L
                }
                SystemClock.sleep(200)
            } while (SystemClock.uptimeMillis() < deadline)
            check(stableSince != 0L && SystemClock.uptimeMillis() - stableSince >= 3_000) {
                "RSS images failed to remain decoded for three seconds: $state"
            }
            check(paths.all { "/rss-fixture/$it.png" in seen } && "/rss-fixture/redirected.png" in seen) {
                "Missing native RSS image requests: $seen"
            }
            checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "RSS screenshot unavailable" }.let { screenshot ->
                try {
                    File(report, "rss-images.png").outputStream().use {
                        check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { screenshot.recycle() }
            }
            File(report, "rss-images-passed.txt").writeText("RSS_IMAGES_PASSED $seen; bytes=${image.size}")
        } finally {
            activity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
            appDb.rssSourceDao.delete(source)
            server.close()
            workers.shutdownNow()
        }
    }

    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(1106)
        bitmap.setPixels(IntArray(256 * 192) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) },
            0, 256, 0, 0, 256, 192)
        bitmap.setPixel(255, 191, Color.rgb(64, 128, 192))
        return ByteArrayOutputStream().use { out ->
            try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() }
            finally { bitmap.recycle() }
        }
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    private fun evaluate(instrumentation: Instrumentation, web: WebView): JSONObject {
        val ready = CountDownLatch(1)
        var value = "null"
        instrumentation.runOnMainSync {
            // API 23's stock WebView predates arrow functions, Array.from and Object.fromEntries.
            web.evaluateJavascript("""(function(){var state={};['fixed','chunked','gzip','redirect'].forEach(function(k){
                var i=document.getElementById('fixture-'+k),pixel='';
                if(i && i.complete && i.naturalWidth){try{var c=document.createElement('canvas');c.width=256;c.height=192;
                    var x=c.getContext('2d');x.drawImage(i,0,0);pixel=Array.prototype.join.call(x.getImageData(255,191,1,1).data,',');}catch(e){pixel=String(e);}}
                state[k]={complete:!!i&&i.complete,width:i?i.naturalWidth:0,height:i?i.naturalHeight:0,pixel:pixel};
            });return JSON.stringify(state);})()""".trimIndent()) { value = it; ready.countDown() }
        }
        check(ready.await(5, TimeUnit.SECONDS)) { "RSS WebView did not respond" }
        return (JSONTokener(value).nextValue() as? String)?.let(::JSONObject) ?: JSONObject()
    }
}
