package io.legado.app.ui.book.read.page

/**
 * A page-turn intent is valid only for its target reading position and display rotation.
 * Loading/status frames may be drawn while a chapter is laid out; they never consume it.
 * Several turns before one draw collapse to the final target instead of queuing waveforms.
 */
internal class NativePageTurnGate {

    data class Position(val bookUrl: String, val chapterIndex: Int, val chapterPosition: Int)
    data class Frame(
        val bookUrl: String,
        val chapterIndex: Int,
        val pageIndex: Int,
        val chapterPosition: Int,
    )

    private data class Pending(
        val position: Position,
        val forward: Boolean,
        val rotation: Int,
        val positionVersion: Long,
    )

    private var pending: Pending? = null
    private var drawnFrame: Frame? = null

    val hasPending: Boolean get() = pending != null

    /** Capture the ready page before the first navigation after effects were reconfigured. */
    fun seedIfNeeded(frame: Frame?) {
        if (drawnFrame == null && pending == null && frame != null) drawnFrame = frame
    }

    fun request(position: Position, forward: Boolean, rotation: Int, positionVersion: Long) {
        pending = Pending(position, forward, rotation, positionVersion)
    }

    /** Null means this frame must not issue a vendor command. */
    fun consume(
        position: Position?,
        frame: Frame?,
        rotation: Int,
        positionVersion: Long,
        enabled: Boolean,
    ): Boolean? {
        val request = pending ?: return null
        if (!enabled || position != request.position || rotation != request.rotation ||
            positionVersion != request.positionVersion
        ) {
            clear()
            return null
        }
        if (frame == null) return null
        // A stale binding can still be on screen while the target chapter is loading.
        if (frame.bookUrl != request.position.bookUrl ||
            frame.chapterIndex != request.position.chapterIndex
        ) {
            return null
        }
        clear()
        val previous = drawnFrame?.takeIf { it.bookUrl == frame.bookUrl }
            ?: return request.forward
        // Coalesced requests may change direction several times before a draw. Use the
        // final displacement from the displayed page, with character anchors stable
        // across layout changes, rather than the direction of the last input event.
        val order = if (frame.chapterIndex != previous.chapterIndex) {
            frame.chapterIndex.compareTo(previous.chapterIndex)
        } else {
            frame.chapterPosition.compareTo(previous.chapterPosition)
        }
        return when {
            order > 0 -> true
            order < 0 -> false
            else -> null
        }
    }

    fun frameDrawn(frame: Frame?) {
        if (frame != null) drawnFrame = frame
    }

    fun clear() {
        pending = null
    }

    /** Configuration changes invalidate the old display anchor as well as pending input. */
    fun reset() {
        clear()
        drawnFrame = null
    }
}
