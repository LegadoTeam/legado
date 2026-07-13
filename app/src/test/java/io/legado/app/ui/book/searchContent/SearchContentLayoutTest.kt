package io.legado.app.ui.book.searchContent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SearchContentLayoutTest {

    @Test
    fun searchResultTextIsConstrainedAcrossParentWidth() {
        val view = searchResultView()

        assertEquals("0dp", view.getAttributeNS(ANDROID_NS, "layout_width"))
        assertEquals("parent", view.getAttributeNS(APP_NS, "layout_constraintStart_toStartOf"))
        assertEquals("parent", view.getAttributeNS(APP_NS, "layout_constraintEnd_toEndOf"))
        assertFalse(view.hasAttributeNS(APP_NS, "layout_constraintRight_toLeftOf"))
    }

    private fun searchResultView(): Element {
        val layout = listOf(
            File("src/main/res/layout/item_search_list.xml"),
            File("app/src/main/res/layout/item_search_list.xml"),
        ).firstOrNull { it.isFile } ?: error("item_search_list.xml not found")
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(layout)
        val textViews = document.getElementsByTagName("TextView")
        return (0 until textViews.length)
            .asSequence()
            .map { textViews.item(it) as Element }
            .first { it.getAttributeNS(ANDROID_NS, "id") == "@+id/tv_search_result" }
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val APP_NS = "http://schemas.android.com/apk/res-auto"
    }
}
