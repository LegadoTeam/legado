package io.legado.app.ui.book.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadAloudBackToSpeechActionTest {

    @Test
    fun `read aloud dialog exposes back to speaking position control`() {
        val xml = readProjectFile("src/main/res/layout/dialog_read_aloud.xml")

        assertTrue(xml.contains("@+id/iv_back_to_speech"))
        assertTrue(xml.contains("@string/back_to_speaking_position"))
    }

    @Test
    fun `back to speaking control is always shown, not gated on follow state`() {
        val dialogKt = readProjectFile("src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt")

        // The control lives in the read-aloud dialog which only opens during playback,
        // so it is always visible there instead of leaving an empty gap when attached.
        assertFalse(dialogKt.contains("ivBackToSpeech.visible("))
        assertFalse(dialogKt.contains("upBackToSpeechState"))
    }

    @Test
    fun `read aloud dialog callback restores follow and requests jump`() {
        val dialogKt = readProjectFile("src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt")
        val activityKt = readProjectFile("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")

        assertTrue(dialogKt.contains("fun backToSpeakingPosition()"))
        assertTrue(dialogKt.contains("callBack?.backToSpeakingPosition()"))
        assertTrue(activityKt.contains("override fun backToSpeakingPosition()"))
        assertTrue(activityKt.contains("ReadAloud.restoreReadAloudFollow()"))
    }

    @Test
    fun `cross chapter jump opens the speaking chapter at the spoken position`() {
        val activityKt = readProjectFile("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")

        // Cross-chapter must be precise: open the speaking chapter AT the spoken char position,
        // not just the chapter start.
        assertTrue(activityKt.contains("ReadBook.openChapter(speakingChapterIndex,"))
    }

    @Test
    fun `back to speaking position strings are localized`() {
        val defaultStrings = readProjectFile("src/main/res/values/strings.xml")
        val zhStrings = readProjectFile("src/main/res/values-zh/strings.xml")

        assertTrue(defaultStrings.contains("<string name=\"back_to_speaking_position\">Back to speaking position</string>"))
        assertTrue(zhStrings.contains("<string name=\"back_to_speaking_position\">回到朗读位置</string>"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val candidates = listOf(
            File(pathInApp),
            File("app/$pathInApp")
        )
        return candidates.first { it.isFile }.readText()
    }
}
