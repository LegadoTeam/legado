package io.legado.app.ui.book.read.config

import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class TipTextSizeTest {

    @Test
    fun `legacy config keeps the previous twelve sp size`() {
        val config = GSON.fromJsonObject<ReadBookConfig.Config>("{}").getOrThrow()

        assertEquals(12, config.tipTextSize)
    }

    @Test
    fun `text size survives json and map export`() {
        val config = ReadBookConfig.Config(tipTextSize = 24)
        val restored = GSON.fromJsonObject<ReadBookConfig.Config>(
            GSON.toJson(config)
        ).getOrThrow()

        assertEquals(24, restored.tipTextSize)
        assertEquals(24, config.toMap()["tipTextSize"])
    }

    @Test
    fun `text size maps to seek bar progress`() {
        assertEquals(0, tipTextSizeToProgress(5))
        assertEquals(7, tipTextSizeToProgress(12))
        assertEquals(45, tipTextSizeToProgress(50))
        assertEquals(0, tipTextSizeToProgress(4))
        assertEquals(45, tipTextSizeToProgress(51))
    }

    @Test
    fun `seek bar progress maps to text size`() {
        assertEquals(5, tipTextSizeFromProgress(0))
        assertEquals(12, tipTextSizeFromProgress(7))
        assertEquals(50, tipTextSizeFromProgress(45))
        assertEquals(5, tipTextSizeFromProgress(-1))
        assertEquals(50, tipTextSizeFromProgress(46))
    }

    @Test
    fun `shared layout and reader info use text size`() {
        val config = readProjectFile("src/main/java/io/legado/app/help/config/ReadBookConfig.kt")
        val pageView = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/PageView.kt")

        assertTrue(config.contains("exportConfig.tipTextSize = shareConfig.tipTextSize"))
        assertTrue(pageView.contains("textSize = ReadTipConfig.tipTextSize.toFloat()"))
    }

    @Test
    fun `text size seek bar exposes the mapped range`() {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(projectFile("src/main/res/layout/dialog_tip_config.xml"))
        val seekBars = document.getElementsByTagName(
            "io.legado.app.ui.widget.DetailSeekBar"
        )
        val textSize = (0 until seekBars.length)
            .map { seekBars.item(it) as Element }
            .single { it.getAttribute("android:id") == "@+id/dsb_tip_text_size" }

        assertEquals("45", textSize.getAttribute("app:max"))
    }

    private fun readProjectFile(pathInApp: String): String {
        return projectFile(pathInApp).readText()
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).first { it.isFile }
    }
}
