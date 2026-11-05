package io.legado.app.ui.book.read.paragraph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphChapterGestureTest {
    @Test fun outwardPullRequiresMoreThanSixSlopsAndRelease() {
        val gesture = ParagraphChapterGesture(8f)
        gesture.start(100f, 100f, canGoPrevious = true, canGoNext = false)
        assertFalse(gesture.move(100f, 148f))
        assertNull(gesture.release(100f, 148f))
        gesture.start(100f, 100f, canGoPrevious = true, canGoNext = false)
        assertTrue(gesture.move(100f, 149f))
        assertEquals(false, gesture.release(100f, 149f))
        assertNull(gesture.release(100f, 160f))
    }

    @Test fun upwardPullAtBottomRequestsNextChapter() {
        val gesture = ParagraphChapterGesture(8f)
        gesture.start(100f, 200f, canGoPrevious = false, canGoNext = true)
        assertEquals(true, gesture.release(102f, 140f))
    }

    @Test fun reachingBoundaryDuringAnOrdinaryDragCannotArmIt() {
        val gesture = ParagraphChapterGesture(8f)
        gesture.start(100f, 200f, canGoPrevious = false, canGoNext = false)
        assertFalse(gesture.move(100f, 100f))
        assertNull(gesture.release(100f, 0f))
    }

    @Test fun cancelledOrHorizontalGestureNeverChangesChapter() {
        val gesture = ParagraphChapterGesture(8f)
        gesture.start(100f, 100f, canGoPrevious = true, canGoNext = true)
        gesture.cancel()
        assertNull(gesture.release(100f, 200f))
        gesture.start(100f, 100f, canGoPrevious = true, canGoNext = true)
        assertFalse(gesture.move(120f, 110f))
        assertNull(gesture.release(120f, 200f))
    }

    @Test fun inwardScrollCannotBecomeAnOutwardChapterPull() {
        val gesture = ParagraphChapterGesture(8f)
        gesture.start(100f, 100f, canGoPrevious = true, canGoNext = false)
        assertFalse(gesture.move(100f, 80f))
        assertNull(gesture.release(100f, 180f))
    }

    @Test fun returningInsideThresholdBeforeReleaseCancelsChapterChange() {
        val gesture = ParagraphChapterGesture(8f)
        gesture.start(100f, 100f, canGoPrevious = true, canGoNext = false)
        assertTrue(gesture.move(100f, 180f))
        assertNull(gesture.release(100f, 120f))
    }

    @Test fun newTouchReplacesThePreviousBoundaryState() {
        val gesture = ParagraphChapterGesture(8f)
        gesture.start(100f, 100f, canGoPrevious = true, canGoNext = false)
        gesture.start(100f, 100f, canGoPrevious = false, canGoNext = true)
        assertNull(gesture.release(100f, 180f))
        gesture.start(100f, 100f, canGoPrevious = true, canGoNext = true)
        assertEquals(true, gesture.release(100f, 20f))
    }
}
