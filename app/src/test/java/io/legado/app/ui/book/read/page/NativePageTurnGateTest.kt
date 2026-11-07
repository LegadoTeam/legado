package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePageTurnGateTest {
    private val start = NativePageTurnGate.Position("book", 3, 0)
    private val target = start.copy(chapterPosition = 100)
    private val firstFrame = NativePageTurnGate.Frame("book", 3, 0, 0)
    private val nextFrame = firstFrame.copy(pageIndex = 1, chapterPosition = 100)

    @Test
    fun `ordinary redraws never request an effect`() {
        val gate = NativePageTurnGate()
        gate.frameDrawn(firstFrame)

        assertNull(gate.consume(start, firstFrame, 0, 0, true))
        assertNull(gate.consume(target, nextFrame, 0, 0, true))
    }

    @Test
    fun `confirmed turn is consumed exactly once`() {
        val gate = NativePageTurnGate()
        gate.frameDrawn(firstFrame)
        gate.request(target, true, 0, 7)

        assertEquals(true, gate.consume(target, nextFrame, 0, 7, true))
        assertFalse(gate.hasPending)
        assertNull(gate.consume(target, nextFrame, 0, 7, true))
    }

    @Test
    fun `loading frame waits for the requested chapter to be bound`() {
        val gate = NativePageTurnGate()
        val chapterTarget = target.copy(chapterIndex = 4, chapterPosition = 0)
        val chapterFrame = firstFrame.copy(chapterIndex = 4)
        gate.request(chapterTarget, true, 0, 1)

        assertNull(gate.consume(chapterTarget, null, 0, 1, true))
        assertTrue(gate.hasPending)
        assertNull(gate.consume(chapterTarget, firstFrame, 0, 1, true))
        assertTrue(gate.hasPending)
        assertEquals(true, gate.consume(chapterTarget, chapterFrame, 0, 1, true))
    }

    @Test
    fun `coalesced requests use net direction from the displayed page`() {
        val gate = NativePageTurnGate()
        val finalPosition = target.copy(chapterPosition = 200)
        val finalFrame = nextFrame.copy(pageIndex = 2, chapterPosition = 200)
        gate.frameDrawn(firstFrame)
        gate.request(target, true, 0, 1)
        gate.request(target.copy(chapterPosition = 300), true, 0, 2)
        gate.request(finalPosition, false, 0, 3)

        assertEquals(true, gate.consume(finalPosition, finalFrame, 0, 3, true))
        assertNull(gate.consume(finalPosition, finalFrame, 0, 3, true))
    }

    @Test
    fun `a final backward input can still result in a forward displayed turn`() {
        val gate = NativePageTurnGate()
        gate.frameDrawn(firstFrame)
        gate.request(target, true, 0, 1)
        gate.request(target.copy(chapterPosition = 200), true, 0, 2)
        gate.request(target, false, 0, 3)

        assertEquals(true, gate.consume(target, nextFrame, 0, 3, true))
    }

    @Test
    fun `chapter order takes precedence over position within a chapter`() {
        val gate = NativePageTurnGate()
        val previousChapter = start.copy(chapterIndex = 2, chapterPosition = Int.MAX_VALUE)
        val previousLastFrame = firstFrame.copy(chapterIndex = 2, pageIndex = 50, chapterPosition = 9000)
        gate.frameDrawn(nextFrame)
        gate.request(previousChapter, false, 0, 1)
        assertNull(gate.consume(previousChapter, null, 0, 1, true))

        assertEquals(false, gate.consume(previousChapter, previousLastFrame, 0, 1, true))
    }

    @Test
    fun `layout renumbering does not reverse a backward character movement`() {
        val gate = NativePageTurnGate()
        val previous = firstFrame.copy(pageIndex = 10, chapterPosition = 10000)
        val relaidOutTarget = nextFrame.copy(pageIndex = 19, chapterPosition = 9500)
        val position = target.copy(chapterPosition = 9500)
        gate.frameDrawn(previous)
        gate.request(position, false, 0, 1)

        assertEquals(false, gate.consume(position, relaidOutTarget, 0, 1, true))
    }

    @Test
    fun `the same anchor with a different layout page number does not animate`() {
        val gate = NativePageTurnGate()
        gate.frameDrawn(nextFrame)
        gate.request(target, true, 0, 1)

        assertNull(gate.consume(target, nextFrame.copy(pageIndex = 8), 0, 1, true))
        assertFalse(gate.hasPending)
    }

    @Test
    fun `returning to the already displayed page does not animate`() {
        val gate = NativePageTurnGate()
        gate.frameDrawn(firstFrame)
        gate.request(target, true, 0, 1)
        gate.request(start, false, 0, 2)

        assertNull(gate.consume(start, firstFrame, 0, 2, true))
        assertFalse(gate.hasPending)
    }

    @Test
    fun `navigation elsewhere expires an outstanding chapter load`() {
        val positions = listOf(
            target.copy(bookUrl = "another book"),
            target.copy(chapterIndex = 4),
            target.copy(chapterPosition = 101),
            null,
        )
        positions.forEach { position ->
            val gate = NativePageTurnGate()
            gate.request(target, true, 0, 1)

            assertNull(gate.consume(position, null, 0, 1, true))
            assertFalse(gate.hasPending)
            assertNull(gate.consume(target, nextFrame, 0, 1, true))
        }
    }

    @Test
    fun `a later gesture expires the previous intent even at the same position`() {
        val gate = NativePageTurnGate()
        gate.request(target, true, 0, 4)

        assertNull(gate.consume(target, nextFrame, 0, 5, true))
        assertFalse(gate.hasPending)
    }

    @Test
    fun `rotation and disabled or covered readers discard pending effects`() {
        listOf(1 to true, 0 to false).forEach { (rotation, enabled) ->
            val gate = NativePageTurnGate()
            gate.request(target, true, 0, 0)

            assertNull(gate.consume(target, nextFrame, rotation, 0, enabled))
            assertFalse(gate.hasPending)
        }
    }

    @Test
    fun `lifecycle cancellation prevents an effect after resuming`() {
        val gate = NativePageTurnGate()
        gate.request(target, true, 0, 0)
        gate.clear()

        assertNull(gate.consume(target, nextFrame, 0, 0, true))
    }

    @Test
    fun `each actually drawn page can be animated again when revisited`() {
        val gate = NativePageTurnGate()
        gate.frameDrawn(firstFrame)
        gate.request(target, true, 0, 1)
        assertEquals(true, gate.consume(target, nextFrame, 0, 1, true))
        gate.frameDrawn(nextFrame)
        gate.request(start, false, 0, 2)

        assertEquals(false, gate.consume(start, firstFrame, 0, 2, true))
    }

    @Test
    fun `re enabling after navigation while disabled discards the old display anchor`() {
        val gate = NativePageTurnGate()
        gate.frameDrawn(firstFrame.copy(chapterPosition = 1000))
        gate.clear()
        // Unanimated navigation while effects are off need not redraw the gate baseline.
        gate.reset()
        gate.request(target, true, 0, 1)
        assertEquals(true, gate.consume(target, nextFrame, 0, 1, true))
    }

    @Test
    fun `a ready initial anchor preserves net direction before the first draw after enabling`() {
        val gate = NativePageTurnGate()
        gate.reset()
        gate.seedIfNeeded(firstFrame)
        gate.request(target, true, 0, 1)
        // A bound target is not a new visible baseline for a pending navigation.
        gate.seedIfNeeded(nextFrame.copy(chapterPosition = 300))
        gate.request(target.copy(chapterPosition = 200), true, 0, 2)
        gate.request(target, false, 0, 3)
        assertEquals(true, gate.consume(target, nextFrame, 0, 3, true))
    }
}
