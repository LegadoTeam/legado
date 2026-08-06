package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Test

class PullBookmarkGestureTest {

    @Test
    fun `only downward vertical pulls are consumed`() {
        assertEquals(
            PullBookmarkGestureState.NONE,
            classifyPullBookmarkGesture(0f, -80f, 8, 48),
        )
        assertEquals(
            PullBookmarkGestureState.NONE,
            classifyPullBookmarkGesture(80f, 40f, 8, 48),
        )
        assertEquals(
            PullBookmarkGestureState.PULLING,
            classifyPullBookmarkGesture(4f, 24f, 8, 48),
        )
        assertEquals(
            PullBookmarkGestureState.READY,
            classifyPullBookmarkGesture(4f, 48f, 8, 48),
        )
    }
}
