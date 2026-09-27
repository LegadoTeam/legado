package io.legado.app.ui.widget.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollTouchSelectionRecoveryTest {

    @Test
    fun `recovers only the missing selection span failure`() {
        assertTrue(
            IndexOutOfBoundsException("setSpan (-1 ... -1) starts before 0")
                .isMissingSelectionSpan()
        )
        assertFalse(
            IndexOutOfBoundsException("setSpan (2 ... 1) has invalid range")
                .isMissingSelectionSpan()
        )
        assertFalse(IndexOutOfBoundsException().isMissingSelectionSpan())
    }
}
