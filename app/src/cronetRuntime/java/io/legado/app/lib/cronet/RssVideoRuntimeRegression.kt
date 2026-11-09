package io.legado.app.lib.cronet

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.ui.rss.read.ReadRssActivity
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** CI-only: decode and seek a generated MP4 through the real RSS WebView and native Cronet. */
internal object RssVideoRuntimeRegression {
    private class VideoProxyBypass(val userAgent: String?) :
        IllegalStateException("Video bypassed the RSS Cronet proxy; userAgent=$userAgent")

    fun verify(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val fixture = File(context.getExternalFilesDir(null), "rss-seek.mp4")
        check(fixture.length() > 5_000_000) { "Missing generated video fixture" }
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val workers = Executors.newCachedThreadPool()
        val requests = ConcurrentLinkedQueue<String>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val origin = "http://127.0.0.1:${server.localPort}/rss-video"
        val source = RssSource(sourceUrl = origin, sourceName = "RSS video runtime",
            header = """{"X-Rss-Runtime":"native-required"}""")
        var activity: Activity? = null
        workers.submit {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (error: IOException) {
                    if (!server.isClosed) failures.add(error)
                    break
                }
                workers.submit {
                    socket.use {
                        try {
                            socket.soTimeout = 15_000
                            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val request = checkNotNull(input.readLine())
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = checkNotNull(input.readLine())
                                if (line.isEmpty()) break
                                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                            }
                            if (request.split(' ')[1] != "/rss-video/seek.mp4") return@submit
                            requests.add("${headers["range"] ?: "full"}; proxy=${headers["x-rss-runtime"]}")
                            if (headers["x-rss-runtime"] != "native-required") {
                                throw VideoProxyBypass(headers["user-agent"])
                            }
                            val range = headers["range"]
                            val size = fixture.length()
                            val bounds = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                            check(range == null || bounds != null) { "Unexpected video range: $range" }
                            val start = bounds?.groupValues?.get(1)?.toLong() ?: 0L
                            val end = minOf(bounds?.groupValues?.get(2)?.toLongOrNull() ?: size - 1, size - 1)
                            check(start in 0..end)
                            val output = socket.getOutputStream()
                            output.write(("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\n" +
                                "Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\n" +
                                "Content-Length: ${end - start + 1}\r\n" +
                                (if (range != null) "Content-Range: bytes $start-$end/$size\r\n" else "") +
                                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            RandomAccessFile(fixture, "r").use { file ->
                                file.seek(start)
                                var remaining = end - start + 1
                                val buffer = ByteArray(8192)
                                while (remaining > 0 && !server.isClosed) {
                                    val count = file.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    check(count > 0)
                                    output.write(buffer, 0, count)
                                    output.flush()
                                    remaining -= count
                                    // Keep distant frames unbuffered so a seek must issue a new HTTP Range.
                                    SystemClock.sleep(30)
                                }
                            }
                        } catch (_: IOException) {
                            // Seeking cancels the old response; a broken socket is expected here.
                        } catch (error: Throwable) {
                            failures.add(error)
                        }
                    }
                }
            }
        }
        val report = File(context.getExternalFilesDir(null), "cronet-runtime").apply { mkdirs() }
        val states = JSONArray()
        try {
            appDb.rssSourceDao.insert(source)
            val html = "<html><body><video id='fixture-video' controls muted preload='metadata' " +
                "width='320' height='240' src='$origin/seek.mp4'></video></body></html>"
            activity = instrumentation.startActivitySync(Intent(context, ReadRssActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("origin", origin)
                .putExtra("title", "RSS video seek runtime").putExtra("startHtml", html))
            var webView: WebView? = null
            var configuredUserAgent = ""
            var providerUserAgent = ""
            instrumentation.runOnMainSync {
                webView = findWebView(activity!!.window.decorView)
                // Automate the user's play gesture; all resource handling remains production code.
                webView?.settings?.mediaPlaybackRequiresUserGesture = false
                configuredUserAgent = webView?.settings?.userAgentString.orEmpty()
                // Read the provider identity separately: ReadRssActivity may override the
                // page's User-Agent, while a legacy media player keeps the provider default.
                providerUserAgent = WebSettings.getDefaultUserAgent(context)
            }
            File(report, "rss-video-provider.txt").writeText(
                "providerUserAgent=$providerUserAgent\nconfiguredUserAgent=$configuredUserAgent")
            val web = checkNotNull(webView)
            val setupDeadline = SystemClock.uptimeMillis() + 15_000
            while (true) {
                val state = evaluate(instrumentation, web,
                    "if(!v.__runtimeStarted){v.__runtimeStarted=true;v.play();}")
                states.put(JSONObject().put("stage", "metadata").put("state", state))
                File(report, "rss-video.json").writeText(JSONObject()
                    .put("requests", JSONArray(requests.toList())).put("states", states)
                    .put("serverFailures", failures.joinToString()).toString(2))
                // This exact stock provider delegates video to Android MediaPlayer outside
                // shouldInterceptRequest; positive and old-wrapper CI controls both reject it.
                // Chromium 44 media/base/android/java/src/org/chromium/media/MediaPlayerBridge.java.
                if (providerUserAgent.contains("Chrome/44.0.2403.119 ") && failures.isNotEmpty() &&
                    failures.all { it is VideoProxyBypass && it.userAgent == providerUserAgent }) {
                    val status = "unsupported-webview-44.0.2403.119"
                    File(report, "rss-video-unsupported.txt").writeText(
                        "RSS_VIDEO_SEEK_UNSUPPORTED $status: native MediaPlayer bypasses interception; " +
                            "providerUserAgent=$providerUserAgent; configuredUserAgent=$configuredUserAgent; $requests")
                    return status
                }
                check(failures.isEmpty()) { "Video fixture server failed: ${failures.firstOrNull()}" }
                check(state.optInt("error") == 0) { "Video failed before playback: $state" }
                if (state.optDouble("duration", 0.0) > 55) break
                check(SystemClock.uptimeMillis() < setupDeadline) { "Video metadata unavailable: $state" }
                SystemClock.sleep(200)
            }
            for (target in listOf(0, 40, 10, 50)) {
                evaluate(instrumentation, web, "v.currentTime=$target;v.play();")
                val deadline = SystemClock.uptimeMillis() + 20_000
                var firstFrame: JSONObject? = null
                while (true) {
                    val state = evaluate(instrumentation, web, "")
                    states.put(JSONObject().put("target", target).put("state", state))
                    File(report, "rss-video.json").writeText(JSONObject()
                        .put("requests", JSONArray(requests.toList())).put("states", states).toString(2))
                    check(failures.isEmpty()) { "Video fixture server failed: ${failures.firstOrNull()}" }
                    check(state.optInt("error") == 0) { "Video decoding failed after seek to $target: $state" }
                    val time = state.optDouble("time", 0.0)
                    if (!state.optBoolean("seeking") && !state.optBoolean("paused", true) &&
                        state.optInt("readyState") >= 2 && time >= target && time < target + 8 &&
                        state.optInt("width") == 320 && state.optInt("height") == 240 &&
                        state.optString("pixel").contains(',')) {
                        if (firstFrame == null) firstFrame = state
                        if (time - firstFrame.optDouble("time") >= 0.8 &&
                            state.optString("pixel") != firstFrame.optString("pixel")) break
                    }
                    check(SystemClock.uptimeMillis() < deadline) {
                        "Video did not decode advancing frames after seek to $target: $state; ranges=$requests"
                    }
                    SystemClock.sleep(200)
                }
            }
            check(requests.mapNotNull { it.substringAfter("bytes=", "").substringBefore('-').toLongOrNull() }
                .filter { it > 0 }.distinct().size >= 2) { "Seeks did not exercise distinct nonzero ranges: $requests" }
            File(report, "rss-video-passed.txt").writeText("RSS_VIDEO_SEEK_PASSED targets=0,40,10,50; ranges=$requests")
            return "passed"
        } finally {
            activity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
            appDb.rssSourceDao.delete(source)
            server.close()
            workers.shutdownNow()
        }
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    private fun evaluate(instrumentation: Instrumentation, web: WebView, action: String): JSONObject {
        val ready = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync {
            web.evaluateJavascript("""(function(){var v=document.getElementById('fixture-video');if(!v)return '{}';
                $action
                var pixel='';if(v.readyState>=2){try{var c=document.createElement('canvas');c.width=16;c.height=16;
                var x=c.getContext('2d');x.drawImage(v,0,0,16,16);pixel=Array.prototype.join.call(x.getImageData(0,0,16,16).data,',');}catch(e){pixel=String(e);}}
                return JSON.stringify({time:v.currentTime,duration:v.duration,seeking:v.seeking,paused:v.paused,
                readyState:v.readyState,width:v.videoWidth,height:v.videoHeight,error:v.error?v.error.code:0,pixel:pixel});})()""".trimIndent()) {
                result = it
                ready.countDown()
            }
        }
        check(ready.await(5, TimeUnit.SECONDS)) { "RSS video WebView did not respond" }
        return (JSONTokener(result).nextValue() as? String)?.let(::JSONObject) ?: JSONObject()
    }
}
