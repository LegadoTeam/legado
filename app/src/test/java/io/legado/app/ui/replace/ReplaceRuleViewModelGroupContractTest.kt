package io.legado.app.ui.replace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReplaceRuleViewModelGroupContractTest {

    private val source by lazy {
        projectFile("src/main/java/io/legado/app/ui/replace/ReplaceRuleViewModel.kt")
            .readText()
            .replace("\r\n", "\n")
            .replace(Regex("\\s+"), " ")
    }

    @Test
    fun `group deletion delegates to exact group update`() {
        assertTrue(source.contains("fun delGroup(group: String) = upGroup(group, null)"))
        assertTrue(source.contains("source.group.renameGroupExact(oldGroup, newGroup)"))
        assertFalse(source.contains("source.group?.splitNotBlank"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
