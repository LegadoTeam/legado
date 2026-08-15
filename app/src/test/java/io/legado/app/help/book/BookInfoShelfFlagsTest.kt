package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookInfoShelfFlagsTest {

    @Test
    fun leftoverWeakUrlCannotPromoteBesideSoleReal() {
        assertFalse(BookInfoShelfFlags.canPromoteToOfficial(identityOnShelf = true, urlOnShelf = false))
        assertTrue(BookInfoShelfFlags.canPromoteToOfficial(identityOnShelf = false, urlOnShelf = false))
        assertTrue(BookInfoShelfFlags.canPromoteToOfficial(identityOnShelf = true, urlOnShelf = true))
    }

    @Test
    fun tempPersistDoesNotPromoteOfficialShelfFlags() {
        val off = BookInfoShelfFlags.State(inBookshelf = false, urlOnShelf = false)
        assertEquals(off, BookInfoShelfFlags.afterUrlPersisted(off))

        val identityOnly = BookInfoShelfFlags.State(inBookshelf = true, urlOnShelf = false)
        assertEquals(identityOnly, BookInfoShelfFlags.afterUrlPersisted(identityOnly))
    }

    @Test
    fun tocCancelDeletesOnlyTheCurrentNotShelfUrl() {
        assertTrue(BookInfoShelfFlags.canDeleteBookUrl("B", "B"))
        assertTrue(BookInfoShelfFlags.canDeleteTempBookUrl("B", "B", persistedIsNotShelf = true))
        assertFalse(BookInfoShelfFlags.canDeleteTempBookUrl("B", "B", persistedIsNotShelf = false))
        assertFalse(BookInfoShelfFlags.canDeleteTempBookUrl("B", "A", persistedIsNotShelf = true))
        assertFalse(BookInfoShelfFlags.canDeleteTempBookUrl("B", null, persistedIsNotShelf = true))
        assertFalse(BookInfoShelfFlags.canDeleteTempBookUrl("", "B", persistedIsNotShelf = true))
        assertFalse(BookInfoShelfFlags.canDeleteBookUrl("B", "A"))
        assertFalse(BookInfoShelfFlags.canDeleteBookUrl("B", null))
        assertFalse(BookInfoShelfFlags.canDeleteBookUrl("", "B"))
    }

    @Test
    fun readerReturnPrefersCurrentPageUrlAfterChangeTo() {
        assertEquals("B", BookInfoShelfFlags.resolveReturnBookUrl("B", "A"))
        assertEquals("A", BookInfoShelfFlags.resolveReturnBookUrl("", "A"))
        assertEquals("A", BookInfoShelfFlags.resolveReturnBookUrl(null, "A"))
        assertEquals("", BookInfoShelfFlags.resolveReturnBookUrl(null, null))
        assertFalse(BookInfoShelfFlags.canDeleteTempBookUrl("B", "B", persistedIsNotShelf = false))
    }

    @Test
    fun readerReturnRecomputesBothFlagsFromPresence() {
        val added = BookInfoShelfFlags.afterReaderReturned(
            identityOnShelf = true,
            urlOnShelf = true,
        )
        assertEquals(BookInfoShelfFlags.State(true, true), added)

        val stillTemp = BookInfoShelfFlags.afterReaderReturned(
            identityOnShelf = false,
            urlOnShelf = false,
        )
        assertEquals(BookInfoShelfFlags.State(false, false), stillTemp)
        assertFalse(BookInfoShelfFlags.readerInBookshelfExtra(false))
        assertTrue(BookInfoShelfFlags.readerInBookshelfExtra(true))
    }

    @Test
    fun loadFailureRestoresOldBookPresence() {
        val restored = BookInfoShelfFlags.afterBookRestored(
            identityOnShelf = false,
            urlOnShelf = false,
        )
        assertEquals(BookInfoShelfFlags.State(false, false), restored)
    }

    @Test
    fun tempTocReadAndReaderAddKeepOfficialFlagsSeparate() {
        var state = BookInfoShelfFlags.State(inBookshelf = false, urlOnShelf = false)

        state = BookInfoShelfFlags.afterUrlPersisted(state)
        assertEquals(BookInfoShelfFlags.State(false, false), state)
        assertTrue(BookInfoShelfFlags.canDeleteTempBookUrl("B", "B", persistedIsNotShelf = true))
        assertFalse(state.urlOnShelf)

        state = BookInfoShelfFlags.afterUrlPersisted(state)
        assertFalse(BookInfoShelfFlags.readerInBookshelfExtra(state.urlOnShelf))

        state = BookInfoShelfFlags.afterReaderReturned(
            identityOnShelf = true,
            urlOnShelf = true,
        )
        assertEquals(BookInfoShelfFlags.State(true, true), state)
        assertTrue(BookInfoShelfFlags.readerInBookshelfExtra(state.urlOnShelf))
        assertFalse(BookInfoShelfFlags.canDeleteBookUrl("B", "A"))
        assertFalse(BookInfoShelfFlags.canDeleteTempBookUrl("B", "B", persistedIsNotShelf = false))
    }
}
