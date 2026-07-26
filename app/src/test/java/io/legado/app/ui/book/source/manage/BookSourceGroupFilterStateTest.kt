package io.legado.app.ui.book.source.manage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSourceGroupFilterStateTest {

    @Test
    fun `only missing group queries are invalidated`() {
        val groups = setOf("novel", "comic")

        assertFalse(isMissingBookSourceGroupFilter(null, groups))
        assertFalse(isMissingBookSourceGroupFilter("keyword", groups))
        assertFalse(isMissingBookSourceGroupFilter("group:novel", groups))
        assertTrue(isMissingBookSourceGroupFilter("group:deleted", groups))
        assertTrue(isMissingBookSourceGroupFilter("group:", emptySet()))
    }
}
