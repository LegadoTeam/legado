package io.legado.app.model

import io.legado.app.ui.book.read.page.movedBeyondTouchSlop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReplacePreviewTest {

    @Test
    fun `preview position follows the source anchor across replacement and title changes`() {
        assertEquals(
            10,
            resolveReplacePreviewPosition(
                sourceText = "Old\nprefix target suffix",
                sourceTitleLength = 4,
                sourcePosition = 11,
                previewText = "New title\ntarget suffix",
                previewTitleLength = 10,
            )
        )
    }

    @Test
    fun `preview position clamps when replacement removes the source anchor`() {
        assertEquals(
            3,
            resolveReplacePreviewPosition(
                sourceText = "title\nabcdefghij",
                sourceTitleLength = 6,
                sourcePosition = 14,
                previewText = "t\nx",
                previewTitleLength = 2,
            )
        )
    }

    @Test
    fun `two finger candidate uses strict touch slop boundaries`() {
        assertFalse(movedBeyondTouchSlop(0f, 0f, 8f, 8f, 8))
        assertTrue(movedBeyondTouchSlop(0f, 0f, 9f, 0f, 8))
        assertTrue(movedBeyondTouchSlop(0f, 0f, 0f, -9f, 8))
    }

    @Test
    fun `preview stays isolated from reader state and persistent chapter work`() {
        val readBook = source("app/src/main/java/io/legado/app/model/ReadBook.kt")
        val build = readBook.substringAfter("suspend fun buildReplacePreview")
            .substringBefore("fun isCurrentReplacePreview")
        assertTrue(build.contains("replaceEnabledOverride = replaceEnabled"))
        assertTrue(build.contains("saveChapterData = false"))
        assertFalse(build.contains("setUseReplaceRule"))
        assertFalse(build.contains("saveRead()"))
        assertFalse(build.contains("curTextChapter ="))

        val chapterProvider = source(
            "app/src/main/java/io/legado/app/ui/book/read/page/provider/ChapterProvider.kt"
        )
        assertTrue(chapterProvider.contains("isTransient = !saveChapterData"))
        val layout = source(
            "app/src/main/java/io/legado/app/ui/book/read/page/provider/TextChapterLayout.kt"
        )
        assertTrue(layout.contains("if (saveChapterData)"))
        val contentView = source(
            "app/src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt"
        )
        assertTrue(contentView.contains("chapter.isTransient ||"))
    }

    @Test
    fun `gesture and lifecycle gates discard stale preview results`() {
        val readView = source("app/src/main/java/io/legado/app/ui/book/read/page/ReadView.kt")
        assertTrue(readView.contains("event.getPointerId(index)"))
        assertTrue(readView.contains("event.findPointerIndex(replacePreviewPointerIds[pointer])"))
        assertTrue(readView.contains("MotionEvent.ACTION_POINTER_UP"))
        assertTrue(readView.contains("MotionEvent.ACTION_CANCEL"))
        assertTrue(readView.contains("callBack.setReplacePreview(true)"))
        assertTrue(readView.contains("callBack.setReplacePreview(false)"))
        assertTrue(readView.contains("override val allowPageMove"))
        assertTrue(readView.contains("replacePreview?.previewChapter"))
        assertTrue(readView.contains("if (replacePreview != null) return null"))
        val touchHandler = readView.substringAfter("override fun onTouchEvent")
            .substringBefore("private fun startReplacePreviewGesture")
        assertTrue(
            touchHandler.indexOf("replacePreviewGestureState !=") <
                touchHandler.indexOf("Build.VERSION.SDK_INT")
        )
        val clearPreview = readView.substringAfter("private fun clearReplacePreview()")
            .substringBefore("fun showReplacePreview")
        assertOrder(
            clearPreview,
            "val stillCurrent = ReadBook.isCurrentReplacePreview(preview)",
            "replacePreview = null",
            "replacePreviewRestorePosition = preview.sourcePosition.takeIf { stillCurrent }",
            "upContent(resetPageOffset = true)",
        )

        val activity = source(
            "app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        )
        val preview = activity.substringAfter("override fun setReplacePreview")
            .substringBefore("private fun startBackupJob")
        assertTrue(preview.contains("++replacePreviewGeneration"))
        assertTrue(preview.contains("generation != replacePreviewGeneration"))
        assertTrue(preview.contains("binding.readView.getReadPosition()"))

        val pause = activity.substringAfter("override fun onPause()")
            .substringBefore("override fun onCompatCreateOptionsMenu")
        assertOrder(
            pause,
            "binding.readView.cancelTouchGestures()",
            "updateScrollReadPosition()",
            "ReadBook.saveRead()",
        )
    }

    @Test
    fun `reader setting is opt in`() {
        val key = source("app/src/main/java/io/legado/app/constant/PreferKey.kt")
        val config = source("app/src/main/java/io/legado/app/help/config/AppConfig.kt")
        val preferences = source("app/src/main/res/xml/pref_config_read.xml")
        assertTrue(key.contains("twoFingerReplacePreview"))
        assertTrue(config.contains("PreferKey.twoFingerReplacePreview, false"))
        val preference = preferences
            .substringBefore("android:key=\"twoFingerReplacePreview\"")
            .substringAfterLast("<io.legado.app.lib.prefs.SwitchPreference")
        assertTrue(preference.contains("android:defaultValue=\"false\""))
    }

    private fun assertOrder(source: String, vararg expected: String) {
        var position = -1
        expected.forEach { text ->
            val next = source.indexOf(text)
            assertTrue("Missing or out of order: $text", next > position)
            position = next
        }
    }

    private fun source(relativePath: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, relativePath).readText().replace("\r\n", "\n")
    }
}
