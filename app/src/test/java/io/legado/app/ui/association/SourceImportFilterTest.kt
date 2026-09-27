package io.legado.app.ui.association

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceImportFilterTest {
    @Test fun `filters source metadata without changing original candidate positions`() {
        val names = listOf("Same", "Other", "Same")
        val urls = listOf("https://a.invalid", "https://b.invalid", "https://c.invalid")
        fun matches(query: String) = names.indices.filter {
            matchesSourceImportSearch(query, names[it], urls[it],
                if (it == 2) "小说, RSS" else null, if (it == 1) "中文注释" else null)
        }
        assertEquals(listOf(0, 2), matches("same"))
        assertEquals(listOf(2), matches("C.INVALID"))
        assertEquals(listOf(1), matches("中文"))
        assertEquals(listOf(2), matches("rss"))
        assertEquals(listOf(2), matches("group:RSS"))
        assertEquals(emptyList<Int>(), matches("group:rss"))
        assertEquals(emptyList<Int>(), matches("group:RS"))
        assertEquals(names.indices.toList(), matches(""))
        assertFalse(matchesSourceImportSearch("anything", null, null, null, null))
        assertTrue(matchesSourceImportSearch("[", "literal [ bracket", null, null, null))
    }
}
