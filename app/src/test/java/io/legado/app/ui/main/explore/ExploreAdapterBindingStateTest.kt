package io.legado.app.ui.main.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExploreAdapterBindingStateTest {

    @Test
    fun `only the latest matching explore binding accepts async results`() {
        assertTrue(isExploreBindingCurrent(2, 2, 3, 3, "source", "source"))
        assertFalse(isExploreBindingCurrent(1, 2, 3, 3, "source", "source"))
        assertFalse(isExploreBindingCurrent(2, 2, 3, 4, "source", "source"))
        assertFalse(isExploreBindingCurrent(2, 2, 3, 3, "source", "other"))
        assertFalse(isExploreBindingCurrent(2, 2, 3, 3, "source", null))
    }

    @Test
    fun `empty explore kinds clear old controls before returning`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/main/explore/ExploreAdapter.kt"
        ).substringAfter("private fun upKindList").substringBefore("@Synchronized")
        val recycleIndex = source.indexOf("recyclerFlexbox(flexbox)")
        val hideIndex = source.indexOf("flexbox.gone()")
        val emptyIndex = source.indexOf("if (kinds.isEmpty())")

        assertTrue(recycleIndex >= 0 && recycleIndex < emptyIndex)
        assertTrue(hideIndex >= 0 && hideIndex < emptyIndex)
    }

    @Test
    fun `dynamic explore labels ignore callbacks after view reuse`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/main/explore/ExploreAdapter.kt"
        )

        assertEquals(5, source.countOccurrences("val viewNameToken = Any()"))
        assertEquals(5, source.countOccurrences("tag = viewNameToken"))
        assertEquals(10, source.countOccurrences("tag !== viewNameToken"))
    }

    private fun String.countOccurrences(value: String): Int =
        windowed(value.length).count { it == value }

    private fun readProjectFile(path: String): String =
        sequenceOf(File(path), File("app/$path"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
}
