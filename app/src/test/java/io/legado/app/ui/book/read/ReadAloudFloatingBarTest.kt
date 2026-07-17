package io.legado.app.ui.book.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadAloudFloatingBarTest {

    @Test
    fun `visibility requires running detached speech with menus hidden`() {
        assertTrue(ReadAloudBarVisibility.shouldShow(true, false, false))
        assertFalse(ReadAloudBarVisibility.shouldShow(false, false, false))
        assertFalse(ReadAloudBarVisibility.shouldShow(true, true, false))
        assertFalse(ReadAloudBarVisibility.shouldShow(true, false, true))
    }

    @Test
    fun `floating actions and host include remain wired`() {
        val layout = projectFile("src/main/res/layout/view_read_aloud_float_bar.xml").readText()
        val host = projectFile("src/main/res/layout/activity_book_read.xml").readText()
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText()

        assertTrue(layout.contains("@+id/ll_back_to_speech"))
        assertTrue(layout.contains("@+id/ll_read_from_here"))
        assertTrue(layout.contains("android:layout_width=\"match_parent\""))
        assertTrue(layout.contains("android:maxLines=\"2\""))
        assertTrue(host.contains("@layout/view_read_aloud_float_bar"))
        assertTrue(activity.contains("backToSpeakingPosition()"))
        assertTrue(activity.contains("ReadBook.readAloud()"))
        assertTrue(activity.contains("ReadAloudBarVisibility.shouldShow"))
    }

    @Test
    fun `follow changes publish a dedicated refresh event`() {
        val eventBus = projectFile(
            "src/main/java/io/legado/app/constant/EventBus.kt"
        ).readText()
        val service = projectFile(
            "src/main/java/io/legado/app/service/BaseReadAloudService.kt"
        ).readText()

        assertTrue(eventBus.contains("READ_ALOUD_FOLLOW"))
        assertTrue(service.section("fun detachReadAloudFollow", "fun restoreReadAloudFollow")
            .contains("postEvent(EventBus.READ_ALOUD_FOLLOW"))
        assertTrue(service.section("fun restoreReadAloudFollow", "fun shouldSyncSpeechNavigation")
            .contains("postEvent(EventBus.READ_ALOUD_FOLLOW"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }

    private fun String.section(start: String, end: String): String =
        substringAfter(start).substringBefore(end)
}
