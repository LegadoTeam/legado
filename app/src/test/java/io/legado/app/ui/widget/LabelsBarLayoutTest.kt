package io.legado.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LabelsBarLayoutTest {

    @Test
    fun `book details wrap and center labels without a horizontal scroller`() {
        listOf(
            "app/src/main/res/layout/activity_book_info.xml",
            "app/src/main/res/layout-land/activity_book_info.xml"
        ).forEach { path ->
            val labels = labelsBar(path, "lb_kind")

            assertEquals("match_parent", labels.androidAttribute("layout_width"))
            assertEquals("row", labels.appAttribute("flexDirection"))
            assertEquals("wrap", labels.appAttribute("flexWrap"))
            assertEquals("center", labels.appAttribute("justifyContent"))
            assertEquals("center", labels.appAttribute("alignItems"))
            assertFalse(labels.hasAncestor("HorizontalScrollView"))
        }
    }

    @Test
    fun `search and explore results keep labels on one line`() {
        val labels = labelsBar("app/src/main/res/layout/item_search.xml", "ll_kind")

        assertEquals("0dp", labels.androidAttribute("layout_width"))
        assertEquals("row", labels.appAttribute("flexDirection"))
        assertEquals("nowrap", labels.appAttribute("flexWrap"))
        assertEquals("flex_start", labels.appAttribute("justifyContent"))
        assertEquals("center", labels.appAttribute("alignItems"))
        assertEquals("@id/tv_author", labels.appAttribute("layout_constraintTop_toBottomOf"))
    }

    @Test
    fun `labels use flexbox and logical spacing`() {
        val source = File(
            repositoryRoot,
            "app/src/main/java/io/legado/app/ui/widget/LabelsBar.kt"
        ).readText()

        assertTrue(source.contains(": FlexboxLayout(context, attrs)"))
        assertTrue(source.contains("FlexboxLayout.LayoutParams("))
        assertTrue(source.contains("marginEnd ="))
        assertTrue(source.contains("flexWrap == FlexWrap.NOWRAP"))
        assertFalse(source.contains("setMargins("))
        assertFalse(source.contains("rightMargin"))
    }

    private fun labelsBar(path: String, id: String): Element {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File(repositoryRoot, path))
        val nodes = document.getElementsByTagName(LABELS_BAR)
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .first { it.androidAttribute("id").endsWith("/$id") }
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private fun Element.appAttribute(name: String): String =
        getAttributeNS(APP_NAMESPACE, name)

    private fun Element.hasAncestor(name: String): Boolean =
        generateSequence(parentNode) { it.parentNode }
            .any { it.nodeName.endsWith(name) }

    private val repositoryRoot: File by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }

    private companion object {
        const val LABELS_BAR = "io.legado.app.ui.widget.LabelsBar"
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"
    }
}
