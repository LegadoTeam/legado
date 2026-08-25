package io.legado.app.ui.book.read.config

import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.normalizeUnderlineConfigs
import io.legado.app.help.config.parseReadConfigArray
import io.legado.app.help.config.parseReadConfigObject
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class UnderlineConfigTest {

    @Test
    fun `legacy boolean underline survives object and array imports`() {
        val enabled = parseReadConfigObject("""{"underline":true}""").getOrThrow()
        val disabled = parseReadConfigArray("""[{"underline":false}]""").getOrThrow().single()

        assertEquals(1, enabled.underlineMode)
        assertEquals(0, disabled.underlineMode)
    }

    @Test
    fun `new underline fields round trip and stay in map export`() {
        val config = ReadBookConfig.Config(
            underlineMode = 5,
            underlineColor = 0x00112233,
            underlineColorSet = true,
            underlineWidth = 4.5f,
            underlineDistance = 12.5f,
            underlineBodyEnabled = false,
            underlineTitleEnabled = true,
            underlineConfigVersion = 1
        )
        val restored = parseReadConfigObject(GSON.toJson(config)).getOrThrow()

        assertEquals(config.underlineMode, restored.underlineMode)
        assertEquals(config.underlineColor, restored.underlineColor)
        assertEquals(config.underlineWidth, restored.underlineWidth, 0.001f)
        assertEquals(config.underlineDistance, restored.underlineDistance, 0.001f)
        assertFalse(restored.underlineBodyEnabled)
        assertTrue(restored.underlineTitleEnabled)
        assertEquals(4.5f, config.toMap()["underlineWidth"])
        assertEquals(12.5f, config.toMap()["underlineDistance"])
    }

    @Test
    fun `legacy styles become one shared fixed-distance config`() {
        val enabled = ReadBookConfig.Config(underlineMode = 2)
        val disabled = ReadBookConfig.Config()

        normalizeUnderlineConfigs(listOf(enabled, disabled))

        assertEquals(2, enabled.underlineMode)
        assertEquals(2, disabled.underlineMode)
        assertEquals(1f, enabled.underlineWidth, 0.001f)
        assertEquals(4f, enabled.underlineDistance, 0.001f)
        assertTrue(enabled.underlineBodyEnabled)
        assertTrue(enabled.underlineTitleEnabled)
        assertEquals(1, enabled.underlineConfigVersion)
    }

    @Test
    fun `new values are clamped while preserving transparent color`() {
        val first = ReadBookConfig.Config(
            underlineColor = 0,
            underlineColorSet = true,
            underlineWidth = 99f,
            underlineDistance = -4f,
            underlineBodyEnabled = false,
            underlineConfigVersion = 1
        )
        val second = ReadBookConfig.Config()

        normalizeUnderlineConfigs(listOf(first, second))

        assertEquals(10f, first.underlineWidth, 0.001f)
        assertEquals(0f, first.underlineDistance, 0.001f)
        assertTrue(first.underlineColorSet)
        assertFalse(second.underlineBodyEnabled)
        assertTrue(second.underlineColorSet)
    }

    @Test
    fun `selected imported style is the global source`() {
        val existing = ReadBookConfig.Config(
            underlineMode = 1,
            underlineWidth = 2f,
            underlineConfigVersion = 1
        )
        val imported = ReadBookConfig.Config(
            underlineMode = 5,
            underlineWidth = 6f,
            underlineConfigVersion = 1
        )

        normalizeUnderlineConfigs(listOf(existing, imported), legacyIndex = 1)

        assertEquals(5, existing.underlineMode)
        assertEquals(6f, existing.underlineWidth, 0.001f)
        assertEquals(5, imported.underlineMode)
    }

    @Test
    fun `dialog exposes all underline controls and renderer uses baseline distance`() {
        val layout = projectFile("src/main/res/layout/dialog_read_bg_text.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layout)
        val seekBars = (0 until document.getElementsByTagName("io.legado.app.ui.widget.DetailSeekBar").length)
            .map { document.getElementsByTagName("io.legado.app.ui.widget.DetailSeekBar").item(it) as Element }
            .associateBy { it.getAttribute("android:id") }

        assertEquals("18", seekBars["@+id/dsb_underline_width"]?.getAttribute("app:max"))
        assertEquals("60", seekBars["@+id/dsb_underline_distance"]?.getAttribute("app:max"))
        assertTrue(layout.readText().contains("@+id/sw_underline_body"))
        assertTrue(layout.readText().contains("@+id/sw_underline_title"))

        val renderer = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/TextLine.kt"
        ).readText()
        assertTrue(renderer.contains("(lineBase - lineTop) + ReadBookConfig.underlineDistance.dpToPx()"))
        assertFalse(renderer.contains("ChapterProvider.lineSpacingExtra * 10 - 11"))
        assertTrue(renderer.contains("ReadBookConfig.underlineTitleEnabled"))
        assertTrue(renderer.contains("ReadBookConfig.underlineBodyEnabled"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).first { it.isFile }
    }
}
