package io.legado.app.ui.book.toc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ChapterListAdapterContractTest {

    @Test
    fun `display title cache uses the unique toc item key`() {
        val source = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
            .resolve("io/legado/app/ui/book/toc/ChapterListAdapter.kt")
            .readText()

        assertTrue(source.contains("displayTitleMap[item.key]"))
        assertTrue(source.contains("private fun getDisplayTitle(item: TocListItem)"))
        assertTrue(source.contains("getDisplayTitle(item)"))
        assertFalse(source.contains("displayTitleMap[chapter.title]"))
    }

    @Test
    fun `full chapter reload clears display title cache before resetting items`() {
        val source = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
            .resolve("io/legado/app/ui/book/toc/ChapterListFragment.kt")
            .readText()
        val initBook = source.substringAfter("private fun initBook(book: Book)")
            .substringBefore("private fun submitChapterItems")
        val clearCache = initBook.indexOf("adapter.clearDisplayTitle()")
        val resetItems = initBook.indexOf("adapter.setItems(emptyList())")

        assertTrue(clearCache in 0 until resetItems)
    }

    @Test
    fun `display title workers keep an isolated cache snapshot`() {
        val source = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
            .resolve("io/legado/app/ui/book/toc/ChapterListAdapter.kt")
            .readText()
        val clearCache = source.substringAfter("fun clearDisplayTitle()")
            .substringBefore("fun upDisplayTitles")
        val updateTitles = source.substringAfter("fun upDisplayTitles")
            .substringBefore("private suspend fun updateDisplayTitle")

        assertTrue(source.contains("@Volatile\n    private var displayTitleMap"))
        assertTrue(clearCache.contains("displayTitleMap = ConcurrentHashMap()"))
        assertFalse(clearCache.contains("displayTitleMap.clear()"))
        assertTrue(
            updateTitles.indexOf("val displayTitleMap = displayTitleMap") in
                    0 until updateTitles.indexOf("Coroutine.async")
        )
        assertTrue(source.contains("displayTitleMap: ConcurrentHashMap<String, String>"))
        assertTrue(source.contains("displayTitleMap === this.displayTitleMap"))
    }

    @Test
    fun `stale worker writes stay in the replaced cache`() {
        var activeCache = ConcurrentHashMap<String, String>()
        val staleWorkerCache = activeCache

        activeCache = ConcurrentHashMap()
        staleWorkerCache["chapter:0"] = "old title"

        assertNull(activeCache["chapter:0"])
    }
}
