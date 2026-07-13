package io.legado.app.ui.book.toc.rule

import io.legado.app.data.entities.TxtTocRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TxtTocRuleFilterTest {

    private val rules = listOf(
        TxtTocRule(id = 1, name = "正文卷", example = "第一章"),
        TxtTocRule(id = 2, name = "VIP章节", example = "Chapter 1"),
        TxtTocRule(id = 3, name = "番外", example = null),
    )

    @Test
    fun blankKeywordReturnsOriginalList() {
        assertSame(rules, rules.filterByKeyword("   "))
    }

    @Test
    fun nameMatchIgnoresCaseAndWhitespace() {
        assertEquals(listOf(2L), rules.filterByKeyword(" vip ").map { it.id })
    }

    @Test
    fun exampleCanBeSearched() {
        assertEquals(listOf(1L), rules.filterByKeyword("第一").map { it.id })
    }

    @Test
    fun missingExampleDoesNotMatchOrCrash() {
        assertEquals(emptyList<Long>(), rules.filterByKeyword("missing").map { it.id })
    }
}
