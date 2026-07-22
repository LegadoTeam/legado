package io.legado.app.ui.book.toc

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BookmarkItemLayoutTest {

    @Test
    fun `bookmark item keeps bindings and card presentation`() {
        val root = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder()
            .parse(projectFile("src/main/res/layout/item_bookmark.xml"))
            .documentElement

        assertEquals("androidx.cardview.widget.CardView", root.tagName)
        assertEquals("10dp", root.androidAttribute("layout_marginStart"))
        assertEquals("3dp", root.androidAttribute("layout_marginTop"))
        assertEquals("10dp", root.androidAttribute("layout_marginEnd"))
        assertEquals("3dp", root.androidAttribute("layout_marginBottom"))
        assertEquals("?android:attr/selectableItemBackground", root.androidAttribute("foreground"))
        assertEquals("@color/background_card", root.appAttribute("cardBackgroundColor"))
        assertEquals("8dp", root.appAttribute("cardCornerRadius"))
        assertEquals("0dp", root.appAttribute("cardElevation"))

        val content = root.firstChildElement()
        assertEquals("LinearLayout", content.tagName)
        assertEquals("12dp", content.androidAttribute("padding"))

        val fields = content.childElements().associateBy { it.androidAttribute("id") }
        assertEquals(
            setOf("@+id/tv_chapter_name", "@+id/tv_book_text", "@+id/tv_content"),
            fields.keys
        )
        assertEquals("14sp", fields.getValue("@+id/tv_chapter_name").androidAttribute("textSize"))
        assertEquals("@color/primaryText", fields.getValue("@+id/tv_chapter_name").androidAttribute("textColor"))
        listOf("@+id/tv_book_text", "@+id/tv_content").forEach { id ->
            assertEquals("12sp", fields.getValue(id).androidAttribute("textSize"))
            assertEquals("@color/secondaryText", fields.getValue(id).androidAttribute("textColor"))
        }
        fields.values.forEach { field ->
            assertEquals("true", field.androidAttribute("singleLine"))
        }
    }

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(androidNamespace, name)

    private fun Element.appAttribute(name: String): String =
        getAttributeNS(appNamespace, name)

    private fun Element.firstChildElement(): Element = childElements().single()

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()

    private companion object {
        const val androidNamespace = "http://schemas.android.com/apk/res/android"
        const val appNamespace = "http://schemas.android.com/apk/res-auto"
    }
}
