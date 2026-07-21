package io.legado.app.ui.widget.dialog

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class VariableDialogLayoutTest {

    @Test
    fun variableInputUsesFloatingLabelAndCommentAreaKeepsCardInsets() {
        val document = parseProjectXml("src/main/res/layout/dialog_variable.xml")
        val variable = document.findElementById("@+id/tv_variable")
        val inputLayout = variable.parentNode as Element
        val scroll = document.getElementsByTagName("androidx.core.widget.NestedScrollView")
            .item(0) as Element
        val commentContent = scroll.firstElementChild()

        assertEquals("io.legado.app.ui.widget.text.TextInputLayout", inputLayout.tagName)
        assertEquals("variable", inputLayout.androidAttribute("hint"))
        assertEquals("16dp", inputLayout.androidAttribute("layout_marginHorizontal"))

        assertEquals("false", scroll.androidAttribute("clipToPadding"))
        assertEquals("6dp", scroll.androidAttribute("paddingTop"))
        assertEquals("12dp", scroll.androidAttribute("paddingBottom"))
        assertEquals("16dp", commentContent.androidAttribute("paddingHorizontal"))
        assertEquals(
            "3dp",
            document.findElementById("@+id/tool_bar").androidAttribute("layout_marginBottom")
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

    private fun Document.findElementById(id: String): Element {
        return getElementsByTagName("*").let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .single { it.androidAttribute("id") == id }
        }
    }

    private fun Element.firstElementChild(): Element {
        return childNodes.let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) }
                .filterIsInstance<Element>()
                .first()
        }
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(androidNamespace, name)

    private companion object {
        const val androidNamespace = "http://schemas.android.com/apk/res/android"
    }
}
