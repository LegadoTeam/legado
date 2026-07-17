package io.legado.app.ui.book.toc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TocReverseNoDiffTest {

    @Test
    fun `full list replacement bypasses diff and suppresses intermediate callback`() {
        val adapter = source("app/src/main/java/io/legado/app/base/adapter/DiffRecyclerAdapter.kt")

        assertTrue(adapter.contains("fun setItemsNoDiff(items: List<ITEM>)"))
        assertTrue(adapter.contains("suppressNextListChange = true"))
        assertTrue(adapter.contains("asyncListDiffer.submitList(null)"))
        assertTrue(adapter.contains("asyncListDiffer.submitList(items.toMutableList())"))
    }

    @Test
    fun `only explicit table reorder selects no diff path`() {
        val activity = source("app/src/main/java/io/legado/app/ui/book/toc/TocActivity.kt")
        val fragment = source("app/src/main/java/io/legado/app/ui/book/toc/ChapterListFragment.kt")
        val viewModel = source("app/src/main/java/io/legado/app/ui/book/toc/TocViewModel.kt")

        assertTrue(activity.contains("replaceAll = true"))
        assertTrue(fragment.contains("if (replaceAll)"))
        assertTrue(fragment.contains("adapter.setItemsNoDiff(items)"))
        assertTrue(fragment.contains("adapter.setItems(items)"))
        assertTrue(viewModel.contains("replaceAll: Boolean = false"))
    }

    private fun source(relativePath: String): String {
        return File(repositoryRoot(), relativePath).readText()
    }

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
