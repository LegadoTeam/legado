package io.legado.app.lib.cronet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CronetDownloadStateTest {

    @Test
    fun `only one download can run at a time`() {
        val state = CronetDownloadState()

        assertTrue(state.tryStart())
        assertTrue(state.isRunning)
        assertFalse(state.tryStart())
    }

    @Test
    fun `download can retry after completion`() {
        val state = CronetDownloadState()

        assertTrue(state.tryStart())
        state.finish()

        assertFalse(state.isRunning)
        assertTrue(state.tryStart())
    }
}
