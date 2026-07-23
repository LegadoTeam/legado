package io.legado.app.ui.menu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadBookPopupActionMigrationTest {

    @Test
    fun `reader long press menus use the shared vertical popup action menu`() {
        val source = readProjectFile(READ_BOOK_ACTIVITY)
        val changeMenu = section(
            source,
            "private fun showChangeSourceMenu(anchor: View)",
            "private fun showRefreshMenu(anchor: View)"
        )
        val refreshMenu = section(
            source,
            "private fun showRefreshMenu(anchor: View)",
            "private fun showBookChangeSource()"
        )

        assertFalse(source.contains("import androidx.appcompat.widget.PopupMenu"))
        assertFalse(source.contains("PopupMenu.OnMenuItemClickListener"))
        assertFalse(source.contains("PopupMenu(this, it)"))
        assertFalse(source.contains("applyOpenTint"))
        assertFalse(source.contains("inflate(R.menu.book_read_change_source)"))
        assertFalse(source.contains("inflate(R.menu.book_read_refresh)"))
        listOf(CHANGE_SOURCE_MENU, REFRESH_MENU).forEach { path ->
            assertFalse(
                "$path should be removed",
                sequenceOf(File(path), File("app/$path")).any(File::isFile)
            )
        }
        assertContains(source, "showChangeSourceMenu(it)")
        assertContains(source, "showRefreshMenu(it)")
        assertOrdered(
            changeMenu,
            "item(getString(R.string.chapter_change_source), \"chapter\")",
            "item(getString(R.string.book_change_source), \"book\")",
            "\"chapter\" -> showChapterChangeSource()",
            "\"book\" -> showBookChangeSource()"
        )
        assertOrdered(
            refreshMenu,
            "item(getString(R.string.menu_refresh_dur), \"dur\")",
            "item(getString(R.string.menu_refresh_after), \"after\")",
            "item(getString(R.string.menu_refresh_all), \"all\")",
            "\"dur\" -> refreshDurChapter()",
            "\"after\" -> refreshAfterChapters()",
            "\"all\" -> refreshAllChapters()"
        )
    }

    @Test
    fun `reader source actions preserve dialog inputs and menu dismissal`() {
        val source = readProjectFile(READ_BOOK_ACTIVITY)
        val book = section(
            source,
            "private fun showBookChangeSource()",
            "private fun showChapterChangeSource()"
        )
        val chapter = section(
            source,
            "private fun showChapterChangeSource()",
            "private fun refreshDurChapter()"
        )

        assertOrdered(
            book,
            "binding.readMenu.runMenuOut()",
            "ReadBook.book?.let",
            "ChangeBookSourceDialog(it.name, it.author)"
        )
        assertOrdered(
            chapter,
            "lifecycleScope.launch",
            "val book = ReadBook.book ?: return@launch",
            "appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)",
            "binding.readMenu.runMenuOut()",
            "ChangeChapterSourceDialog(book.name, book.author, chapter.index, chapter.title)"
        )
    }

    @Test
    fun `reader refresh actions preserve review reset and cache behavior`() {
        val source = readProjectFile(READ_BOOK_ACTIVITY)
        val current = section(
            source,
            "private fun refreshDurChapter()",
            "private fun refreshAfterChapters()"
        )
        val after = section(
            source,
            "private fun refreshAfterChapters()",
            "private fun refreshAllChapters()"
        )
        val all = section(
            source,
            "private fun refreshAllChapters()",
            "override fun onCompatOptionsItemSelected"
        )

        assertOrdered(
            current,
            "resetReviewSummaryState()",
            "if (ReadBook.bookSource == null)",
            "upContent()",
            "ReadBook.curTextChapter = null",
            "binding.readView.upContent()",
            "viewModel.refreshContentDur(it)"
        )
        assertOrdered(
            after,
            "resetReviewSummaryState()",
            "if (ReadBook.bookSource == null)",
            "upContent()",
            "ReadBook.clearTextChapter()",
            "binding.readView.upContent()",
            "viewModel.refreshContentAfter(it)"
        )
        assertOrdered(
            all,
            "if (ReadBook.bookSource == null)",
            "resetReviewSummaryState()",
            "upContent()",
            "refreshContentAll(it)"
        )
    }

    @Test
    fun `toolbar clicks route through the same reader actions`() {
        val source = readProjectFile(READ_BOOK_ACTIVITY)

        assertContains(source, "menu.iconItemOnLongClick(R.id.menu_change_source)")
        assertContains(source, "menu.iconItemOnLongClick(R.id.menu_refresh)")
        assertContains(source, "R.id.menu_change_source -> showBookChangeSource()")
        assertContains(source, "R.id.menu_refresh -> refreshDurChapter()")
        listOf(
            "R.id.menu_book_change_source",
            "R.id.menu_chapter_change_source",
            "R.id.menu_refresh_dur",
            "R.id.menu_refresh_after",
            "R.id.menu_refresh_all"
        ).forEach { assertFalse(source.contains(it)) }
    }

    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex + start.length)
        assertTrue("ReadBookActivity.kt should contain $start before $end", startIndex >= 0 && endIndex > startIndex)
        return source.substring(startIndex, endIndex)
    }

    private fun assertOrdered(source: String, vararg snippets: String) {
        var previous = -1
        snippets.forEach { snippet ->
            val current = source.indexOf(snippet, previous + 1)
            assertTrue("ReadBookActivity.kt should contain $snippet after the previous action", current > previous)
            previous = current
        }
    }

    private fun assertContains(source: String, expected: String) {
        assertTrue("ReadBookActivity.kt should contain $expected", source.contains(expected))
    }

    private fun readProjectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?.replace("\r\n", "\n")
            .orEmpty()

    private companion object {
        const val READ_BOOK_ACTIVITY = "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        const val CHANGE_SOURCE_MENU = "src/main/res/menu/book_read_change_source.xml"
        const val REFRESH_MENU = "src/main/res/menu/book_read_refresh.xml"
    }
}
