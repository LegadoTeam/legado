package io.legado.app.help.webView

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebViewPoolJavascriptInterfaceContractTest {

    private val poolSource by lazy {
        projectFile("src/main/java/io/legado/app/help/webView/WebViewPool.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    @Test
    fun `pool release removes every registered javascript interface`() {
        val registrationPattern =
            Regex("""addJavascriptInterface\([^,]+,\s*(name\w+)\s*\)""")
        val registeredNames = projectDirectory("src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                registrationPattern.findAll(file.readText()).map { it.groupValues[1] }
            }
            .toSet()
        val release = section("fun release", "private fun createNewWebView")
        val removalPattern =
            Regex("""removeJavascriptInterface\(WebJsExtensions\.(name\w+)\)""")
        val removedNames = removalPattern.findAll(release).map { it.groupValues[1] }.toList()

        assertEquals(
            setOf("nameBasic", "nameJava", "nameSource", "nameCache"),
            registeredNames
        )
        assertEquals(registeredNames, removedNames.toSet())
        assertEquals(registeredNames.size, removedNames.size)
    }

    @Test
    fun `interfaces are removed before blank page navigation`() {
        val release = section("fun release", "private fun createNewWebView")
        val stopLoading = release.indexOf("stopLoading()")
        val loadBlank = release.indexOf("loadUrl(BLANK_HTML)")

        assertTrue(stopLoading >= 0)
        assertTrue(loadBlank > stopLoading)
        listOf("nameBasic", "nameJava", "nameSource", "nameCache").forEach { name ->
            val removal = release.indexOf(
                "removeJavascriptInterface(WebJsExtensions.$name)"
            )
            assertTrue(removal > stopLoading)
            assertTrue(removal < loadBlank)
        }
    }

    private fun section(startMarker: String, endMarker: String): String {
        val start = poolSource.indexOf(startMarker)
        val end = poolSource.indexOf(endMarker, start)
        require(start >= 0 && end > start)
        return poolSource.substring(start, end)
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
