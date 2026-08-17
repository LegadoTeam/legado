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
    fun `bookmark indicator uses the header before the floating fallback`() {
        val activity = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val update = activity.substringAfter("fun upBookmarkIndicator()")
            .substringBefore("override fun changeReplaceRuleState")
        assertTrue(update.contains("curPage.showBookmarkIndicator(showIndicator)"))
        assertTrue(update.contains("showIndicator && !shownInHeader"))
        assertTrue(update.contains("curPage.displayCutoutPaddingEnd"))

        val pageView = source("app/src/main/java/io/legado/app/ui/book/read/page/PageView.kt")
        val render = pageView.substringAfter("private fun renderReaderInfo()")
            .substringBefore("private data class ReaderInfoView")
        assertTrue(render.contains("view === binding.tvHeaderRight"))
        assertTrue(render.contains("bookmarkIndicatorVisible"))
        assertTrue(pageView.contains("R.drawable.ic_bookmark_filled"))
        val showInHeader = pageView.substringAfter("fun showBookmarkIndicator(show: Boolean)")
            .substringBefore("private data class ReaderInfoView")
        assertTrue(showInHeader.contains("return show && !binding.llHeader.isGone"))
        val insets = pageView.substringAfter("fun upPaddingDisplayCutouts()")
            .substringBefore("private fun upTipStyle()")
        assertTrue(insets.contains("readBookActivity?.upBookmarkIndicator()"))

        val styleRefresh = activity.substringAfter("2 -> {")
            .substringBefore("3 ->")
        assertTrue(styleRefresh.contains("readView.upStyle()"))
        assertTrue(styleRefresh.contains("upBookmarkIndicator()"))
        assertTrue(styleRefresh.indexOf("readView.upStyle()") <
                styleRefresh.indexOf("upBookmarkIndicator()"))

        val layout = source("app/src/main/res/layout/activity_book_read.xml")
        assertTrue(layout.contains("android:src=\"@drawable/ic_bookmark_filled\""))
        assertTrue(activity.substringAfter("private fun resetBookmarkObserver()")
            .substringBefore("fun upBookmarkIndicator()")
            .contains("curPage.showBookmarkIndicator(false)"))
    }

    @Test
    fun `bookmark indicator keeps the existing header line metrics`() {
        assertEquals(16, BookmarkIndicatorGeometry.size(-12, 4))
        assertEquals(18, BookmarkIndicatorGeometry.top(30, -12))

        val pageView = source("app/src/main/java/io/legado/app/ui/book/read/page/PageView.kt")
        val indicator = pageView.substringAfter("if (bookmarkIndicatorVisible)")
            .substringBefore("return@forEach")
        assertTrue(indicator.contains("bookmarkIndicatorText"))
        assertFalse(indicator.contains("setCompoundDrawablesRelative"))
        val span = pageView.substringAfter("private class BookmarkIndicatorSpan")
            .substringBefore("class PageView")
        assertTrue(span.contains("top = metrics.top"))
        assertTrue(span.contains("bottom = metrics.bottom"))
    }

    @Test
    fun `long press clears pull candidate before selecting text`() {
        val source = source("app/src/main/java/io/legado/app/ui/book/read/page/ReadView.kt")
        val selection = source.substringAfter("curPage.longPress(startX, startY)")
            .substringBefore("val startPos = textPos.copy()")
        assertTrue(selection.contains("resetPullBookmarkGesture()"))
    }

    @Test
    fun `text selection magnifier follows drags and always dismisses`() {
        val readView = source("app/src/main/java/io/legado/app/ui/book/read/page/ReadView.kt")
        val magnifier = readView.substringAfter("fun showTextMagnifier(x: Float, y: Float)")
            .substringBefore("private fun selectMoveAtRaw")
        assertTrue(magnifier.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.P"))
        assertTrue(magnifier.contains("SelectionMagnifierApi28(this)"))
        assertTrue(readView.contains("@RequiresApi(Build.VERSION_CODES.P)\n" +
                "    private class SelectionMagnifierApi28"))

        val handleMove = readView.substringAfter("private fun selectMoveAtRaw")
            .substringBefore("fun selectStartMoveAtRaw")
        assertTrue(handleMove.contains("val localX = x - locationOnScreen[0]"))
        assertTrue(handleMove.contains("val localY = y - locationOnScreen[1]"))
        assertTrue(handleMove.contains("curPage.selectStartMove(localX, localY)"))
        assertTrue(handleMove.contains("curPage.selectEndMove(localX, localY)"))
        assertTrue(handleMove.contains("showTextMagnifier(localX, localY)"))
        val dismiss = readView.substringAfter("fun dismissTextMagnifier()")
            .substringBefore("fun onDestroy()")
        assertTrue(dismiss.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.P"))

        val touch = readView.substringAfter("override fun onTouchEvent")
            .substringBefore("private fun resetPullBookmarkGesture")
        assertTrue(touch.substringAfter("MotionEvent.ACTION_MOVE ->")
            .substringBefore("MotionEvent.ACTION_UP ->")
            .contains("showTextMagnifier(event.x, event.y)"))
        assertTrue(touch.substringAfter("MotionEvent.ACTION_UP ->")
            .substringBefore("MotionEvent.ACTION_CANCEL ->")
            .contains("dismissTextMagnifier()"))
        assertTrue(touch.substringAfter("MotionEvent.ACTION_CANCEL ->")
            .contains("dismissTextMagnifier()"))
        assertTrue(readView.substringAfter("fun cancelSelect")
            .substringBefore("fun upStatusBar")
            .contains("dismissTextMagnifier()"))
        assertTrue(readView.substringAfter("fun onDestroy()")
            .substringBefore("fun fillPage")
            .contains("dismissTextMagnifier()"))

        val activity = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val handleTouch = activity.substringAfter("override fun onTouch(v: View, event: MotionEvent)")
            .substringBefore("override fun upSelectedStart")
        assertTrue(handleTouch.contains("readView.selectStartMoveAtRaw("))
        assertTrue(handleTouch.contains("readView.selectEndMoveAtRaw("))
        val finishHandleDrag = handleTouch.substringAfter(
            "MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->"
        )
        assertTrue(finishHandleDrag.contains("readView.dismissTextMagnifier()"))
        assertTrue(finishHandleDrag.contains("readView.curPage.resetReverseCursor()"))
        assertTrue(finishHandleDrag.contains("showTextActionMenu()"))
    }

    private fun source(relativePath: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, relativePath).readText()
    }
}
