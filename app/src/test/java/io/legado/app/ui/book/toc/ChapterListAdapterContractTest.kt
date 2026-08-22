package io.legado.app.ui.book.toc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
}
