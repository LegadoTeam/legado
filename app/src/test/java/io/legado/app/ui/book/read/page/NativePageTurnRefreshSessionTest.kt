package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePageTurnRefreshSessionTest {
    private fun frame(page: Int = 1, book: String = "book") =
        NativePageTurnGate.Frame(book, 0, page, page * 100)

    @Test
    fun `system managed cleaning never adds a local full refresh`() {
        val session = NativePageTurnRefreshSession()
        repeat(100) {
            session.arm(frame(it), false)
            session.frameDrawn(frame(it))
            assertFalse(session.isFullRefreshDue("book", -2))
        }
    }

    @Test
    fun `arming and duplicate redraws do not advance cleaning frequency`() {
        val session = NativePageTurnRefreshSession()
        session.arm(frame(), false)
        assertEquals(0, session.turnsSinceFullRefresh)
        session.frameDrawn(frame())
        session.frameDrawn(frame())
        assertEquals(1, session.turnsSinceFullRefresh)
    }

    @Test
    fun `an intervening non body frame cannot credit a later target draw`() {
        val session = NativePageTurnRefreshSession()
        session.arm(frame(), false)
        session.frameDrawn(null)
        session.frameDrawn(frame())
        assertEquals(0, session.turnsSinceFullRefresh)
    }

    @Test
    fun `the configured nth submitted page cleans and resets the cycle`() {
        val session = NativePageTurnRefreshSession()
        repeat(3) { cycle ->
            repeat(5) { index ->
                val full = session.isFullRefreshDue("book", 5)
                assertEquals(index == 4, full)
                val target = frame(cycle * 5 + index)
                session.arm(target, full)
                session.frameDrawn(target)
            }
            assertEquals(0, session.turnsSinceFullRefresh)
        }
    }

    @Test
    fun `every page interval includes the first successful turn`() {
        val session = NativePageTurnRefreshSession()
        assertTrue(session.isFullRefreshDue("book", 1))
        session.arm(frame(), true)
        session.frameDrawn(frame())
        assertTrue(session.isFullRefreshDue("book", 1))
    }

    @Test
    fun `cancellation or another page drawing does not count the prepared target`() {
        val session = NativePageTurnRefreshSession()
        session.arm(frame(), false)
        session.cancelPending()
        session.frameDrawn(frame())
        assertEquals(0, session.turnsSinceFullRefresh)
        session.arm(frame(2), false)
        session.frameDrawn(frame(3))
        session.frameDrawn(frame(2))
        assertEquals(0, session.turnsSinceFullRefresh)
    }

    @Test
    fun `coalesced submissions count only the final drawn frame`() {
        val session = NativePageTurnRefreshSession()
        session.arm(frame(1), false)
        session.arm(frame(2), false)
        session.frameDrawn(frame(2))
        assertEquals(1, session.turnsSinceFullRefresh)
    }

    @Test
    fun `a canceled full refresh does not reset the completed page counter`() {
        val session = NativePageTurnRefreshSession()
        session.isFullRefreshDue("book", 2)
        session.arm(frame(1), false)
        session.frameDrawn(frame(1))
        session.arm(frame(2), true)
        session.cancelPending()
        assertEquals(1, session.turnsSinceFullRefresh)
        assertTrue(session.isFullRefreshDue("book", 2))
    }

    @Test
    fun `a new book or interval starts a fresh cleaning cycle`() {
        val session = NativePageTurnRefreshSession()
        assertFalse(session.isFullRefreshDue("book", 5))
        repeat(4) {
            session.arm(frame(it), false)
            session.frameDrawn(frame(it))
        }
        assertTrue(session.isFullRefreshDue("book", 5))
        assertTrue(session.isFullRefreshDue("book", 1))
        assertFalse(session.isFullRefreshDue("book", 10))
        assertFalse(session.isFullRefreshDue("another book", 5))
        session.arm(frame(0, "another book"), false)
        session.frameDrawn(frame(0, "another book"))
        assertEquals(1, session.turnsSinceFullRefresh)
    }

    @Test
    fun `switching from system managed to a custom interval starts a new cleaning cycle`() {
        val session = NativePageTurnRefreshSession()
        session.isFullRefreshDue("book", 5)
        repeat(4) {
            session.arm(frame(it), false)
            session.frameDrawn(frame(it))
        }
        assertTrue(session.isFullRefreshDue("book", 5))
        assertFalse(session.isFullRefreshDue("book", -2))
        assertFalse(session.isFullRefreshDue("book", 5))
        assertEquals(0, session.turnsSinceFullRefresh)
    }
}
