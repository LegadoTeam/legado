package io.legado.app.utils.canvasrecorder

import androidx.annotation.CallSuper
import java.util.concurrent.atomic.AtomicLong

abstract class BaseCanvasRecorder : CanvasRecorder {

    private val state = CanvasRecorderState()

    override fun invalidate() {
        state.invalidate()
    }

    @CallSuper
    override fun recycle() {
        state.invalidate()
    }

    protected fun markRecordingStarted() {
        state.markRecordingStarted()
    }

    @CallSuper
    override fun endRecording() {
        state.markRecordingFinished()
    }

    override fun isDirty(): Boolean {
        return state.isDirty()
    }

    override fun isLocked(): Boolean {
        return false
    }

    override fun needRecord(): Boolean {
        return isDirty() && !isLocked()
    }

}

internal class CanvasRecorderState {

    private val invalidationVersion = AtomicLong()

    @Volatile
    private var recordingVersion = Long.MIN_VALUE

    @Volatile
    private var renderedVersion = Long.MIN_VALUE

    fun invalidate() {
        invalidationVersion.incrementAndGet()
    }

    fun markRecordingStarted() {
        recordingVersion = invalidationVersion.get()
    }

    fun markRecordingFinished() {
        renderedVersion = recordingVersion
    }

    fun isDirty(): Boolean {
        return renderedVersion != invalidationVersion.get()
    }
}
