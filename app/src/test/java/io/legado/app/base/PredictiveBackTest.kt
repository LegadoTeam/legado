package io.legado.app.base

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PredictiveBackTest {

    @Test
    fun `base activity leaves default back navigation to the system`() {
        val baseActivity = File(
            "src/main/java/io/legado/app/base/BaseActivity.kt"
        ).readText()
        val blanketFinishCallback = Regex(
            """onBackPressedDispatcher\.addCallback\(this\)\s*\{\s*finish\(\)\s*}"""
        )

        assertFalse(blanketFinishCallback.containsMatchIn(baseActivity))

        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:enableOnBackInvokedCallback="true"""))
    }

    @Test
    fun `regular activities do not consume finish without closing`() {
        listOf(
            "src/main/java/io/legado/app/ui/book/search/SearchActivity.kt",
            "src/main/java/io/legado/app/ui/book/source/manage/BookSourceActivity.kt"
        ).forEach { path ->
            assertFalse(File(path).readText().contains("override fun finish()"))
        }
    }

    @Test
    fun `activities intercept back before finish may defer closing`() {
        listOf(
            "src/main/java/io/legado/app/ui/rss/source/edit/RssSourceEditActivity.kt",
            "src/main/java/io/legado/app/ui/book/source/edit/BookSourceEditActivity.kt",
            "src/main/java/io/legado/app/ui/code/CodeEditActivity.kt",
            "src/main/java/io/legado/app/ui/autoTask/AutoTaskEditActivity.kt",
            "src/main/java/io/legado/app/ui/book/audio/AudioPlayActivity.kt",
            "src/main/java/io/legado/app/ui/book/manga/ReadMangaActivity.kt",
        ).forEach { path ->
            val source = File(path).readText()
            assertTrue(source.contains("onBackPressedDispatcher.addCallback(this) { finish() }"))
            assertTrue(source.contains("override fun finish()"))
        }
    }
}
