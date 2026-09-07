package io.legado.app.ui.book.toc

import com.google.gson.JsonObject
import io.legado.app.data.dao.withTocExpanded
import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TocExpansionPreferenceTest {

    @Test
    fun `toc expansion preference defaults and round trips`() {
        assertTrue(Book.ReadConfig().tocExpanded)
        assertTrue(GSON.fromJsonObject<Book.ReadConfig>("{}").getOrThrow().tocExpanded)

        val book = Book(readConfig = Book.ReadConfig())
        assertTrue(book.getTocExpanded())
        book.setTocExpanded(false)
        assertFalse(book.getTocExpanded())

        val restored = GSON.fromJsonObject<Book.ReadConfig>(
            GSON.toJson(book.readConfig)
        ).getOrThrow()
        assertFalse(restored.tocExpanded)
    }

    @Test
    fun `toc expansion update preserves other read config fields`() {
        val updated = GSON.fromJsonObject<JsonObject>(
            """{"futureOption":"keep","reverseToc":true}"""
        ).getOrThrow().toString().withTocExpanded(false)
        val json = GSON.fromJsonObject<JsonObject>(updated).getOrThrow()

        assertFalse(json.get("tocExpanded").asBoolean)
        assertTrue(json.get("reverseToc").asBoolean)
        assertTrue(json.get("futureOption").asString == "keep")
    }

    @Test
    fun `toc expansion toggle is wired through menu and activity`() {
        val menu = projectFile("src/main/res/menu/book_toc.xml").readText()
        assertTrue(menu.contains("@+id/menu_expand_toc"))
        assertTrue(menu.contains("android:checkable=\"true\""))
        assertTrue(menu.contains("@string/expand_toc"))

        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/toc/TocActivity.kt"
        ).readText()
        assertTrue(activity.contains("getTocExpanded()"))
        assertTrue(activity.contains("R.id.menu_expand_toc"))
        assertTrue(activity.contains("viewModel.setTocExpanded(expanded)"))

        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/book/toc/TocViewModel.kt"
        ).readText()
        assertTrue(viewModel.contains("book.setTocExpanded(expanded)"))
        assertTrue(viewModel.contains("bookDao.updateTocExpanded(book.bookUrl, expanded)"))
        assertTrue(viewModel.contains("resetCollapse = true"))
        val updateBlock = viewModel.substringAfter("fun setTocExpanded")
            .substringBefore("fun startChapterListSearch")
        assertFalse(updateBlock.contains("book.update()"))

        val dao = projectFile("src/main/java/io/legado/app/data/dao/BookDao.kt").readText()
        assertTrue(dao.contains("fun updateTocExpanded(bookUrl: String, expanded: Boolean)"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
