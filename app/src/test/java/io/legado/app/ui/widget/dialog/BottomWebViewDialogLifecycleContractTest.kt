package io.legado.app.ui.widget.dialog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BottomWebViewDialogLifecycleContractTest {

    private val source by lazy {
        projectFile(
            "src/main/java/io/legado/app/ui/widget/dialog/BottomWebViewDialog.kt"
        ).readText().replace("\r\n", "\n")
    }

    @Test
    fun `pooled webview follows the view lifecycle`() {
        val fields = section("private var pooledWebView", "private var source")
        val onViewCreated = section("override fun onViewCreated", "private fun initWebView")
        val onDestroyView = section("override fun onDestroyView", "override fun onConfigurationChanged")

        assertTrue(fields.contains("private var pooledWebView: PooledWebView? = null"))
        assertTrue(fields.contains("get() = checkNotNull(pooledWebView).realWebView"))
        assertFalse(source.contains("override fun onAttach"))

        val acquire = onViewCreated.indexOf("pooledWebView = WebViewPool.acquire(requireContext())")
        val resetHistory = onViewCreated.indexOf("needClearHistory = true", acquire)
        val attach = onViewCreated.indexOf("binding.webViewContainer.addView(currentWebView)")
        assertTrue(acquire >= 0)
        assertTrue(resetHistory > acquire)
        assertTrue(attach > resetHistory)

        val release = onDestroyView.indexOf("pooledWebView?.let(WebViewPool::release)")
        val clear = onDestroyView.indexOf("pooledWebView = null")
        assertTrue(release >= 0)
        assertTrue(clear > release)
    }

    @Test
    fun `webview initialization is cancelled with the view`() {
        val onViewCreated = section("override fun onViewCreated", "private fun initWebView")
        val upConfig = section("override fun upConfig", "@Suppress(\"unused\")")

        assertTrue(onViewCreated.contains("viewLifecycleOwner.lifecycleScope.launch(IO)"))
        assertTrue(onViewCreated.contains("withContext(Dispatchers.Main)"))
        assertTrue(onViewCreated.contains("catch (e: CancellationException)"))
        assertTrue(onViewCreated.contains("if (error is CancellationException) throw error"))
        assertFalse(onViewCreated.contains("runOnUiThread"))
        assertFalse(onViewCreated.contains("currentWebView.post"))

        val failure = onViewCreated.substringAfter("}.onFailure { error ->")
        val cancellationGuard = failure.indexOf("if (error is CancellationException) throw error")
        val errorPage = failure.indexOf("withContext(Dispatchers.Main)")
        assertTrue(cancellationGuard >= 0)
        assertTrue(errorPage > cancellationGuard)

        assertTrue(upConfig.contains("val owner = viewLifecycleOwnerLiveData.value ?: return"))
        val launch = upConfig.indexOf("owner.lifecycleScope.launch(Dispatchers.Main)")
        val tryBlock = upConfig.indexOf("try {")
        val parseConfig = upConfig.indexOf("GSON.fromJsonObject<Config>(config).getOrThrow()")
        val applyConfig = upConfig.indexOf("setConfig(config)")
        val cancellationCatch = upConfig.indexOf("catch (e: CancellationException)")
        val rethrowCancellation = upConfig.indexOf("throw e", cancellationCatch)
        val exceptionCatch = upConfig.indexOf("catch (e: Exception)")
        val logError = upConfig.indexOf("AppLog.put(\"config err\", e)")
        assertTrue(launch >= 0)
        assertTrue(tryBlock > launch)
        assertTrue(parseConfig > tryBlock)
        assertTrue(applyConfig > parseConfig)
        assertTrue(cancellationCatch > applyConfig)
        assertTrue(rethrowCancellation > cancellationCatch)
        assertTrue(exceptionCatch > rethrowCancellation)
        assertTrue(logError > exceptionCatch)
    }

    @Test
    fun `bottom sheet references follow the current dialog`() {
        val fields = section("private val binding", "private val displayMetrics")

        assertTrue(fields.contains("private val bottomSheet: View?"))
        assertTrue(
            fields.contains(
                "get() = dialog?.findViewById(com.google.android.material.R.id.design_bottom_sheet)"
            )
        )
        assertTrue(fields.contains("private val behavior: BottomSheetBehavior<View>?"))
        assertTrue(fields.contains("BottomSheetBehavior.from(sheet)"))
        assertFalse(fields.contains("private val bottomSheet by lazy"))
        assertFalse(fields.contains("private val behavior by lazy"))
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
}
