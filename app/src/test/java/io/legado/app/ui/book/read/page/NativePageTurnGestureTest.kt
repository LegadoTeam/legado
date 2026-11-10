package io.legado.app.ui.book.read.page

import io.legado.app.ui.book.read.page.entities.PageDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePageTurnGestureTest {
    @Test
    fun `horizontal swipe commits using total displacement`() {
        assertEquals(PageDirection.NEXT, direction(100f, 100f, 40f, 105f))
        assertEquals(PageDirection.PREV, direction(100f, 100f, 160f, 95f))
    }

    @Test
    fun `a small final reversal does not cancel an otherwise complete swipe`() {
        // The previous MOVE may have been at x=30; the complete gesture still moved left.
        assertEquals(PageDirection.NEXT, direction(100f, 100f, 35f, 100f))
    }

    @Test
    fun `returning within touch slop cancels navigation`() {
        assertEquals(PageDirection.NONE, direction(100f, 100f, 105f, 100f))
        assertEquals(PageDirection.NONE, direction(100f, 100f, 110f, 100f))
    }

    @Test
    fun `vertical bookmark and diagonal gestures do not become horizontal turns`() {
        assertEquals(PageDirection.NONE, direction(100f, 100f, 120f, 160f))
        assertEquals(PageDirection.NONE, direction(100f, 100f, 140f, 140f))
        assertEquals(PageDirection.NONE, direction(100f, 100f, 100f, 20f))
    }

    @Test
    fun `reversing across the original start point changes the resulting page direction`() {
        assertEquals(PageDirection.PREV, direction(100f, 100f, 130f, 100f))
        assertEquals(PageDirection.NEXT, direction(100f, 100f, 70f, 100f))
    }

    @Test
    fun `two fingers without any move remain consumed rather than becoming a tap`() {
        val gesture = NativePageTurnGesture()
        gesture.begin()
        gesture.cancel()

        assertTrue(gesture.consumed)
        assertEquals(PageDirection.NONE, gesture.direction(100f, 100f, 100f, 100f, 100))
        assertTrue(gesture.consumed)
        assertEquals(PageDirection.NONE, gesture.direction(100f, 100f, 20f, 100f, 100))
    }

    @Test
    fun `a new swipe works after a canceled multi-touch gesture`() {
        val gesture = NativePageTurnGesture()
        gesture.cancel()
        gesture.begin()

        assertFalse(gesture.consumed)
        assertEquals(PageDirection.NEXT, gesture.direction(100f, 100f, 20f, 100f, 100))
        assertTrue(gesture.consumed)
    }

    @Test
    fun `a swipe that returns to its origin remains consumed and cancels navigation`() {
        val gesture = NativePageTurnGesture()
        gesture.begin()
        assertEquals(PageDirection.NEXT, gesture.direction(100f, 100f, 20f, 100f, 100))

        assertEquals(PageDirection.NONE, gesture.direction(100f, 100f, 100f, 100f, 100))
        assertTrue(gesture.consumed)
    }

    @Test
    fun `a hardware turn with a held finger cannot turn its eventual release into another tap`() {
        val gesture = NativePageTurnGesture()
        gesture.begin()
        gesture.finish(touching = true)
        assertTrue(gesture.consumed)
        assertEquals(PageDirection.NONE, gesture.direction(100f, 100f, 20f, 100f, 100))
        gesture.finish(touching = false)
        assertFalse(gesture.consumed)
        assertEquals(PageDirection.NEXT, gesture.direction(100f, 100f, 20f, 100f, 100))
    }

    private fun direction(startX: Float, startY: Float, endX: Float, endY: Float) =
        nativePageTurnDirection(startX, startY, endX, endY, slopSquare = 100)
}
