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

class TitleLineSpacingTest {

    @Test
    fun legacyConfigDefaultsToFontLineHeight() {
        val config = GSON.fromJsonObject<ReadBookConfig.Config>("{}").getOrThrow()

        assertEquals(0, config.titleLineSpacingExtra)
        assertEquals(0, config.toMap()["titleLineSpacingExtra"])
    }

    @Test
    fun spacingSurvivesJsonRoundTrip() {
        val config = ReadBookConfig.Config(titleLineSpacingExtra = 20)
        val restored = GSON.fromJsonObject<ReadBookConfig.Config>(
            GSON.toJson(config)
        ).getOrThrow()

        assertEquals(20, restored.titleLineSpacingExtra)
    }

    @Test
    fun spacingMapsToTheMinusTwoThroughPlusThreeRange() {
        assertEquals(20, titleLineSpacingToProgress(0))
        assertEquals(-20, titleLineSpacingFromProgress(0))
        assertEquals(30, titleLineSpacingFromProgress(50))
        assertEquals("-2.0", titleLineSpacingDisplayValue(0))
        assertEquals("3.0", titleLineSpacingDisplayValue(50))
    }

    @Test
    fun spacingValuesAreClamped() {
        assertEquals(0, titleLineSpacingToProgress(-100))
        assertEquals(50, titleLineSpacingToProgress(100))
        assertEquals(-20, titleLineSpacingFromProgress(-1))
        assertEquals(30, titleLineSpacingFromProgress(51))
    }

    @Test
    fun settingsExposeTitleAndNumberSpacingRanges() {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(projectFile("src/main/res/layout/dialog_tip_config.xml"))
        val seekBars = document.getElementsByTagName(
            "io.legado.app.ui.widget.DetailSeekBar"
        )
        val titleSpacing = (0 until seekBars.length)
            .map { seekBars.item(it) as Element }
            .single { it.getAttribute("android:id") == "@+id/dsb_title_line_spacing" }
        val numberSpacing = (0 until seekBars.length)
            .map { seekBars.item(it) as Element }
            .single { it.getAttribute("android:id") == "@+id/dsb_title_number_spacing" }

        assertEquals("50", titleSpacing.getAttribute("app:max"))
        assertEquals("150", numberSpacing.getAttribute("app:max"))
    }

    @Test
    fun paginationAppliesTitleSpacingOnlyToChapterNames() {
        val provider = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/provider/ChapterProvider.kt"
        )
        val layout = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/provider/TextChapterLayout.kt"
        )
        val page = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/TextPage.kt"
        )
        val config = readProjectFile(
            "src/main/java/io/legado/app/help/config/ReadBookConfig.kt"
        )

        assertTrue(provider.contains("titleLineSpacingExtra"))
        assertTrue(provider.contains("line.isTitle && !line.isTitleNumber"))
        assertTrue(
            provider.contains(
                "(100 + ReadBookConfig.titleLineSpacingExtra.coerceIn(-20, 30)) / 100f"
            )
        )
        assertTrue(layout.contains("isTitle && !isTitleNumber"))
        assertTrue(layout.contains("textHeight * (1f + (layout.lineCount - 1) * lineSpacing)"))
        assertTrue(layout.contains("prepareNextPageIfNeed(durY + textHeight)"))
        assertTrue(layout.contains("durY += textHeight * lineSpacing"))
        assertTrue(page.contains("lastLine.isTitle && !lastLine.isTitleNumber"))
        assertTrue(page.contains("ChapterProvider.lineSpacingFor(lastLine)"))
        assertTrue(config.contains("exportConfig.titleLineSpacingExtra = shareConfig.titleLineSpacingExtra"))
    }

    private fun readProjectFile(pathInApp: String): String {
        return projectFile(pathInApp).readText()
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).first { it.isFile }
    }
}
