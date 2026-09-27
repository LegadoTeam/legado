package io.legado.app.ui.replace.edit

import io.legado.app.data.entities.ReplaceRule
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ReplacePreviewPersistenceContractTest {

    @Test
    fun `local preview preferences remain included in both backup rule formats`() {
        val legacy = projectFile("src/main/res/xml/backup_rules.xml")
        val modern = projectFile("src/main/res/xml/data_extraction_rules.xml")

        assertNotExcluded(legacy, "replace_preview.xml")
        assertNotExcluded(legacy, "replace_preview.xml.bak")
        assertNotExcluded(modern, "replace_preview.xml")
        assertNotExcluded(modern, "replace_preview.xml.bak")
    }

    @Test
    fun `custom backup embeds preview samples during backup and restore`() {
        val backup = projectFile("src/main/java/io/legado/app/help/storage/Backup.kt").readText()
        val restore = projectFile("src/main/java/io/legado/app/help/storage/Restore.kt").readText()

        assertTrue(backup.contains("ReplacePreviewConfig.withSamples"))
        assertTrue(restore.contains("ReplacePreviewConfig.saveImportedSamples"))
        assertTrue(restore.contains("clearMissing = true"))
    }

    @Test
    fun `preview sample is optional in replacement rule json`() {
        val rule = ReplaceRule(id = 7, pattern = "x").also { it.previewText = "input" }
        val restored = GSON.fromJsonObject<ReplaceRule>(GSON.toJson(rule)).getOrThrow()
        assertEquals("input", restored.previewText)

        val legacy = GSON.fromJsonObject<ReplaceRule>("""{"id":8,"pattern":"x"}""").getOrThrow()
        assertNull(legacy.previewText)
    }

    private fun assertNotExcluded(file: File, path: String) {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val excludes = document.getElementsByTagName("exclude")
        val found = (0 until excludes.length).any { index ->
            val node = excludes.item(index)
            node.attributes?.getNamedItem("domain")?.nodeValue == "sharedpref" &&
                node.attributes?.getNamedItem("path")?.nodeValue == path
        }
        assertFalse("Unexpected shared preference exclusion for $path", found)
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
