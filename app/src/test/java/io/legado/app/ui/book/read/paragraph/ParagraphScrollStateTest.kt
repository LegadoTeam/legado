package io.legado.app.ui.book.read.paragraph

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphScrollStateTest {
    @Test
    fun imageLayoutDoesNotCancelPendingSpeechFollow() {
        val state = ParagraphScrollState()
        val request = state.requestSpeechFollow()
        state.onScrolled()
        assertTrue(state.isSpeechRequestCurrent(request))
    }

    @Test
    fun userNavigationCancelsQueuedFollowAndOldPositioningCompletion() {
        val state = ParagraphScrollState()
        val positioning = state.beginProgrammaticScroll()
        val follow = state.requestSpeechFollow()
        state.beginUserScroll()
        assertFalse(state.isSpeechRequestCurrent(follow))
        assertFalse(state.completeProgrammaticScroll(positioning))
        assertFalse(state.isProgrammatic)
    }

    @Test
    fun oldLayoutCallbackCannotFinishNewViewportRestoration() {
        val state = ParagraphScrollState()
        val old = state.beginProgrammaticScroll()
        val restored = state.beginProgrammaticScroll()
        assertFalse(state.completeProgrammaticScroll(old))
        assertTrue(state.isProgrammatic)
        assertTrue(state.completeProgrammaticScroll(restored))
        assertFalse(state.isProgrammatic)
    }

    @Test
    fun imageRelayoutAfterUserScrollStopsDoesNotCancelNewFollow() {
        val state = ParagraphScrollState()
        state.beginUserScroll()
        state.onScrolled()
        state.onIdle()
        val request = state.requestSpeechFollow()
        state.onScrolled()
        assertTrue(state.isSpeechRequestCurrent(request))
    }

    @Test
    fun changedChapterInvalidatesPendingWork() {
        val state = ParagraphScrollState()
        val positioning = state.beginProgrammaticScroll()
        val speech = state.requestSpeechFollow()
        state.invalidate()
        assertFalse(state.completeProgrammaticScroll(positioning))
        assertFalse(state.isSpeechRequestCurrent(speech))
    }
}
