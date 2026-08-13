package io.legado.app.help.webView

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class WebJsExtensionsResultCacheTest {

    @Test
    fun `await results are removed from memory after consumption`() {
        val source = projectFile(
            "src/main/java/io/legado/app/help/webView/WebJsExtensions.kt"
        ).readText().replace("\r\n", "\n")

        assertEquals(
            2,
            Regex("const result = cache\\.getFromMemory\\(id\\);\\s*cache\\.deleteMemory\\(id\\);")
                .findAll(source)
                .count(),
        )
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
