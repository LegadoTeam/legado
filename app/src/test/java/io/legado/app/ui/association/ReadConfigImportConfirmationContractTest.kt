package io.legado.app.ui.association

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadConfigImportConfirmationContractTest {

    @Test
    fun `online read config waits for confirmation before importing`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/association/OnLineImportActivity.kt"
        )
        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/association/OnLineImportViewModel.kt"
        )
        val confirmation = activity.substringAfter("private fun confirmReadConfigImport()")
            .substringBefore("private fun finallyDialog")
        val directLoad = viewModel.substringAfter("fun getReadConfig(url: String)")
            .substringBefore("fun importReadConfig()")
        val confirmedImport = viewModel.substringAfter("fun importReadConfig()")
            .substringBefore("fun cancelReadConfigImport()")
        val binaryImport = viewModel.substringAfter("\"application/zip\".toMediaType(),")
            .substringBefore("else ->")

        assertTrue(activity.contains("\"/readConfig\" -> viewModel.getReadConfig(url)"))
        assertTrue(activity.contains("viewModel.readConfigLive.observe(this)"))
        assertEquals(3, Regex("viewModel\\.determineType\\(url\\)").findAll(activity).count())
        assertTrue(
            activity.indexOf("if (viewModel.intentHandled) return") <
                    activity.indexOf("intent.data?.let")
        )
        assertTrue(confirmation.contains("yesButton"))
        assertTrue(confirmation.contains("viewModel.importReadConfig()"))
        assertTrue(confirmation.contains("viewModel.cancelReadConfigImport()"))
        assertFalse(directLoad.contains("ReadBookConfig.import"))
        assertTrue(confirmedImport.contains("val bytes = readConfigLive.value ?: return"))
        assertTrue(
            confirmedImport.indexOf("readConfigLive.value = null") <
                    confirmedImport.indexOf("ReadBookConfig.import(bytes)")
        )
        assertTrue(binaryImport.contains("rs.bytes()"))
        assertFalse(binaryImport.contains("importReadConfig"))
        assertFalse(activity.contains("viewModel.importReadConfig(bytes"))
    }

    private fun projectFile(pathInApp: String): String {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("Missing project file: $pathInApp")
    }
}
