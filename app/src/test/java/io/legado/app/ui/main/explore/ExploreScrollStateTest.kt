package io.legado.app.ui.main.explore

import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreScrollStateTest {
    @Test fun openingKeepsTheClickedRowTop() {
        assertEquals((12 to 240) to 240, exploreScrollState(null, 12, 240))
    }

    @Test fun loadedExpansionReusesTheOriginalOffset() {
        assertEquals(null to 240, exploreScrollState(12 to 240, 12, 0))
    }

    @Test fun aDifferentRowStartsASeparateAnchor() {
        assertEquals((18 to 96) to 96, exploreScrollState(12 to 240, 18, 96))
    }
}
