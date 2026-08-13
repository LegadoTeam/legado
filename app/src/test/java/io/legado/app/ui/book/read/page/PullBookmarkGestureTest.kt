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
    fun `release position decides whether bookmark is toggled`() {
        val actionUp = source("app/src/main/java/io/legado/app/ui/book/read/page/ReadView.kt")
            .substringAfter("MotionEvent.ACTION_UP ->")
            .substringBefore("MotionEvent.ACTION_CANCEL ->")

        assertTrue(actionUp.contains("classifyPullBookmarkGesture("))
        assertTrue(actionUp.contains("event.x - startX"))
        assertTrue(actionUp.contains("event.y - startY"))
        assertTrue(actionUp.contains("pullBookmarkDistance"))
        assertTrue(actionUp.contains(") == PullBookmarkGestureState.READY"))
        assertFalse(actionUp.contains("pullBookmarkState == PullBookmarkGestureState.READY"))
    }

    @Test
    fun `bookmark actions use the metadata-bearing current page`() {
        val source = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val toggleBookmark = source.substringAfter("override fun toggleBookmark()")
            .substringBefore("private suspend fun deleteBookmarks")
        assertTrue(toggleBookmark.contains("val page = binding.readView.curPage.textPage"))
        assertFalse(toggleBookmark.contains("binding.readView.getCurVisiblePage()"))
        assertTrue(source.contains("private val bookmarkToggleMutex = Mutex()"))
        assertTrue(source.contains("bookmarkToggleMutex.withLock"))
    }

    @Test
    fun `bookmark toggle remains pending until confirmation finishes`() {
        val source = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val toggleBookmark = source.substringAfter("override fun toggleBookmark()")
            .substringBefore("private suspend fun deleteBookmarks")
        assertTrue(toggleBookmark.contains("if (bookmarkTogglePending) return"))
        assertTrue(toggleBookmark.contains("onDismiss"))
        assertTrue(toggleBookmark.substringAfter("okButton {")
            .substringBefore("noButton()")
            .contains("bookmarkTogglePending = false"))
        assertTrue(toggleBookmark.substringAfter("onDismiss {")
            .contains("bookmarkTogglePending = false"))
    }

    @Test
    fun `bookmark indicator refresh waits for page content update`() {
        val source = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val pageChanged = source.substringAfter("override fun pageChanged()")
            .substringBefore("private fun updateScrollReadPosition")
        assertFalse(pageChanged.substringBefore("handler.post {")
            .contains("upBookmarkIndicator()"))
        assertTrue(pageChanged.substringAfter("handler.post {")
            .substringBefore("}")
            .contains("upBookmarkIndicator()"))
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
