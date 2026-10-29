package io.legado.app.model

import io.legado.app.model.ReadAloudDragSeekSession.Action
import org.junit.Assert.*
import org.junit.Test

class ReadAloudDragSeekSessionTest {
    @Test fun `long paragraph cannot undo an explicit edge page turn when speech resumes`() {
        assertEquals(450, ReadAloudDragSeekSession.seekPosition(200, 450, true))
        assertEquals(500, ReadAloudDragSeekSession.seekPosition(500, 500, true))
        assertEquals(200, ReadAloudDragSeekSession.seekPosition(200, 450, false))
    }

    @Test fun `same displacement from different touch locations gives the same reading position`() {
        for (touch in listOf(30f, 400f, 900f)) {
            val relative = ReadAloudDragSeekSession.RelativePosition()
            relative.begin(650f, touch)
            assertEquals(650f, relative.move(touch), 0.01f)
            assertEquals(710f, relative.move(touch + 60f), 0.01f)
            assertEquals(610f, relative.move(touch - 40f), 0.01f)
        }
    }

    @Test fun `finger in the middle can advance the reading position beyond the bottom`() {
        val relative = ReadAloudDragSeekSession.RelativePosition()
        relative.begin(880f, 300f)
        relative.move(340f)
        assertEquals(1, relative.edge(100f, 900f, 16f))
        relative.rebase(104f)
        assertEquals(104f, relative.move(340f), 0.01f)
        assertEquals(0, relative.edge(100f, 900f, 16f))
        assertEquals(124f, relative.move(360f), 0.01f)
    }

    @Test fun `finger in the middle can return past the top and continue on the previous page`() {
        val relative = ReadAloudDragSeekSession.RelativePosition()
        relative.begin(120f, 600f)
        relative.move(560f)
        assertEquals(-1, relative.edge(100f, 900f, 16f))
        relative.rebase(896f)
        assertEquals(876f, relative.move(540f), 0.01f)
    }

    @Test fun `edge hysteresis prevents accidental flips and reversing at book boundary is immediate`() {
        val relative = ReadAloudDragSeekSession.RelativePosition()
        relative.begin(890f, 300f)
        relative.move(320f)
        assertEquals(0, relative.edge(100f, 900f, 16f))
        relative.move(350f)
        assertEquals(1, relative.edge(100f, 900f, 16f))
        relative.rebase(900f) // no next page available
        assertEquals(890f, relative.move(340f), 0.01f)
    }

    @Test fun `anchor comes from speech even when touch is elsewhere and survives paused highlight removal`() {
        val targets = listOf(
            ReadAloudDragSeekSession.Target(2, 4, 20f, 40f, 200),
            ReadAloudDragSeekSession.Target(2, 5, 80f, 100f, 250, true),
            ReadAloudDragSeekSession.Target(2, 6, 140f, 160f, 300))
        assertEquals(5, ReadAloudDragSeekSession.anchor(targets, 2, 260)?.paragraph)
        assertEquals(5, ReadAloudDragSeekSession.anchor(targets.map { it.copy(speaking = false) }, 2, 260)?.paragraph)
        assertNull(ReadAloudDragSeekSession.anchor(emptyList(), 2, 260))
    }

    @Test fun `horizontal diagonal and small gestures never begin a vertical seek`() {
        assertEquals(ReadAloudDragSeekSession.Direction.PENDING, ReadAloudDragSeekSession.direction(3f, 7f, 8))
        assertEquals(ReadAloudDragSeekSession.Direction.BLOCKED, ReadAloudDragSeekSession.direction(80f, 20f, 8))
        assertEquals(ReadAloudDragSeekSession.Direction.BLOCKED, ReadAloudDragSeekSession.direction(-40f, -40f, 8))
        assertEquals(ReadAloudDragSeekSession.Direction.VERTICAL, ReadAloudDragSeekSession.direction(3f, -70f, 8))
    }

    @Test fun `bar selects the paragraph beneath the finger including wrapped lines and page edges`() {
        val targets = listOf(
            ReadAloudDragSeekSession.Target(2, 4, 20f, 40f),
            ReadAloudDragSeekSession.Target(2, 4, 45f, 65f),
            ReadAloudDragSeekSession.Target(2, 5, 80f, 100f))
        assertEquals(4, ReadAloudDragSeekSession.targetAt(targets, -300f)?.paragraph)
        assertEquals(4, ReadAloudDragSeekSession.targetAt(targets, 60f)?.paragraph)
        assertEquals(5, ReadAloudDragSeekSession.targetAt(targets, 78f)?.paragraph)
        assertEquals(5, ReadAloudDragSeekSession.targetAt(targets, 900f)?.paragraph)
        assertNull(ReadAloudDragSeekSession.targetAt(emptyList(), 30f))
    }
    @Test fun `release seeks and resumes previously playing audio only once`() {
        val session = ReadAloudDragSeekSession()
        assertTrue(session.begin("book", true))
        assertFalse(session.begin("book", false))
        assertTrue(session.isActive)
        assertEquals(ReadAloudDragSeekSession.Result(Action.SEEK, true),
            session.finish("book", true, false, true))
        assertFalse(session.isActive)
        assertEquals(Action.NONE, session.finish("book", true, false, true).action)
    }

    @Test fun `paused audio seeks without starting playback`() {
        val session = ReadAloudDragSeekSession()
        session.begin("book", false)
        assertEquals(ReadAloudDragSeekSession.Result(Action.SEEK, false),
            session.finish("book", true, false, true))
    }

    @Test fun `cancelled or unavailable target restores old speech without seeking`() {
        for (cancelled in listOf(false, true)) {
            val session = ReadAloudDragSeekSession()
            session.begin("book", true)
            assertEquals(ReadAloudDragSeekSession.Result(Action.RESUME, true),
                session.finish("book", true, cancelled, false))
        }
        val session = ReadAloudDragSeekSession()
        session.begin("book", false)
        assertEquals(ReadAloudDragSeekSession.Result(Action.RESUME, false),
            session.finish("book", true, true, true))
    }

    @Test fun `book change and service stop discard the gesture`() {
        val session = ReadAloudDragSeekSession()
        session.begin("old-book", true)
        assertEquals(Action.NONE, session.finish("new-book", true, false, true).action)
        session.begin("new-book", true)
        assertEquals(Action.NONE, session.finish("new-book", false, false, true).action)
    }
}
