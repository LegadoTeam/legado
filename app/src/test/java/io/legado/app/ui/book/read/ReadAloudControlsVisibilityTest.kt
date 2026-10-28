package io.legado.app.ui.book.read

import org.junit.Assert.*
import org.junit.Test

class ReadAloudControlsVisibilityTest {
    @Test fun switchingOffDragRestoresPositionControls() {
        assertFalse(readAloudControlsVisible(true, true, false, false, true, true))
        assertTrue(readAloudControlsVisible(true, false, false, false, true, true))
    }
    @Test fun positionControlsWorkWhenPauseIsDisabled() {
        assertTrue(readAloudControlsVisible(true, false, false, false, false, true))
        assertFalse(readAloudControlsVisible(true, false, false, false, false, false))
    }
    @Test fun menuAutoHideAndStoppedServiceStillHideControls() {
        assertFalse(readAloudControlsVisible(true, false, true, false, true, true))
        assertFalse(readAloudControlsVisible(true, false, false, true, true, true))
        assertFalse(readAloudControlsVisible(false, false, false, false, true, true))
    }
}
