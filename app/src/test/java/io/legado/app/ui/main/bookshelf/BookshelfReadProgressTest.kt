package io.legado.app.ui.main.bookshelf

import io.legado.app.data.entities.Book
import io.legado.app.help.book.readProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BookshelfReadProgressTest {

    @Test
    fun `unopened books do not expose progress`() {
        assertNull(Book(totalChapterNum = 20).readProgress())
    }

    @Test
    fun `single chapter books become complete after reading starts`() {
        val book = Book(totalChapterNum = 1, durChapterPos = 1)
        assertEquals(1f, book.readProgress() ?: 0f, 0f)
    }

    @Test
    fun `chapter index maps to bounded progress`() {
        assertEquals(
            0.5f,
            Book(totalChapterNum = 11, durChapterIndex = 5).readProgress() ?: 0f,
            0f,
        )
        assertEquals(
            1f,
            Book(totalChapterNum = 10, durChapterIndex = 20).readProgress() ?: 0f,
            0f,
        )
    }

    @Test
    fun `all book item layouts provide hidden progress views`() {
        bookItemLayouts.forEach { layout ->
            val document = parseProjectXml("src/main/res/layout/$layout")
            val progress = document.findElementById("@+id/pb_read_progress")
            assertEquals("gone", progress.androidAttribute("visibility"))
            assertEquals("2dp", progress.appAttribute("trackThickness"))

            val percent = document.findElementsById("@+id/tv_read_percent")
            if (layout.startsWith("item_bookshelf_list")) {
                assertEquals(1, percent.size)
                assertEquals("gone", percent.single().androidAttribute("visibility"))
            } else {
                assertTrue(percent.isEmpty())
            }
        }
    }

    @Test
    fun `group layouts remain free of per-book progress`() {
        groupLayouts.forEach { layout ->
            val document = parseProjectXml("src/main/res/layout/$layout")
            assertFalse(document.findElementsById("@+id/pb_read_progress").isNotEmpty())
        }
    }

    @Test
    fun `bookshelf settings expose the progress switch in order`() {
        val document = parseProjectXml("src/main/res/layout/dialog_bookshelf_config.xml")
        val progressSwitch = document.findElementById("@+id/sw_show_read_progress")
        val waitSwitch = document.findElementById("@+id/sw_show_wait_up_books")

        assertEquals("@string/show_read_progress", progressSwitch.androidAttribute("text"))
        assertEquals(
            "@+id/sw_show_last_update_time",
            progressSwitch.appAttribute("layout_constraintTop_toBottomOf"),
        )
        assertEquals(
            "@+id/sw_show_read_progress",
            waitSwitch.appAttribute("layout_constraintTop_toBottomOf"),
        )
    }

    private fun parseProjectXml(pathInApp: String): Document {
        val file = listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(file)
    }

    private fun Document.findElementById(id: String): Element =
        findElementsById(id).single()

    private fun Document.findElementsById(id: String): List<Element> {
        return getElementsByTagName("*").let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .filter { it.androidAttribute("id") == id }
        }
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(androidNamespace, name)

    private fun Element.appAttribute(name: String): String =
        getAttributeNS(appNamespace, name)

    private companion object {
        const val androidNamespace = "http://schemas.android.com/apk/res/android"
        const val appNamespace = "http://schemas.android.com/apk/res-auto"

        val bookItemLayouts = listOf(
            "item_bookshelf_grid.xml",
            "item_bookshelf_grid2.xml",
            "item_bookshelf_list.xml",
            "item_bookshelf_list2.xml",
        )

        val groupLayouts = listOf(
            "item_bookshelf_grid_group.xml",
            "item_bookshelf_grid_group2.xml",
            "item_bookshelf_list_group.xml",
        )
    }
}
