package io.legado.app.ui.book.read.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ReadStyleLineSpacingTest {

    @Test
    fun `seek bar exposes the requested range`() {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(projectFile("src/main/res/layout/dialog_read_book_style.xml"))
        val seekBars = document.getElementsByTagName(
            "io.legado.app.ui.widget.DetailSeekBar"
        )
        val lineSpacing = (0 until seekBars.length)
            .map { seekBars.item(it) as Element }
            .single { it.getAttribute("android:id") == "@+id/dsb_line_size" }

        assertEquals("50", lineSpacing.getAttribute("app:max"))
    }

    @Test
    fun `legacy values keep their effective line spacing`() {
        assertEquals(10, lineSpacingToProgress(0))
        assertEquals(22, lineSpacingToProgress(12))
        assertEquals(30, lineSpacingToProgress(20))
        assertEquals(12, lineSpacingFromProgress(lineSpacingToProgress(12)))
        assertEquals("0.2", lineSpacingDisplayValue(lineSpacingToProgress(12)))
    }

    @Test
    fun `new endpoints map to minus two through plus three`() {
        assertEquals(-10, lineSpacingFromProgress(0))
        assertEquals(40, lineSpacingFromProgress(50))
        assertEquals("-2.0", lineSpacingDisplayValue(0))
        assertEquals("3.0", lineSpacingDisplayValue(50))
    }

    @Test
    fun `progress and config values are clamped`() {
        assertEquals(0, lineSpacingToProgress(-100))
        assertEquals(50, lineSpacingToProgress(100))
        assertEquals(-10, lineSpacingFromProgress(-1))
        assertEquals(40, lineSpacingFromProgress(51))
    }

    @Test
    fun `pagination still consumes the stored multiplier`() {
        val provider = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/provider/ChapterProvider.kt"
        ).readText()
        val layout = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/provider/TextChapterLayout.kt"
        ).readText()

        assertTrue(provider.contains("lineSpacingExtra = ReadBookConfig.lineSpacingExtra / 10f"))
        assertTrue(layout.contains("durY += lineHeight * lineSpacingExtra"))
        assertTrue(layout.contains("durY += textHeight * lineSpacing"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .first { it.isFile }
    }
}
