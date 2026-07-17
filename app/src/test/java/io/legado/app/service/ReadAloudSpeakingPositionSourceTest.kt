package io.legado.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadAloudSpeakingPositionSourceTest {

    @Test
    fun `service records live spoken char position through upTtsProgress`() {
        val serviceKt = readProjectFile("src/main/java/io/legado/app/service/BaseReadAloudService.kt")

        // The service is the durable source of truth for the current spoken char position,
        // so a recreated Activity can jump back even before observing a TTS_PROGRESS event.
        assertTrue(serviceKt.contains("var readAloudChapterStart"))
        assertTrue(serviceKt.contains("fun upTtsProgress(progress: Int)"))
        assertTrue(serviceKt.contains("readAloudChapterStart = progress"))
    }

    @Test
    fun `read aloud facade exposes spoken char position`() {
        val readAloudKt = readProjectFile("src/main/java/io/legado/app/model/ReadAloud.kt")

        assertTrue(readAloudKt.contains("val readAloudChapterStart"))
        assertTrue(readAloudKt.contains("BaseReadAloudService.readAloudChapterStart"))
    }

    @Test
    fun `back to speaking position uses observer cache only for the active speech chapter`() {
        val activityKt = readProjectFile("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
            .replace(Regex("\\s+"), " ")

        assertTrue(activityKt.contains("lastReadAloudChapterIndex == speakingChapterIndex"))
        assertTrue(activityKt.contains("?: ReadAloud.readAloudChapterStart"))
        assertTrue(activityKt.contains("lastReadAloudChapterIndex = ReadAloud.readAloudChapterIndex"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val candidates = listOf(
            File(pathInApp),
            File("app/$pathInApp")
        )
        return candidates.first { it.isFile }.readText()
    }
}
