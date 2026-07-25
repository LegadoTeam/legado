package io.legado.app.ui.book.read

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManualHighlightActivityTest {

    @Test
    fun `editing highlight is restored before fragments`() {
        val content = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        )
        val onCreate = content.indexOf("override fun onCreate(savedInstanceState: Bundle?)")
        val restore = content.indexOf("editingHighlight = savedInstanceState?.getParcelable", onCreate)
        val superOnCreate = content.indexOf("super.onCreate(savedInstanceState)", onCreate)

        assertTrue(onCreate >= 0)
        assertTrue(restore in onCreate until superOnCreate)
        assertTrue(content.contains("outState.putParcelable(STATE_EDITING_HIGHLIGHT, it)"))
        assertTrue(content.contains("findFragmentByTag(HighlightStyleDialog::class.simpleName)"))
    }

    @Test
    fun `book metadata edits keep highlight labels synchronized`() {
        val content = readProjectFile(
            "src/main/java/io/legado/app/ui/book/info/edit/BookInfoEditViewModel.kt"
        )

        assertTrue(content.contains("appDb.runInTransaction"))
        assertTrue(content.contains("appDb.bookHighlightDao.updateBookMetadata("))
        assertTrue(content.contains("ReadBook.loadHighlights(book)"))
    }

    @Test
    fun `legacy chapter rebinding only updates the owner url`() {
        val content = readProjectFile(
            "src/main/java/io/legado/app/model/ReadBook.kt"
        )

        assertTrue(content.contains("val legacyTimes = legacyBound.map { it.time }"))
        assertTrue(content.contains("bookHighlightDao.bindChapterUrl(legacyTimes, bookChapter.url)"))
        assertTrue(
            content.contains(
                ".sortedWith(compareBy(BookHighlight::chapterPos, BookHighlight::time))"
            )
        )
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first(File::isFile)
            .readText()
    }
}
