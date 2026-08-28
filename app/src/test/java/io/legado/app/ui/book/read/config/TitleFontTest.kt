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

class TitleFontTest {

    @Test
    fun `legacy config inherits the text font`() {
        val config = GSON.fromJsonObject<ReadBookConfig.Config>(
            """{"textFont":"body.ttf"}"""
        ).getOrThrow()

        assertEquals("", config.titleFont)
        assertEquals("", config.toMap()["titleFont"])
    }

    @Test
    fun `title font survives json round trip`() {
        val config = ReadBookConfig.Config(
            textFont = "body.ttf",
            titleFont = "title.ttf",
        )
        val restored = GSON.fromJsonObject<ReadBookConfig.Config>(
            GSON.toJson(config)
        ).getOrThrow()

        assertEquals("title.ttf", restored.titleFont)
        assertEquals("title.ttf", restored.toMap()["titleFont"])
    }

    @Test
    fun `title settings reuse the font selector with inheritance as default`() {
        val dialog = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/config/TipConfigDialog.kt"
        )
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(projectFile("src/main/res/layout/dialog_tip_config.xml"))
        val rows = document.getElementsByTagName("LinearLayout")
        val titleFont = (0 until rows.length)
            .map { rows.item(it) as Element }
            .single { it.getAttribute("android:id") == "@+id/ll_title_font" }

        assertEquals("48dp", titleFont.getAttribute("android:minHeight"))
        assertTrue(dialog.contains("FontSelectDialog.CallBack"))
        assertTrue(dialog.contains("override val selectSystemTypefaceOnDefault = false"))
        assertTrue(dialog.contains("ReadBookConfig.titleFont = path"))
    }

    @Test
    fun `renderer and config transfer keep title and body fonts separate`() {
        val config = readProjectFile(
            "src/main/java/io/legado/app/help/config/ReadBookConfig.kt"
        )
        val provider = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/provider/ChapterProvider.kt"
        )
        val configDialog = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/config/BgTextConfigDialog.kt"
        )

        assertTrue(config.contains("config.titleFont.ifEmpty { config.textFont }"))
        assertTrue(config.contains("exportConfig.titleFont = shareConfig.titleFont"))
        assertTrue(config.contains("config.titleFont = importFont(config.titleFont)"))
        assertTrue(provider.contains("getPaints(titleTypeface, typeface)"))
        assertTrue(provider.contains("ReadBookConfig.titleFont = \"\""))
        assertTrue(configDialog.contains("val titleFontPath = ReadBookConfig.titleFont"))
        assertTrue(configDialog.contains("config.titleFont = if (titleFontPath == textFontPath"))
    }

    private fun readProjectFile(pathInApp: String): String = projectFile(pathInApp).readText()

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).first { it.isFile }
    }
}
