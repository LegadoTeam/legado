package io.legado.app.ui.book.read.config

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadAloudAccessibilityTest {

    @Test
    fun `play pause tooltip follows its dynamic accessibility label`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt"
        )
        val upPlayState = source.substringAfter("private fun upPlayState()")
            .substringBefore("private fun upSeekTimer()")

        val pauseLabel = upPlayState.indexOf("R.string.pause")
        val playLabel = upPlayState.indexOf("R.string.audio_play")
        val tooltipUpdate = upPlayState.indexOf("TooltipCompat.setTooltipText(")
        assertTrue(pauseLabel >= 0)
        assertTrue(playLabel >= 0)
        assertTrue(tooltipUpdate > pauseLabel)
        assertTrue(tooltipUpdate > playLabel)
        assertTrue(upPlayState.contains("binding.ivPlayPause.contentDescription,"))
    }

    private fun projectFile(pathInApp: String): String {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .first { it.isFile }
            .readText()
    }
}
