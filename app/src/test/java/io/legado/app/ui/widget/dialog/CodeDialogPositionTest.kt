package io.legado.app.ui.widget.dialog

import org.junit.Assert.assertEquals
import org.junit.Test

class CodeDialogPositionTest {

    @Test
    fun `no scrollable content starts at zero`() {
        assertEquals(0, resolveCodeDialogPositionProgress(10, 0))
    }

    @Test
    fun `progress is clamped to the scroll range`() {
        assertEquals(0, resolveCodeDialogPositionProgress(-10, 100))
        assertEquals(10000, resolveCodeDialogPositionProgress(110, 100))
    }

    @Test
    fun `progress maps the middle of the scroll range`() {
        assertEquals(5000, resolveCodeDialogPositionProgress(50, 100))
    }
}
