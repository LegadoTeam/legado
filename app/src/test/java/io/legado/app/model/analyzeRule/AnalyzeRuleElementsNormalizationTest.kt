package io.legado.app.model.analyzeRule

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyzeRuleElementsNormalizationTest {

    @Test
    fun javascriptArrayResultsBecomeUsableElementLists() {
        val analyzeRule = AnalyzeRule().setContent("ignored")

        assertEquals(
            listOf(1, 2.0),
            analyzeRule.getElements("@js:[1, null, 2]"),
        )
    }
}
