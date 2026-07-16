package io.legado.app.ui.book.info.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BookInfoEditLayoutTest {

    @Test
    fun `all editable content stays inside the bounded scroll viewport`() {
        val document = parse(LAYOUT_PATH)
        val root = document.documentElement
        val titleBar = document.elementById("title_bar")
        val scroller = document.elementsByTagName(SCROLL_VIEW).single()

        assertSame(root, titleBar.parentNode)
        assertSame(root, scroller.parentNode)
        assertEquals("match_parent", scroller.androidAttribute("layout_width"))
        assertEquals("0dp", scroller.androidAttribute("layout_height"))
        assertEquals("1", scroller.androidAttribute("layout_weight"))
        assertEquals("true", scroller.androidAttribute("fillViewport"))
        assertEquals(1, scroller.childElements().size)

        listOf(
            "iv_cover",
            "tie_book_name",
            "tie_book_author",
            "sp_type",
            "tie_cover_url",
            "tv_select_cover",
            "tv_change_cover",
            "tv_refresh_cover",
            "tie_book_intro"
        ).forEach { id ->
            assertTrue("$id must scroll with the form", document.elementById(id).hasAncestor(SCROLL_VIEW))
        }
    }

    @Test
    fun `cover header and actions fit constrained widths`() {
        val document = parse(LAYOUT_PATH)
        val bookFieldColumn = document.elementById("til_book_name").parentNode as Element

        assertEquals("0dp", bookFieldColumn.androidAttribute("layout_width"))
        assertEquals("1", bookFieldColumn.androidAttribute("layout_weight"))
        assertEquals("10dp", bookFieldColumn.androidAttribute("layout_marginStart"))

        listOf("tv_select_cover", "tv_change_cover", "tv_refresh_cover").forEach { id ->
            val action = document.elementById(id)
            assertEquals("0dp", action.androidAttribute("layout_width"))
            assertEquals("1", action.androidAttribute("layout_weight"))
            assertEquals("1", action.androidAttribute("maxLines"))
            assertEquals("48dp", action.androidAttribute("minHeight"))
            assertEquals("center", action.androidAttribute("gravity"))
            assertEquals("uniform", action.appAttribute("autoSizeTextType"))
            assertEquals("10sp", action.appAttribute("autoSizeMinTextSize"))
        }
    }

    @Test
    fun `introduction is visibly multiline and layout uses logical spacing`() {
        val document = parse(LAYOUT_PATH)
        val introduction = document.elementById("tie_book_intro")
        val source = File(repositoryRoot, LAYOUT_PATH).readText()

        assertEquals("4", introduction.androidAttribute("minLines"))
        assertEquals("top|start", introduction.androidAttribute("gravity"))
        assertFalse(source.contains("layout_marginLeft"))
        assertFalse(source.contains("layout_marginRight"))
        assertFalse(source.contains("RtlHardcoded"))
    }

    @Test
    fun `Chinese local image actions use compact labels`() {
        assertEquals("本地图片", stringValue("app/src/main/res/values-zh/strings.xml"))
        assertEquals("本機圖片", stringValue("app/src/main/res/values-zh-rTW/strings.xml"))
        assertEquals("本地圖片", stringValue("app/src/main/res/values-zh-rHK/strings.xml"))
    }

    private fun parse(path: String): Document =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File(repositoryRoot, path))

    private fun Document.elementById(id: String): Element =
        elementsByTagName("*").first { it.androidAttribute("id").endsWith("/$id") }

    private fun Document.elementsByTagName(name: String): List<Element> {
        val nodes = getElementsByTagName(name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()

    private fun Element.hasAncestor(name: String): Boolean =
        generateSequence(parentNode) { it.parentNode }
            .filterIsInstance<Element>()
            .any { it.tagName == name }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private fun Element.appAttribute(name: String): String =
        getAttributeNS(APP_NAMESPACE, name)

    private fun stringValue(path: String): String {
        val nodes = parse(path).getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .first { it.getAttribute("name") == "select_local_image" }
            .textContent
    }

    private val repositoryRoot: File by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }

    private companion object {
        const val LAYOUT_PATH = "app/src/main/res/layout/activity_book_info_edit.xml"
        const val SCROLL_VIEW = "io.legado.app.ui.widget.NoChildScrollNestedScrollView"
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"
    }
}
