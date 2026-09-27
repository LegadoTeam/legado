package io.legado.app.ui.book.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExploreCategoriesTest {
    @Test
    fun `rows preserve every category in order with at most three balanced rows`() {
        for (count in 0..150) {
            val categories = (0 until count).toList()
            val rows = splitExploreCategoryRows(categories)
            assertEquals(categories, rows.flatten())
            assertTrue(rows.size <= 3)
            assertTrue(rows.all { it.isNotEmpty() })
            if (rows.isNotEmpty()) {
                assertTrue(rows.maxOf { it.size } - rows.minOf { it.size } <= 1)
            }
        }
        assertEquals(listOf(8, 7, 7), splitExploreCategoryRows((0 until 22).toList()).map { it.size })
    }

    @Test
    fun `switching category invalidates both stale success and stale failure`() {
        val state = ExplorePaginationState()
        val oldRequest = state.startNextPage()
        assertTrue(state.isLoading)
        state.skipTo(1)
        assertFalse(state.isLoading)
        val newRequest = state.startNextPage()
        assertFalse(state.complete(oldRequest))
        assertFalse(state.fail(oldRequest))
        assertTrue(state.isActive(newRequest))
        assertTrue(state.complete(newRequest))
        assertEquals(2, state.nextPage)
    }

    @Test
    fun `preference is global default off and old backups reset missing key`() {
        val config = source("help/config/AppConfig.kt")
        assertTrue(config.contains("getPrefBoolean(PreferKey.showExploreCategories, false)"))
        val restore = source("help/storage/Restore.kt")
        assertTrue(restore.contains("if (PreferKey.showExploreCategories !in map)"))
        assertTrue(restore.contains("edit.putBoolean(PreferKey.showExploreCategories, false)"))
        assertFalse(source("help/storage/BackupConfig.kt").contains("PreferKey.showExploreCategories"))
    }

    @Test
    fun `recreation preserves selected category instead of reinitializing intent`() {
        val model = source("ui/book/explore/ExploreShowViewModel.kt")
        assertTrue(model.contains("if (initialized) return"))
        assertTrue(model.contains("savedState?.getString(\"exploreUrl\")"))
        assertTrue(model.contains("outState.putString(\"exploreUrl\", it.url)"))
        assertTrue(model.contains("outState.putInt(\"explorePage\", pageLiveData.value ?: firstLoadedPage)"))
        assertFalse(model.contains("addBooksData"))
    }

    @Test
    fun `category rows stay compact and top scroll restores first page indicator`() {
        val activity = source("ui/book/explore/ExploreShowActivity.kt")
        assertTrue(activity.contains("minimumHeight = 40.dpToPx()"))
        assertTrue(activity.contains("setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)"))
        assertTrue(activity.contains("viewModel.showPage(1)"))
        assertTrue(source("ui/book/explore/ExploreShowViewModel.kt").contains("fun showPage(page: Int)"))
    }

    private fun source(path: String): String = sequenceOf(
        File("src/main/java/io/legado/app/$path"),
        File("app/src/main/java/io/legado/app/$path"),
    ).first { it.isFile }.readText().replace("\r\n", "\n")
}
