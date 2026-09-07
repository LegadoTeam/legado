package io.legado.app.ui.book.manage

import io.legado.app.utils.mergeFilteredOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookshelfManageOrderTest {

    @Test
    fun `filtered drag preserves hidden slots and uses current rows`() {
        val allItems = listOf(
            Item("a", "a-latest"),
            Item("x", "x-latest"),
            Item("b", "b-latest"),
            Item("y", "y-latest"),
        )
        val orderedItems = listOf(
            Item("missing", "missing"),
            Item("y", "y-stale"),
            Item("x", "x-stale"),
            Item("y", "y-duplicate"),
        )

        val result = mergeFilteredOrder(allItems, orderedItems) { it.key }

        assertEquals(
            listOf("a-latest", "y-latest", "b-latest", "x-latest"),
            result.map(Item::value),
        )
    }

    @Test
    fun `bookshelf drag writes only shelf order fields`() {
        val dao = projectFile("src/main/java/io/legado/app/data/dao/BookDao.kt").readText()
        val adapter = projectFile(
            "src/main/java/io/legado/app/ui/book/manage/BookAdapter.kt"
        ).readText()
        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/book/manage/BookshelfManageViewModel.kt"
        ).readText().substringAfter("fun updateBookOrder").substringBefore("fun deleteBook")

        assertTrue(
            dao.contains(
                "SELECT * FROM books WHERE type & \${BookType.notShelf} = 0 ORDER BY `order`"
            )
        )
        assertTrue(dao.contains("UPDATE books SET `order` = :order WHERE bookUrl = :bookUrl"))
        assertTrue(adapter.contains("callBack.updateBookOrder(getItems(), needsOrderReset)"))
        assertFalse(adapter.contains("callBack.updateBook(*getItems().toTypedArray())"))
        assertTrue(viewModel.contains("appDb.runInTransaction"))
        assertTrue(viewModel.contains("appDb.bookDao.allShelfByOrder"))
        assertTrue(viewModel.contains("appDb.bookDao.updateOrder(reordered)"))
        assertFalse(viewModel.contains("updatePreservingCustomCoverUrl"))
    }

    private data class Item(val key: String, val value: String)

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp")).first { it.isFile }
}
