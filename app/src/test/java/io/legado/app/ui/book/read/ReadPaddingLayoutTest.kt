package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ReadPaddingLayoutTest {

    @Test
    fun `padding panel uses four shared controls`() {
        val document = readPaddingLayout()

        assertEquals(
            "androidx.core.widget.NestedScrollView",
            document.documentElement.tagName,
        )
        assertEquals("true", document.documentElement.androidAttribute("fillViewport"))
        assertEquals(
            4,
            document.getElementsByTagName("io.legado.app.ui.widget.DetailSeekBar").length,
        )
    }

    @Test
    fun `vertical padding supports the extended range`() {
        val document = readPaddingLayout()
        verticalPaddingIds.forEach { id ->
            assertEquals("400", document.findElementById(id).appAttribute("max"))
        }
    }

    @Test
    fun `horizontal padding keeps the compact range`() {
        val document = readPaddingLayout()
        horizontalPaddingIds.forEach { id ->
            assertEquals("100", document.findElementById(id).appAttribute("max"))
        }
    }

    @Test
    fun `region controls are equal and touch sized`() {
        val document = readPaddingLayout()
        regionButtonIds.forEach { id ->
            val button = document.findElementById(id)
            assertEquals("0dp", button.androidAttribute("layout_width"))
            assertEquals("1", button.androidAttribute("layout_weight"))
            assertEquals("48dp", button.androidAttribute("layout_height"))
            assertEquals("true", button.androidAttribute("clickable"))
            assertEquals("true", button.androidAttribute("focusable"))
        }

        val reset = document.findElementById("@+id/iv_reset")
        assertEquals("48dp", reset.androidAttribute("layout_width"))
        assertEquals("48dp", reset.androidAttribute("layout_height"))
        assertEquals("@string/restore_default", reset.androidAttribute("contentDescription"))
    }

    @Test
    fun `binary options use accessible switches`() {
        val document = readPaddingLayout()
        val lock = document.findElementById("@+id/sw_lock_lr")
        assertEquals("@string/lock_left_right_padding", lock.androidAttribute("text"))
        assertEquals("48dp", lock.androidAttribute("minHeight"))
        val showLine = document.findElementById("@+id/sw_show_line")
        assertEquals("@string/showLine", showLine.androidAttribute("text"))
        assertEquals("48dp", showLine.androidAttribute("minHeight"))
    }

    private fun readPaddingLayout(): Document {
        val path = "src/main/res/layout/dialog_read_padding.xml"
        val file = listOf(File(path), File("app/$path"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $path")
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(file)
    }

    private fun Document.findElementById(id: String): Element {
        return getElementsByTagName("*").let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .single { it.androidAttribute("id") == id }
        }
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(androidNamespace, name)

    private fun Element.appAttribute(name: String): String =
        getAttributeNS(appNamespace, name)

    private companion object {
        const val androidNamespace = "http://schemas.android.com/apk/res/android"
        const val appNamespace = "http://schemas.android.com/apk/res-auto"

        val verticalPaddingIds = listOf(
            "@+id/dsb_top",
            "@+id/dsb_bottom",
        )

        val horizontalPaddingIds = listOf(
            "@+id/dsb_left",
            "@+id/dsb_right",
        )

        val regionButtonIds = listOf(
            "@+id/btn_region_header",
            "@+id/btn_region_body",
            "@+id/btn_region_footer",
        )
    }
}
