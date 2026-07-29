package io.legado.app.data.entities

import io.legado.app.help.HighlightStyle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightRuleTest {

    @Test
    fun `style cache follows direct json replacement`() {
        val rule = HighlightRule().apply {
            applyStyle(HighlightStyle(fill = 1))
        }
        assertEquals(1, rule.styleObj().fill)

        rule.style = GSON.toJson(HighlightStyle(fill = 2))

        assertEquals(2, rule.styleObj().fill)
    }

    @Test
    fun `oversized shadow remains editable and serializable`() {
        val rule = HighlightRule(style = """{"shadow":{"dx":-3.5e38}}""")

        val restored = rule.styleObj()
        assertTrue(restored.shadow?.dx?.isFinite() == true)
        rule.applyStyle(restored)

        assertEquals(
            restored,
            GSON.fromJsonObject<HighlightStyle>(rule.style).getOrThrow()
        )
    }

    @Test
    fun `invalid and dangling regex patterns are rejected`() {
        assertFalse(HighlightRule(pattern = "", isRegex = false).isValid())
        assertFalse(HighlightRule(pattern = "[", isRegex = true).isValid())
        assertTrue(HighlightRule(pattern = "a|", isRegex = true).isValid())
        assertTrue(HighlightRule(pattern = "a\\|", isRegex = true).isValid())
    }

}
