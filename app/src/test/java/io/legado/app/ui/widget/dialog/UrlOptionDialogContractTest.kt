package io.legado.app.ui.widget.dialog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UrlOptionDialogContractTest {

    private val source by lazy {
        projectFile("src/main/java/io/legado/app/ui/widget/dialog/UrlOptionDialog.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    @Test
    fun `request and response scripts use their matching setters`() {
        assertTrue(source.contains("urlOption.setJs(binding.editJs.text.toString())"))
        assertTrue(source.contains("urlOption.setBodyJs(binding.editBodyJs.text.toString())"))
        assertFalse(source.contains("urlOption.setJs(binding.editBodyJs.text.toString())"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
