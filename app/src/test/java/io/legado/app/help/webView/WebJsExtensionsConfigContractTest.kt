package io.legado.app.help.webView

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebJsExtensionsConfigContractTest {

    private val source by lazy {
        projectFile("src/main/java/io/legado/app/help/webView/WebJsExtensions.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    @Test
    fun `duplicate bottom sheet configs are not forwarded`() {
        assertTrue(source.contains("private var lastForwardedConfig: String? = null"))

        val start = source.indexOf("fun upConfig(config: String)")
        val end = source.indexOf("/**", start)
        require(start >= 0 && end > start)
        val method = source.substring(start, end)

        val guard = method.indexOf("if (config == lastForwardedConfig) return")
        val remember = method.indexOf("lastForwardedConfig = config")
        val forward = method.indexOf("callbackRef.get()?.upConfig(config)")
        assertTrue(guard >= 0)
        assertTrue(remember > guard)
        assertTrue(forward > remember)
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
