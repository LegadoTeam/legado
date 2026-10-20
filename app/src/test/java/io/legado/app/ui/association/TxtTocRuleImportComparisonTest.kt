package io.legado.app.ui.association

import io.legado.app.data.entities.TxtTocRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtTocRuleImportComparisonTest {

    private val original = TxtTocRule(
        id = 42L,
        name = "Chapter headings",
        rule = "^Chapter (.+)$",
        replacement = "$1",
        example = "Chapter One",
        serialNumber = 3,
        enable = true,
    )

    @Test
    fun `new rules have no matching local content`() {
        assertFalse(original.hasSameImportContent(null))
    }

    @Test
    fun `identical copies remain existing without a default overwrite`() {
        assertTrue(original.hasSameImportContent(original.copy()))
        assertTrue(original.copy(example = null).hasSameImportContent(original.copy(example = null)))
    }

    @Test
    fun `each persisted field change requires an update`() {
        val changes = mapOf(
            "id" to original.copy(id = 43L),
            "name" to original.copy(name = "Updated headings"),
            "rule" to original.copy(rule = "^Part (.+)$"),
            "replacement" to original.copy(replacement = "Part $1"),
            "example" to original.copy(example = "Chapter Two"),
            "serialNumber" to original.copy(serialNumber = 4),
            "enable" to original.copy(enable = false),
        )

        changes.forEach { (field, changed) ->
            assertFalse("Changed $field must be imported", changed.hasSameImportContent(original))
            assertFalse("Comparison must be symmetric for $field", original.hasSameImportContent(changed))
        }
    }

    @Test
    fun `removing an example and explicitly empty examples remain content changes`() {
        assertFalse(original.copy(example = null).hasSameImportContent(original))
        assertFalse(original.copy(example = "").hasSameImportContent(original.copy(example = null)))
    }

    @Test
    fun `changed imports keep the same selection identity`() {
        val changed = original.copy(rule = "^Part (.+)$", enable = false)
        val selected = hashSetOf(original)

        assertFalse(changed.hasSameImportContent(original))
        assertEquals(original, changed)
        assertTrue(selected.contains(changed))
        assertTrue(selected.remove(changed))
        assertTrue(selected.isEmpty())
    }
}
