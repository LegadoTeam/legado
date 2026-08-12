package io.legado.app.utils.canvasrecorder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasRecorderStateTest {

    @Test
    fun `invalidation during recording remains dirty`() {
        val state = CanvasRecorderState()

        state.markRecordingStarted()
        state.markRecordingFinished()
        assertFalse(state.isDirty())

        state.invalidate()
        state.markRecordingStarted()
        state.invalidate()
        state.markRecordingFinished()
        assertTrue(state.isDirty())

        state.markRecordingStarted()
        state.markRecordingFinished()
        assertFalse(state.isDirty())
    }
}
