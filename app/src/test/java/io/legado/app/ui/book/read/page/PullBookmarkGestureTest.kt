package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PullBookmarkGestureTest {

    @Test
    fun `only downward vertical pulls are consumed`() {
        assertEquals(
            PullBookmarkGestureState.NONE,
            classifyPullBookmarkGesture(0f, -80f, 8, 48),
        )
        assertEquals(
            PullBookmarkGestureState.NONE,
            classifyPullBookmarkGesture(80f, 40f, 8, 48),
        )
        assertEquals(
            PullBookmarkGestureState.PULLING,
            classifyPullBookmarkGesture(4f, 24f, 8, 48),
        )
        assertEquals(
            PullBookmarkGestureState.READY,
            classifyPullBookmarkGesture(4f, 48f, 8, 48),
        )
    }

    @Test
    fun `bookmark actions use the metadata-bearing current page`() {
        val source = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        assertTrue(source.contains("val page = binding.readView.curPage.textPage"))
        assertFalse(source.contains("val page = binding.readView.getCurVisiblePage()"))
        assertTrue(source.contains("private val bookmarkToggleMutex = Mutex()"))
        assertTrue(source.contains("bookmarkToggleMutex.withLock"))
    }

    @Test
    fun `long press clears pull candidate before selecting text`() {
        val source = source("app/src/main/java/io/legado/app/ui/book/read/page/ReadView.kt")
        val selection = source.substringAfter("curPage.longPress(startX, startY)")
            .substringBefore("val startPos = textPos.copy()")
        assertTrue(selection.contains("resetPullBookmarkGesture()"))
    }

    private fun source(relativePath: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, relativePath).readText()
    }
}
