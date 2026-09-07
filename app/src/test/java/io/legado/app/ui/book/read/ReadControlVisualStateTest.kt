package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ReadControlVisualStateTest {

    @Test
    fun chapterNavigationKeepsDisabledTextVisibleOnTheReaderBackground() {
        val source = readProjectFile("src/main/java/io/legado/app/ui/book/read/ReadMenu.kt")

        assertTrue(source.contains(".setDisabledColor(ColorUtils.withAlpha(textColor, 0.4f))"))
        assertTrue(source.contains("tvPre.setTextColor(chapterTextColor)"))
        assertTrue(source.contains("tvNext.setTextColor(chapterTextColor)"))
    }

    @Test
    fun runningReadAloudOpensControlsInsteadOfTogglingPlayback() {
        val source = readProjectFile("src/main/java/io/legado/app/ui/book/read/ReadMenu.kt")
        val clickBlock = source.substringAfter("llReadAloud.setOnClickListener")
            .substringBefore("llReadAloud.onLongClick")

        assertTrue(clickBlock.contains("if (BaseReadAloudService.isRun)"))
        assertTrue(clickBlock.contains("callBack.showReadAloudDialog()"))
        assertTrue(clickBlock.contains("else"))
        assertTrue(clickBlock.contains("callBack.onClickReadAloud()"))
    }

    @Test
    fun httpTtsDeleteActionIsVerticallyCentered() {
        val layout = parseXml("src/main/res/layout/item_http_tts.xml")
        val delete = layout.getElementsByTagName("*").asSequence()
            .filterIsInstance<Element>()
            .first { it.androidAttribute("id") == "@+id/iv_menu_delete" }

        assertEquals("parent", delete.appAttribute("layout_constraintTop_toTopOf"))
        assertEquals("parent", delete.appAttribute("layout_constraintBottom_toBottomOf"))
        assertEquals("", delete.appAttribute("layout_constraintVertical_bias"))
    }

    private fun parseXml(pathInApp: String): Element =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(projectFile(pathInApp)).documentElement

    private fun readProjectFile(pathInApp: String): String = projectFile(pathInApp).readText()

    private fun projectFile(pathInApp: String): File = sequenceOf(
        File(pathInApp),
        File("app/$pathInApp")
    ).first(File::isFile)

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private fun Element.appAttribute(name: String): String =
        getAttributeNS(APP_NAMESPACE, name)

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<org.w3c.dom.Node> = sequence {
        for (index in 0 until length) yield(item(index))
    }

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        private const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"
    }
}
