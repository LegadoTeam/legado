package io.legado.app.help.webView

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebViewPoolReleaseContractTest {

    private val source by lazy {
        projectFile("src/main/java/io/legado/app/help/webView/WebViewPool.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    @Test
    fun `releasing a webview twice is idempotent`() {
        val release = section("fun release", "private fun createNewWebView")
        val guard = release.indexOf(
            "if (inUsePool.remove(pooledWebView.id) == null) return"
        )
        val reset = release.indexOf("pooledWebView.realWebView.run")

        assertTrue(guard >= 0)
        assertTrue(reset > guard)
    }

    @Test
    fun `owned destruction paths keep idle pool consistent`() {
        val release = section("fun release", "private fun createNewWebView")
        val poolFull = release.substringAfter(
            "if (idlePool.size >= CACHED_WEB_VIEW_MAX_NUM - inUsePool.size)"
        ).substringBefore("webViewClient =")
        val cleanup = section("toRemove.forEach", "if (idlePool.isEmpty())")

        assertTrue(poolFull.contains("pooledWebView.realWebView.destroy()"))
        assertTrue(poolFull.indexOf("destroy()") < poolFull.indexOf("return"))
        assertTrue(cleanup.indexOf("idlePool.remove(pooled)") >= 0)
        assertTrue(
            cleanup.indexOf("idlePool.remove(pooled)") <
                cleanup.indexOf("pooled.realWebView.destroy()")
        )
    }

    @Test
    fun `cleanup completion returns a released webview to idle pool only once`() {
        val release = section("fun release", "private fun createNewWebView")
        val callback = release.substringAfter("override fun onPageFinished")
        val generation = release.indexOf(
            "val recycleGeneration = ++pooledWebView.recycleGeneration"
        )
        val recycleUrl = release.indexOf(
            "val recycleUrl = \"\$BLANK_HTML?legado-recycle=\$recycleGeneration\""
        )
        val navigationGuard = callback.indexOf("if (url != recycleUrl) return")
        val synchronized = callback.indexOf("synchronized(this@WebViewPool)")
        val borrowedGuard = callback.indexOf(
            "if (!pooledWebView.isInUse || pooledWebView.id in inUsePool) return"
        )
        val reset = callback.indexOf("webview.settings.apply")
        val markIdle = callback.indexOf("pooledWebView.isInUse = false")
        val enqueue = callback.indexOf("idlePool.push(pooledWebView)")
        val loadRecycleUrl = release.indexOf("loadUrl(recycleUrl)")

        assertTrue(generation >= 0)
        assertTrue(recycleUrl > generation)
        assertTrue(navigationGuard >= 0)
        assertTrue(synchronized > navigationGuard)
        assertTrue(borrowedGuard > synchronized)
        assertTrue(reset > borrowedGuard)
        assertTrue(markIdle > reset)
        assertTrue(enqueue > markIdle)
        assertTrue(loadRecycleUrl > enqueue)
    }

    @Test
    fun `pooled webviews never pause global timers`() {
        val globalTimerCalls = projectDirectory("src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .flatMap { file ->
                Regex("""\.((?:pause|resume)Timers)\(\)""")
                    .findAll(file.readText())
                    .map { "${file.name}:${it.value}" }
            }
            .toList()

        assertTrue(globalTimerCalls.toString(), globalTimerCalls.isEmpty())
    }

    private fun section(startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start)
        require(start >= 0 && end > start)
        return source.substring(start, end)
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }

    private fun projectDirectory(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isDirectory }
            ?: error("Missing project directory: $pathInApp")
    }
}
