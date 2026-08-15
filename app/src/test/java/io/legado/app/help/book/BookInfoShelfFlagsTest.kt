package io.legado.app.help.book

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookInfoShelfFlagsTest {

    @Test
    fun tocCancelDeletesOnlyTheCurrentNotShelfUrl() {
        assertTrue(BookInfoShelfFlags.canDeleteBookUrl("B", "B"))
        assertFalse(BookInfoShelfFlags.canDeleteBookUrl("B", "A"))
    }
}
