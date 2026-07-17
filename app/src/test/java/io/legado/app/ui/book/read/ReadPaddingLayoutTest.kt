package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ReadPaddingLayoutTest {

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
            "@+id/dsb_header_padding_top",
            "@+id/dsb_header_padding_bottom",
            "@+id/dsb_padding_top",
            "@+id/dsb_padding_bottom",
            "@+id/dsb_footer_padding_top",
            "@+id/dsb_footer_padding_bottom",
        )

        val horizontalPaddingIds = listOf(
            "@+id/dsb_header_padding_left",
            "@+id/dsb_header_padding_right",
            "@+id/dsb_padding_left",
            "@+id/dsb_padding_right",
            "@+id/dsb_footer_padding_left",
            "@+id/dsb_footer_padding_right",
        )
    }
}
