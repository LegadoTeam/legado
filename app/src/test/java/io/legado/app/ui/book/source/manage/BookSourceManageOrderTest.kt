package io.legado.app.ui.book.source.manage

import io.legado.app.utils.mergeFilteredOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookSourceManageOrderTest {

    @Test
    fun `descending filtered drag preserves hidden slots`() {
        val allItems = listOf(Item("a"), Item("x"), Item("b"), Item("y"))
        val displayedItems = listOf(Item("x"), Item("y"))

        val result = mergeFilteredOrder(allItems, displayedItems.asReversed()) { it.key }

        assertEquals(listOf("a", "y", "b", "x"), result.map(Item::key))
    }

    @Test
    fun `duplicate order shared with hidden source is normalized`() {
        val allItems = listOf(Item("a", 0), Item("hidden", 1), Item("b", 1))
        val movedItems = listOf(Item("a", 1), Item("b", 0))

        val result = mergeFilteredOrder(
            allItems,
            movedItems.sortedBy(Item::order),
            Item::key,
        ).onEachIndexed { index, item -> item.order = index }

        assertEquals(listOf("b", "hidden", "a"), result.map(Item::key))
        assertEquals(listOf(0, 1, 2), result.map(Item::order))
    }

    @Test
    fun `duplicate source order resets from the full current list`() {
        val adapter = projectFile(
            "src/main/java/io/legado/app/ui/book/source/manage/BookSourceAdapter.kt"
        ).readText().substringAfter("override fun onClearView")
            .substringBefore("val dragSelectCallback")
        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/book/source/manage/BookSourceViewModel.kt"
        ).readText().substringAfter("fun upOrder").substringBefore("fun enable")

        assertTrue(adapter.contains("callBack.upOrder(if (resetAll) getItems()"))
        assertFalse(adapter.contains("getItems().mapIndexed"))
        assertTrue(viewModel.contains("appDb.runInTransaction"))
        assertTrue(viewModel.contains("resetAll || appDb.bookSourceDao.hasDuplicateOrder"))
        assertTrue(viewModel.contains("else -> items.sortedBy { it.customOrder }"))
        assertTrue(viewModel.contains("appDb.bookSourceDao.allPart"))
        assertTrue(viewModel.contains("resetAll && !sortAscending -> items.asReversed()"))
        assertTrue(viewModel.contains("source.customOrder = index"))
        assertTrue(viewModel.contains("appDb.bookSourceDao.upOrder(reordered)"))
    }

    private data class Item(val key: String, var order: Int = 0)

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp")).first { it.isFile }
}
