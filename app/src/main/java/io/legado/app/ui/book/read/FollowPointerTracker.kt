package io.legado.app.ui.book.read

/** Pointer IDs stay stable when Android reorders indices after a finger lifts. */
internal class FollowPointerTracker {
    data class Pointer(val id: Int, val x: Float, val y: Float)
    data class Movement(val dx: Float, val dy: Float, val deltaY: Float)
    private var activeId: Int? = null
    private var originX = 0f
    private var originY = 0f
    private var lastY = 0f
    var hadMultiplePointers = false
        private set

    private fun adopt(pointer: Pointer?) {
        activeId = pointer?.id
        if (pointer != null) {
            originX = pointer.x
            originY = pointer.y
            lastY = pointer.y
        }
    }

    fun begin(pointer: Pointer) {
        hadMultiplePointers = false
        adopt(pointer)
    }

    fun pointerDown(pointer: Pointer) {
        hadMultiplePointers = true
        adopt(pointer)
    }

    fun pointerUp(id: Int, remaining: List<Pointer>) {
        hadMultiplePointers = true
        if (activeId == id) adopt(remaining.lastOrNull())
    }

    fun move(pointers: List<Pointer>): Movement {
        val pointer = pointers.firstOrNull { it.id == activeId }
        if (pointer == null) {
            adopt(pointers.lastOrNull())
            return Movement(0f, 0f, 0f)
        }
        val movement = Movement(pointer.x - originX, pointer.y - originY, pointer.y - lastY)
        lastY = pointer.y
        return movement
    }

    fun clear() { activeId = null }
}
