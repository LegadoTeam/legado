package io.legado.app.ui

import io.legado.app.data.entities.RssSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MovedItemsIdentityTest {

    @Test
    fun `mutable order changes do not duplicate a keyed moved item`() {
        val source = RssSource(sourceUrl = "https://example.com/rss")
        val originalHash = source.hashCode()
        val movedItems = linkedMapOf(source.sourceUrl to source)

        source.customOrder = 2

        assertNotEquals(originalHash, source.hashCode())
        movedItems[source.sourceUrl] = source
        assertEquals(1, movedItems.size)
    }

    @Test
    fun `drag adapters collect moved items by stable identity`() {
        val bookSource = projectFile(
            "src/main/java/io/legado/app/ui/book/source/manage/BookSourceAdapter.kt"
        ).readText()
        val rssSource = projectFile(
            "src/main/java/io/legado/app/ui/rss/source/manage/RssSourceAdapter.kt"
        ).readText()
        val ruleSub = projectFile(
            "src/main/java/io/legado/app/ui/rss/subscription/RuleSubAdapter.kt"
        ).readText()

        assertTrue(bookSource.contains("linkedMapOf<String, BookSourcePart>()"))
        assertTrue(bookSource.contains("movedItems[srcItem.bookSourceUrl] = srcItem"))
        assertFalse(bookSource.contains("private val movedItems = hashSetOf<BookSourcePart>()"))
        assertTrue(rssSource.contains("linkedMapOf<String, RssSource>()"))
        assertTrue(rssSource.contains("movedItems[srcItem.sourceUrl] = srcItem"))
        assertFalse(rssSource.contains("private val movedItems = hashSetOf<RssSource>()"))
        assertTrue(ruleSub.contains("linkedMapOf<Long, RuleSub>()"))
        assertTrue(ruleSub.contains("movedItems[srcItem.id] = srcItem"))
        assertFalse(ruleSub.contains("private val movedItems = hashSetOf<RuleSub>()"))
    }

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp")).first { it.isFile }
}
