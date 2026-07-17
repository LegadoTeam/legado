package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpeechFollowStateTest {

    @Test
    fun detachedFollowDoesNotApplySpeechProgressToVisibleReader() {
        val state = SpeechFollowState()

        state.detachForManualNavigation()

        assertFalse(state.shouldApplySpeechProgressToVisibleReader(isSpeechPlaying = true))
    }

    @Test
    fun detachedFollowDoesNotStopSolelyBecauseVisibleChapterSyncWasSkipped() {
        val state = SpeechFollowState()

        state.detachForManualNavigation()

        assertNotEquals(
            SpeechFollowState.NextChapterDecision.Stop,
            state.nextChapterDecision(
                hasNextSpeechChapter = true,
                visibleSyncMoved = false
            )
        )
    }

    @Test
    fun detachedNextChapterContinuationStartsNextSpeechChapterWithoutVisibleSync() {
        val state = SpeechFollowState()

        state.detachForManualNavigation()
        val decision = state.nextChapterDecision(
            hasNextSpeechChapter = true,
            visibleSyncMoved = false
        )

        assertEquals(SpeechFollowState.NextChapterDecision.ContinueSpeechOnly, decision)
    }

    @Test
    fun attachedNextChapterContinuationUsesVisibleSyncWhenItMoved() {
        val state = SpeechFollowState()

        val decision = state.nextChapterDecision(
            hasNextSpeechChapter = true,
            visibleSyncMoved = true
        )

        assertEquals(SpeechFollowState.NextChapterDecision.ContinueWithVisibleSync, decision)
    }

    @Test
    fun noNextChapterStopsSpeech() {
        val state = SpeechFollowState()

        state.detachForManualNavigation()
        val decision = state.nextChapterDecision(
            hasNextSpeechChapter = false,
            visibleSyncMoved = false
        )

        assertEquals(SpeechFollowState.NextChapterDecision.Stop, decision)
    }
}
