package io.legado.app.ui.book.read

import org.junit.Assert.*
import org.junit.Test

class FollowPointerTrackerTest {
    private fun p(id: Int, y: Float, x: Float = 30f) = FollowPointerTracker.Pointer(id, x, y)

    @Test fun addedFingerContinuesWithoutAbsolutePositionJump() {
        val t = FollowPointerTracker()
        t.begin(p(4, 100f))
        assertEquals(20f, t.move(listOf(p(4, 120f))).deltaY, 0f)
        t.pointerDown(p(19, 600f))
        assertEquals(10f, t.move(listOf(p(4, 120f), p(19, 610f))).deltaY, 0f)
    }
    @Test fun liftingActiveFingerRebasesRemainingFinger() {
        val t = FollowPointerTracker()
        t.begin(p(4, 100f))
        t.pointerDown(p(19, 600f))
        t.pointerUp(19, listOf(p(4, 150f)))
        assertEquals(0f, t.move(listOf(p(4, 150f))).deltaY, 0f)
        assertEquals(-12f, t.move(listOf(p(4, 138f))).deltaY, 0f)
    }
    @Test fun liftingInactiveFingerDoesNotInterruptActiveMovement() {
        val t = FollowPointerTracker()
        t.begin(p(4, 100f))
        t.pointerDown(p(19, 600f))
        t.pointerUp(4, listOf(p(19, 615f)))
        assertEquals(15f, t.move(listOf(p(19, 615f))).deltaY, 0f)
    }
    @Test fun reorderingAndroidIndicesDoesNotSwitchPointer() {
        val t = FollowPointerTracker()
        t.begin(p(4, 100f))
        t.pointerDown(p(19, 600f))
        assertEquals(7f, t.move(listOf(p(19, 607f), p(4, 120f))).deltaY, 0f)
    }
    @Test fun threeFingerHandoffsStayRelative() {
        val t = FollowPointerTracker()
        t.begin(p(1, 10f))
        t.pointerDown(p(2, 100f))
        t.pointerDown(p(3, 900f))
        assertEquals(5f, t.move(listOf(p(1, 10f), p(2, 100f), p(3, 905f))).deltaY, 0f)
        t.pointerUp(3, listOf(p(1, 10f), p(2, 100f)))
        assertEquals(8f, t.move(listOf(p(1, 10f), p(2, 108f))).deltaY, 0f)
        t.pointerUp(2, listOf(p(1, 10f)))
        assertEquals(9f, t.move(listOf(p(1, 19f))).deltaY, 0f)
    }
    @Test fun multipleFingerTapCannotBecomeOrdinaryMenuTap() {
        val t = FollowPointerTracker()
        t.begin(p(1, 10f)); t.pointerDown(p(2, 100f)); t.pointerUp(2, listOf(p(1, 10f)))
        assertTrue(t.hadMultiplePointers)
        t.begin(p(1, 10f))
        assertFalse(t.hadMultiplePointers)
    }
    @Test fun missingPointerOrCancellationCannotProduceHugeDelta() {
        val t = FollowPointerTracker()
        t.begin(p(1, 10f))
        assertEquals(0f, t.move(listOf(p(9, 1000f))).deltaY, 0f)
        t.clear()
        assertEquals(0f, t.move(listOf(p(8, 600f))).deltaY, 0f)
        assertEquals(4f, t.move(listOf(p(8, 604f))).deltaY, 0f)
    }
}
