package io.legado.app.ui.association

import io.legado.app.data.dao.mergeImportedHighlightRules
import io.legado.app.data.dao.reorderHighlightRules
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.help.HighlightStyle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HighlightRuleImportTest {

    @Test
    fun `typed file round trip keeps complete rule configuration`() {
        val source = HighlightRule(
            id = 41,
            uuid = UUID_A,
            name = "name",
            pattern = "a.+",
            isRegex = true,
            scope = "book",
            isEnabled = false,
            order = 9,
            timeoutMillisecond = 1234,
            applyToTitle = true,
            applyToBody = false
        ).apply {
            applyStyle(HighlightStyle(textColor = 7, bold = true))
        }
        val json = GSON.toJson(
            HighlightRuleFile(type = HighlightRuleFile.TYPE, rules = listOf(source))
        )

        val parsed = parseHighlightRuleFile(json).single()

        assertEquals(UUID_A, parsed.uuid)
        assertEquals("name", parsed.name)
        assertEquals("a.+", parsed.pattern)
        assertTrue(parsed.isRegex)
        assertEquals("book", parsed.scope)
        assertFalse(parsed.isEnabled)
        assertEquals(9, parsed.order)
        assertEquals(1234, parsed.timeoutMillisecond)
        assertTrue(parsed.applyToTitle)
        assertFalse(parsed.applyToBody)
        assertEquals(HighlightStyle(textColor = 7, bold = true), parsed.styleObj())
    }

    @Test
    fun `invalid envelope uuid and rule reject the whole file`() {
        val invalidFiles = listOf(
            "{}",
            """{"type":"replaceRule","rules":[]}""",
            """{"type":"highlightRule"}""",
            """{"type":"highlightRule","rules":[null]}""",
            """{"type":"highlightRule","rules":[{"uuid":"bad","pattern":"x"}]}""",
            """{"type":"highlightRule","rules":[
                {"uuid":"$UUID_A","pattern":"x"},
                {"uuid":"$UUID_A","pattern":"y"}
            ]}""",
            """{"type":"highlightRule","rules":[
                {"uuid":"$UUID_A","pattern":"[","isRegex":true}
            ]}"""
        )

        invalidFiles.forEach { json ->
            val file = GSON.fromJsonObject<HighlightRuleFile>(json).getOrThrow()
            assertThrows(Exception::class.java) { validateHighlightRuleFile(file) }
        }
    }

    @Test
    fun `typed file parser rejects nonstandard json syntax`() {
        val invalidJson = listOf(
            "{'type':'highlightRule','rules':[]}",
            """{"type":"highlightRule",/*comment*/"rules":[]}""",
            """{type:"highlightRule","rules":[]}""",
            """{"type":"highlightRule","rules":[{"pattern":"x"}]}""",
            """{"type":"highlightRule","rules":[{"uuid":"$UUID_A","pattern":123}]}""",
            """{"type":"highlightRule","rules":[{"uuid":"$UUID_A","pattern":"x","name":{}}]}""",
            """{"type":"highlightRule","rules":[{"uuid":"$UUID_A","pattern":"x","name":null}]}""",
            """{"type":"highlightRule","rules":[{"uuid":"$UUID_A","pattern":"x","isRegex":"true"}]}""",
            """{"type":"highlightRule","rules":[{"uuid":"$UUID_A","pattern":"x","isEnabled":null}]}""",
            """{"type":"highlightRule","rules":[{"uuid":"$UUID_A","pattern":"x","order":1.5}]}""",
            """{"type":"highlightRule","rules":[{"uuid":"$UUID_A","pattern":"x","timeoutMillisecond":9223372036854775808}]}"""
        )

        invalidJson.forEach { json ->
            assertThrows(Exception::class.java) { parseHighlightRuleFile(json) }
        }
    }

    @Test
    fun `comparison ignores local id and order but detects configuration changes`() {
        val local = HighlightRule(id = 1, uuid = UUID_A, name = "local", pattern = "x", order = 4)
        val same = local.copy(id = 99, order = 90)
        val changed = local.copy(id = 98, order = 91, pattern = "y")
        val added = HighlightRule(id = 1, uuid = UUID_B, pattern = "z")

        val statuses = compareImportedHighlightRules(
            listOf(same, changed, added),
            listOf(local)
        ).map(HighlightRuleImportItem::status)

        assertEquals(
            listOf(
                HighlightRuleImportStatus.EXISTING,
                HighlightRuleImportStatus.UPDATE,
                HighlightRuleImportStatus.NEW
            ),
            statuses
        )
    }

    @Test
    fun `merge keeps local identities and appends new rules without remote id collisions`() {
        val current = listOf(
            HighlightRule(id = 1, uuid = UUID_A, name = "first", pattern = "old", order = 3),
            HighlightRule(id = 2, uuid = UUID_B, name = "second", pattern = "stay", order = 8)
        )
        val imported = listOf(
            HighlightRule(id = 200, uuid = UUID_A, name = "updated", pattern = "new", order = 50),
            HighlightRule(id = 2, uuid = UUID_C, name = "third", pattern = "add", order = 1),
            HighlightRule(id = 2, uuid = UUID_D, name = "fourth", pattern = "add", order = 2)
        )

        val merged = mergeImportedHighlightRules(current, imported)

        assertEquals(listOf(UUID_A, UUID_B, UUID_C, UUID_D), merged.map { it.uuid })
        assertEquals(listOf(1L, 2L, 0L, 0L), merged.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), merged.map { it.order })
        assertEquals("updated", merged[0].name)
        assertEquals("stay", merged[1].pattern)
    }

    @Test
    fun `batch top and bottom keep relative order with unique continuous positions`() {
        val rules = listOf(UUID_A, UUID_B, UUID_C, UUID_D).mapIndexed { index, uuid ->
            HighlightRule(id = index + 1L, uuid = uuid, pattern = uuid, order = index * 2)
        }
        val selected = setOf(UUID_B, UUID_D)

        val top = reorderHighlightRules(rules, selected, toTop = true)
        val bottom = reorderHighlightRules(rules, selected, toTop = false)

        assertEquals(listOf(UUID_B, UUID_D, UUID_A, UUID_C), top.map { it.uuid })
        assertEquals(listOf(UUID_A, UUID_C, UUID_B, UUID_D), bottom.map { it.uuid })
        assertEquals(listOf(0, 1, 2, 3), top.map { it.order })
        assertEquals(listOf(0, 1, 2, 3), bottom.map { it.order })
    }

    @Test
    fun `file and online association both route typed highlight rules`() {
        val base = projectFile(
            "src/main/java/io/legado/app/ui/association/BaseAssociationViewModel.kt"
        )
        val file = projectFile(
            "src/main/java/io/legado/app/ui/association/FileAssociationActivity.kt"
        )
        val online = projectFile(
            "src/main/java/io/legado/app/ui/association/OnLineImportActivity.kt"
        )

        assertTrue(base.contains("map[\"type\"] == HighlightRuleFile.TYPE"))
        assertTrue(
            base.indexOf("map[\"type\"] == HighlightRuleFile.TYPE") <
                base.indexOf("map.containsKey(\"pattern\")")
        )
        assertTrue(file.contains("\"highlightRule\" -> showImportHighlightRuleDialog(it.second, true)"))
        assertTrue(online.contains("\"highlightRule\" -> showImportHighlightRuleDialog(it.second, true)"))
    }

    @Test
    fun `import is single flight and fragment observes view model state`() {
        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/association/ImportHighlightRuleViewModel.kt"
        )
        val dialog = projectFile(
            "src/main/java/io/legado/app/ui/association/ImportHighlightRuleDialog.kt"
        )

        assertTrue(viewModel.contains("if (importingLiveData.value == true) return"))
        assertTrue(viewModel.contains("importingLiveData.value = true"))
        assertFalse(viewModel.contains("fun importSelected(callback:"))
        assertTrue(dialog.contains("importingLiveData.observe(viewLifecycleOwner)"))
        assertTrue(dialog.contains("importSuccessLiveData.observe(viewLifecycleOwner)"))
        assertTrue(dialog.contains("supportFragmentManager.findFragmentByTag(tag) == null"))
    }

    private fun projectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("Missing project file: $pathInApp")

    private companion object {
        const val UUID_A = "11111111-1111-4111-8111-111111111111"
        const val UUID_B = "22222222-2222-4222-8222-222222222222"
        const val UUID_C = "33333333-3333-4333-8333-333333333333"
        const val UUID_D = "44444444-4444-4444-8444-444444444444"
    }
}
