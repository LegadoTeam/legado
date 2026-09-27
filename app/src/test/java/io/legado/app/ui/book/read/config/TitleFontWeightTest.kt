package io.legado.app.ui.book.read.config

import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.parseReadConfigArray
import io.legado.app.help.config.parseReadConfigObject
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Test

class TitleFontWeightTest {
    @Test
    fun legacyObjectAndBackupArrayKeepTheOriginalTitleBehaviorForEveryBodyWeight() {
        for (bodyWeight in 0..2) {
            val json = """{"textBold":$bodyWeight,"titleFont":"title.ttf"}"""
            val shared = parseReadConfigObject(json).getOrThrow()
            val backup = parseReadConfigArray("[$json]").getOrThrow().single()
            for (config in listOf(shared, backup)) {
                assertEquals(-1, config.titleBold)
                assertEquals(bodyWeight, config.textBold)
                assertEquals("title.ttf", config.titleFont)
            }
        }
    }

    @Test
    fun everyTitleWeightSurvivesConfigCopyJsonMapAndBackupRoundTrips() {
        for (titleWeight in -1..2) {
            val config = ReadBookConfig.Config(textBold = 1, titleBold = titleWeight,
                titleFont = "title.ttf", textFont = "body.ttf")
            val copied = config.copy()
            val objectImport = parseReadConfigObject(GSON.toJson(copied)).getOrThrow()
            val mapImport = parseReadConfigObject(GSON.toJson(config.toMap())).getOrThrow()
            val backupImport = parseReadConfigArray(GSON.toJson(listOf(copied))).getOrThrow().single()
            for (restored in listOf(objectImport, mapImport, backupImport)) {
                assertEquals(titleWeight, restored.titleBold)
                assertEquals(1, restored.textBold)
                assertEquals("title.ttf", restored.titleFont)
                assertEquals("body.ttf", restored.textFont)
                assertEquals(titleWeight, restored.toMap()["titleBold"])
            }
        }
    }
}
