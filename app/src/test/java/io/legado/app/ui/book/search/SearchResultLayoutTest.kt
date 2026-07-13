package io.legado.app.ui.book.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SearchResultLayoutTest {

    @Test
    fun metadataRowsAreIndependentlyConstrained() {
        val document = searchLayout()

        assertEquals("@id/tv_name", view(document, "tv_author").topConstraint())
        assertEquals("@id/tv_author", view(document, "ll_kind").topConstraint())
        assertEquals("@id/ll_kind", view(document, "tv_lasted").topConstraint())
        assertEquals("@id/tv_lasted", view(document, "tv_introduce").topConstraint())
    }

    @Test
    fun rowUsesLogicalDirectionConstraintsForRtl() {
        val document = searchLayout()
        val cover = view(document, "iv_cover")
        val bookshelfIndicator = view(document, "iv_in_bookshelf")
        val originCount = view(document, "bv_originCount")

        assertEquals("parent", cover.appAttribute("layout_constraintStart_toStartOf"))
        assertEquals("@id/iv_cover", bookshelfIndicator.appAttribute("layout_constraintStart_toEndOf"))
        assertEquals("parent", originCount.appAttribute("layout_constraintEnd_toEndOf"))
        listOf(cover, bookshelfIndicator, originCount).forEach { view ->
            assertFalse(view.hasAttributeNS(APP_NS, "layout_constraintLeft_toLeftOf"))
            assertFalse(view.hasAttributeNS(APP_NS, "layout_constraintLeft_toRightOf"))
            assertFalse(view.hasAttributeNS(APP_NS, "layout_constraintRight_toRightOf"))
        }
    }

    @Test
    fun introductionCanGrowWithoutBeingBoundToCoverHeight() {
        val document = searchLayout()
        val introduction = view(document, "tv_introduce")
        val cover = view(document, "iv_cover")

        assertEquals("wrap_content", introduction.androidAttribute("layout_height"))
        assertEquals("3", introduction.androidAttribute("maxLines"))
        assertEquals("parent", introduction.appAttribute("layout_constraintBottom_toBottomOf"))
        assertEquals("0", cover.appAttribute("layout_constraintVertical_bias"))
    }

    private fun searchLayout() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(
        listOf(
            File("src/main/res/layout/item_search.xml"),
            File("app/src/main/res/layout/item_search.xml"),
        ).firstOrNull { it.isFile } ?: error("item_search.xml not found")
    )

    private fun view(document: org.w3c.dom.Document, id: String): Element {
        val nodes = document.getElementsByTagName("*")
        return (0 until nodes.length)
            .asSequence()
            .map { nodes.item(it) as Element }
            .first { it.androidAttribute("id") == "@+id/$id" }
    }

    private fun Element.topConstraint(): String =
        appAttribute("layout_constraintTop_toBottomOf")

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NS, name)

    private fun Element.appAttribute(name: String): String =
        getAttributeNS(APP_NS, name)

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val APP_NS = "http://schemas.android.com/apk/res-auto"
    }
}
