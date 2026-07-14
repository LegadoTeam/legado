package io.legado.app.ui.book.toc

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TocCallbackLifecycleTest {

    @Test
    fun ownerClearsItsOwnCallback() {
        val owner = Any()

        assertNull(clearCallbackIfOwned(owner, owner))
    }

    @Test
    fun staleOwnerDoesNotClearNewCallback() {
        val staleOwner = Any()
        val newCallback = Any()

        assertSame(newCallback, clearCallbackIfOwned(newCallback, staleOwner))
    }

    @Test
    fun missingCallbackRemainsMissing() {
        assertNull(clearCallbackIfOwned(null, Any()))
    }
}
