package io.legado.app.ui.book.search

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchReadRecordContractTest {

    @Test
    fun `read records are observed so returning to the search page shows new ones`() {
        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/book/search/SearchViewModel.kt"
        ).readText().replace(Regex("\\s+"), " ")

        assertTrue(viewModel.contains("appDb.readRecordDao.flowBooks()"))
        assertTrue(viewModel.contains("distinctUntilChanged()"))
        assertTrue(viewModel.contains("""upAdapterLiveData.postValue("hasReadRecord")"""))
    }

    @Test
    fun `the indicator matches on author and can be turned off`() {
        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/book/search/SearchViewModel.kt"
        ).readText().replace(Regex("\\s+"), " ")

        assertTrue(viewModel.contains("if (!AppConfig.showSearchReadRecord) { return false }"))
        assertTrue(viewModel.contains("readRecordIndex.contains(book.name, book.author)"))
    }

    @Test
    fun `the search menu toggles the indicator and repaints the list`() {
        val menu = projectFile("src/main/res/menu/book_search.xml").readText()
        assertTrue(menu.contains("""android:id="@+id/menu_show_read_record""""))
        assertTrue(menu.contains("""android:title="@string/show_search_read_record""""))

        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/search/SearchActivity.kt"
        ).readText().replace(Regex("\\s+"), " ")
        assertTrue(
            activity.contains(
                "showReadRecordMenuItem?.isChecked = AppConfig.showSearchReadRecord"
            )
        )
        assertTrue(
            activity.contains(
                "AppConfig.showSearchReadRecord = !AppConfig.showSearchReadRecord"
            )
        )
        assertTrue(activity.contains("""viewModel.upAdapterLiveData.postValue("hasReadRecord")"""))
    }

    @Test
    fun `reading a book stores the author with the record`() {
        listOf(
            Triple("src/main/java/io/legado/app/model/ReadBook.kt",
                "val currentBook = book?.copy() ?: return", "getRecord(AppConst.androidId, book.name, book.author)"),
            Triple("src/main/java/io/legado/app/model/ReadManga.kt",
                "val currentBook = book?.copy() ?: return", "getRecord(AppConst.androidId, book.name, book.author)"),
        ).forEach { (path, authorCapture, authorAssignment) ->
            val source = projectFile(path).readText().replace(Regex("\\s+"), " ")
            assertTrue(path, source.contains(authorCapture))
            assertTrue(path, source.contains(authorAssignment))
        }

        val audioPlay = projectFile("src/main/java/io/legado/app/model/AudioPlay.kt")
            .readText().replace(Regex("\\s+"), " ")
        assertTrue(audioPlay.contains("book?.takeUnless(readTimeTracker::isForBook)?.let { resetReadRecord(it) }"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
