package io.legado.app.ui.book.source.manage

import io.legado.app.utils.moveRelativeTo
import org.junit.Assert.assertEquals
import org.junit.Test

class BookSourceManageOrderTest {
    @Test fun `drag relocates only the dragged item across hidden rows`() {
        val items = listOf("a", "hidden1", "b", "hidden2", "c", "hidden3")
            .mapIndexed { index, key -> Item(key, if (index < 3) 0 else index * 100, "metadata-$key") }
        fun moved(key: String, target: String, after: Boolean) =
            moveRelativeTo(items, key, target, after) { it.key }
        assertEquals(listOf("hidden1", "b", "hidden2", "c", "a", "hidden3"),
            moved("a", "c", true).map { it.key })
        assertEquals(listOf("c", "a", "hidden1", "b", "hidden2", "hidden3"),
            moved("c", "a", false).map { it.key })
        // In a descending view, dropping c below b means placing c before b globally.
        assertEquals(listOf("a", "hidden1", "c", "b", "hidden2", "hidden3"),
            moved("c", "b", false).map { it.key })
        assertEquals(items.filter { it.key != "a" }, moved("a", "c", true).filter { it.key != "a" })
        assertEquals(items.associateBy { it.key }, moved("a", "c", true).associateBy { it.key })
        assertEquals(items, moved("a", "a", true))
        assertEquals(items, moved("missing", "c", true))
        assertEquals(items, moved("a", "missing", false))
        assertEquals(listOf("a", "hidden1", "b", "hidden2", "c", "hidden3"), items.map { it.key })
    }

    private data class Item(val key: String, val order: Int, val metadata: String)
}
