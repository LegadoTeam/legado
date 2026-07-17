package io.legado.app.ui.rss.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadRssSystemBarTest {

    @Test
    fun `rss orientation changes use window insets`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/rss/read/ReadRssActivity.kt"
        )

        assertTrue(source.contains("WindowCompat.getInsetsController(window, window.decorView)"))
        assertTrue(source.contains("hide(WindowInsetsCompat.Type.statusBars())"))
        assertTrue(source.contains("show(WindowInsetsCompat.Type.statusBars())"))
        assertTrue(
            source.contains(
                "WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE"
            )
        )
        assertFalse(source.contains("WindowManager.LayoutParams.FLAG_FULLSCREEN"))
        assertFalse(source.contains("WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
