package io.legado.app.ui.book.changesource

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangeSourceResultOptionsWiringTest {

    @Test
    fun `both change source dialogs share result option controls`() {
        val menu = projectFile("src/main/res/menu/change_source.xml").readText()
        assertTrue(menu.contains("@+id/menu_sort_respond_time"))
        assertTrue(menu.contains("@+id/menu_word_count_filter"))

        DIALOGS.forEach { path ->
            val source = projectFile(path).readText()
            assertTrue(path, source.contains("R.id.menu_sort_respond_time"))
            assertTrue(path, source.contains("R.id.menu_word_count_filter"))
            assertTrue(path, source.contains("syncChangeSourceResultOptions()"))
            assertTrue(path, source.contains("showChangeSourceWordCountFilter"))
        }
    }

    @Test
    fun `display and automatic source selection use the same result policy`() {
        val source = projectFile(VIEW_MODEL).readText()

        assertTrue(source.contains("ChangeSourceResultOptions.apply("))
        assertTrue(source.contains("currentResults().forEach"))
    }

    @Test
    fun `only chapter source results pin the current book`() {
        val bookViewModel = projectFile(VIEW_MODEL).readText()
        val chapterViewModel = projectFile(CHAPTER_VIEW_MODEL).readText()

        assertTrue(bookViewModel.contains("protected open val pinCurrentSource = false"))
        assertTrue(
            bookViewModel.contains(
                "pinnedBookUrl = if (pinCurrentSource) oldBook?.bookUrl else null"
            )
        )
        assertTrue(chapterViewModel.contains("protected override val pinCurrentSource = true"))
    }

    @Test
    fun `both adapters refresh measured result fields`() {
        ADAPTERS.forEach { path ->
            val source = projectFile(path).readText()
            assertTrue(path, source.contains("oldItem.chapterWordCountText == newItem.chapterWordCountText"))
            assertTrue(path, source.contains("oldItem.respondTime == newItem.respondTime"))
        }
    }

    @Test
    fun `result options reuse cached searches without changing the base preference`() {
        val viewModel = projectFile(VIEW_MODEL).readText()
        val appConfig = projectFile(APP_CONFIG).readText()

        assertTrue(viewModel.contains("AppConfig.changeSourceLoadWordCount -> startRefreshList(true)"))
        assertTrue(viewModel.contains("operationState.reserveMeasurementRefresh("))
        assertTrue(viewModel.contains("operationPreparation.withLock"))
        assertTrue(viewModel.contains("operationState.startTaskIfCurrent(operation)"))
        assertTrue(viewModel.contains("finishPreparingOperation(operation)"))
        assertTrue(viewModel.contains("refreshPendingMeasurements(operation)"))
        assertTrue(viewModel.contains("refreshList(books, operation)"))
        assertFalse(viewModel.contains("private var searchBookList"))
        assertFalse(viewModel.contains("searchBooks.isEmpty() || AppConfig.changeSourceLoadWordCount"))
        assertFalse(appConfig.contains("if (value) changeSourceLoadWordCount = true"))
        assertFalse(appConfig.contains("if (mode != 0) changeSourceLoadWordCount = true"))
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
    }

    companion object {
        private const val VIEW_MODEL =
            "src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt"
        private const val CHAPTER_VIEW_MODEL =
            "src/main/java/io/legado/app/ui/book/changesource/ChangeChapterSourceViewModel.kt"
        private const val APP_CONFIG = "src/main/java/io/legado/app/help/config/AppConfig.kt"
        private val DIALOGS = listOf(
            "src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceDialog.kt",
            "src/main/java/io/legado/app/ui/book/changesource/ChangeChapterSourceDialog.kt",
        )
        private val ADAPTERS = listOf(
            "src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceAdapter.kt",
            "src/main/java/io/legado/app/ui/book/changesource/ChangeChapterSourceAdapter.kt",
        )
    }
}
