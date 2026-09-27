package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PullBookmarkGestureTest {

    @Test
    fun `bookmark pull distance preserves the old default and accepts overrides`() {
        assertEquals(48, resolvePullBookmarkDistance(0, 8))
        assertEquals(48, resolvePullBookmarkDistance(-1, 8))
        assertEquals(96, resolvePullBookmarkDistance(96, 8))

        val readView = source("app/src/main/java/io/legado/app/ui/book/read/page/ReadView.kt")
        val settings = source(
            "app/src/main/java/io/legado/app/ui/book/read/config/MoreConfigDialog.kt"
        )
        val preferences = source("app/src/main/res/xml/pref_config_read.xml")
        assertTrue(readView.contains("AppConfig.pullBookmarkDistance"))
        assertTrue(settings.contains("PreferKey.pullBookmarkDistance ->"))
        assertTrue(settings.contains("AppConfig.pullBookmarkDistance = it"))
        assertTrue(preferences.contains("android:key=\"pullBookmarkDistance\""))
    }

    @Test
    fun `bookmark pull moves the page with bounded resistance and rebounds`() {
        assertEquals(0f, resolvePullBookmarkPageOffset(-20f, 1000), 0.001f)
        assertEquals(50f, resolvePullBookmarkPageOffset(100f, 1000), 0.001f)
        assertEquals(350f, resolvePullBookmarkPageOffset(1000f, 1000), 0.001f)

        val readView = source("app/src/main/java/io/legado/app/ui/book/read/page/ReadView.kt")
        val move = readView.substringAfter("MotionEvent.ACTION_MOVE ->")
            .substringBefore("MotionEvent.ACTION_UP ->")
        assertTrue(move.contains("setPullBookmarkPageOffset("))
        assertTrue(readView.contains("ValueAnimator.ofFloat(startOffset, 0f)"))
        val pointerChange = readView.substringAfter("//在多点触控时")
            .substringBefore("when (event.actionMasked)")
        assertTrue(pointerChange.contains("resetPullBookmarkGesture(animatePage = false)"))
        assertTrue(pointerChange.indexOf("resetPullBookmarkGesture") <
                pointerChange.indexOf("pageDelegate?.onTouch(event)"))

        val pageOffset = readView.substringAfter("private fun setPullBookmarkPageOffset")
            .substringBefore("fun cancelSelect")
        assertTrue(pageOffset.contains("curPage.translationY = offset"))
        assertFalse(pageOffset.contains("callBack"))
    }

    @Test
    fun `bookmark pull exposes an opaque reader background`() {
        val readView = source("app/src/main/java/io/legado/app/ui/book/read/page/ReadView.kt")
        val upBg = readView.substringAfter("fun upBg()")
            .substringBefore("fun upBgAlpha()")

        assertTrue(upBg.contains("setBackgroundColor(ReadBookConfig.bgMeanColor)"))
    }

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
    fun `bookmark indicator follows the animated page in both header modes`() {
        val activity = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val update = activity.substringAfter("fun upBookmarkIndicator()")
            .substringBefore("override fun changeReplaceRuleState")
        assertTrue(update.contains("pageView.showBookmarkIndicator(showIndicator)"))
        assertFalse(update.contains("binding.bookmarkIndicator"))

        val pageView = source("app/src/main/java/io/legado/app/ui/book/read/page/PageView.kt")
        val render = pageView.substringAfter("private fun renderReaderInfo()")
            .substringBefore("private data class ReaderInfoView")
        assertTrue(render.contains("view === binding.tvHeaderRight"))
        assertTrue(render.contains("bookmarkIndicatorVisible"))
        assertTrue(render.contains("view.minimumWidth = 32.dpToPx()"))
        assertTrue(render.contains("view.setTextIfNotEqual(\" \")"))
        assertTrue(render.contains("view.contentDescription = context.getString(R.string.bookmark)"))
        val showInHeader = pageView.substringAfter("fun showBookmarkIndicator(show: Boolean)")
            .substringBefore("private data class ReaderInfoView")
        assertTrue(showInHeader.contains("pageBookmarkIndicator.isVisible = show"))
        assertTrue(showInHeader.contains("if (showInHeader) 32 else 20"))
        assertTrue(showInHeader.contains("if (showInHeader) 32 else 40"))
        assertTrue(showInHeader.contains("R.drawable.ic_bookmark_long"))
        assertTrue(showInHeader.contains("View.IMPORTANT_FOR_ACCESSIBILITY_AUTO"))
        assertTrue(showInHeader.contains("doOnLayout"))
        assertTrue(showInHeader.contains("translationX"))
        assertTrue(showInHeader.contains("translationY"))
        assertTrue(showInHeader.contains("bookmarkIndicatorMarginRight("))
        assertTrue(showInHeader.contains("bookmarkIndicatorTop("))
        assertTrue(showInHeader.contains("binding.vwRoot.paddingRight"))
        assertTrue(showInHeader.contains("translationY = (headerHeight - top).toFloat()"))
        val insets = pageView.substringAfter("fun upPaddingDisplayCutouts()")
            .substringBefore("private fun upTipStyle()")
        assertTrue(insets.contains("readBookActivity?.upBookmarkIndicator()"))

        val styleRefresh = activity.substringAfter("2 -> {")
            .substringBefore("3 ->")
        assertTrue(styleRefresh.contains("readView.upStyle()"))
        assertTrue(styleRefresh.contains("upBookmarkIndicator()"))
        assertTrue(styleRefresh.indexOf("readView.upStyle()") <
                styleRefresh.indexOf("upBookmarkIndicator()"))

        val pageLayout = source("app/src/main/res/layout/view_book_page.xml")
        val pageOverlayId = "android:id=\"@+id/page_bookmark_indicator\""
        assertTrue(pageLayout.contains(pageOverlayId))
        val pageOverlay = pageLayout.substringAfter(pageOverlayId)
            .substringBefore("/>")
        assertFalse(pageLayout.contains("android:id=\"@+id/bookmark_indicator\""))
        assertTrue(pageOverlay.contains("android:layout_width=\"32dp\""))
        assertTrue(pageOverlay.contains("android:layout_height=\"32dp\""))
        assertTrue(pageOverlay.contains("android:contentDescription=\"@string/bookmark\""))
        assertTrue(pageOverlay.contains("android:importantForAccessibility=\"no\""))
        assertTrue(pageOverlay.contains("android:src=\"@drawable/ic_bookmark_filled\""))
        assertTrue(pageOverlay.contains("app:layout_constraintTop_toTopOf=\"parent\""))
        assertTrue(pageOverlay.contains("app:layout_constraintRight_toRightOf=\"parent\""))

        val activityLayout = source("app/src/main/res/layout/activity_book_read.xml")
        assertFalse(activityLayout.contains("android:id=\"@+id/bookmark_indicator\""))
        assertFalse(activity.contains("override fun setPullBookmarkPageOffset"))

        val longIndicator = source("app/src/main/res/drawable/ic_bookmark_long.xml")
        assertTrue(longIndicator.contains("android:width=\"20dp\""))
        assertTrue(longIndicator.contains("android:height=\"40dp\""))
        assertTrue(longIndicator.contains("android:pathData=\"M4,0h12v40"))

        val horizontal = source(
            "app/src/main/java/io/legado/app/ui/book/read/page/delegate/HorizontalPageDelegate.kt"
        )
        val cover = source(
            "app/src/main/java/io/legado/app/ui/book/read/page/delegate/CoverPageDelegate.kt"
        )
        val simulation = source(
            "app/src/main/java/io/legado/app/ui/book/read/page/delegate/SimulationPageDelegate.kt"
        )
        assertTrue(horizontal.contains("curPage.screenshot(curRecorder)"))
        assertTrue(cover.contains("curPage.screenshot(curRecorder)"))
        assertTrue(simulation.contains("curPage.screenshot(curBitmap, canvas)"))
        assertTrue(activity.substringAfter("private fun resetBookmarkObserver()")
            .substringBefore("fun upBookmarkIndicator()")
            .contains("curPage.showBookmarkIndicator(false)"))
    }

    @Test
    fun `bookmark indicator keeps the existing header line metrics`() {
        assertEquals(20, BookmarkIndicatorGeometry.marginRight(12, 12, 4))
        assertEquals(-4, BookmarkIndicatorGeometry.marginRight(0, 0, 4))
        assertEquals(6, BookmarkIndicatorGeometry.top(30, 28, 4, 0))
        assertEquals(8, BookmarkIndicatorGeometry.top(10, 32, 4, 8))

        val pageView = source("app/src/main/java/io/legado/app/ui/book/read/page/PageView.kt")
        val indicator = pageView.substringAfter("if (bookmarkIndicatorVisible)")
            .substringBefore("return@forEach")
        assertTrue(indicator.contains("view.setTextIfNotEqual(\" \")"))
        assertFalse(indicator.contains("setCompoundDrawablesRelative"))
        assertFalse(pageView.contains("BookmarkIndicatorSpan"))
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
        assertTrue(readView.replace("\r\n", "\n").contains(
            "@RequiresApi(Build.VERSION_CODES.P)\n" +
                "    private class SelectionMagnifierApi28"
        ))

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
