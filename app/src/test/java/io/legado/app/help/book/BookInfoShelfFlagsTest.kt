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
    fun tocCancelDeletesOnlyTheCurrentUrl() {
        assertTrue(BookInfoShelfFlags.canDeleteBookUrl("B", "B"))
        assertFalse(BookInfoShelfFlags.canDeleteBookUrl("B", "A"))
        assertFalse(BookInfoShelfFlags.canDeleteBookUrl("B", null))
        assertFalse(BookInfoShelfFlags.canDeleteBookUrl("", "B"))
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
        assertTrue(BookInfoShelfFlags.canDeleteBookUrl("B", "B"))
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
    }
}
