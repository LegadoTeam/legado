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

    @Test
    fun `batch moves allocate orders outside existing bounds`() {
        val top = source.substringAfter("fun topSelect").substringBefore("fun toBottom")
        val bottom = source.substringAfter("fun bottomSelect").substringBefore("fun upOrder")

        assertTrue(top.contains("it.order = minOrder++"))
        assertFalse(top.contains("it.order = ++minOrder"))
        assertTrue(bottom.contains("var maxOrder = appDb.replaceRuleDao.maxOrder + 1"))
        assertTrue(bottom.contains("it.order = maxOrder++"))
    }

    @Test
    fun `selection group actions copy rules and update only group members`() {
        assertTrue(
            source.contains(
                "fun selectionAddToGroups(rules: List<ReplaceRule>, groups: String)"
            )
        )
        assertTrue(
            source.contains(
                "fun selectionRemoveFromGroups(rules: List<ReplaceRule>, groups: String)"
            )
        )
        assertTrue(source.contains("rules[it].copy().addGroup(groups)"))
        assertTrue(source.contains("rules[it].copy().removeGroup(groups)"))
        assertFalse(source.contains("scope = groups"))
        assertFalse(source.contains("excludeScope = groups"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
