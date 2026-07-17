package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechNavigationGateTest {

    private class Recorder {
        var speechSyncCount = 0
            private set
        var restartSpeechCount = 0
            private set

        fun speechDrivenPageAdvance(state: SpeechFollowState) {
            if (state.shouldSyncSpeechNavigation()) {
                speechSyncCount++
            }
        }

        fun userNavigation(state: SpeechFollowState) {
            state.detachForManualNavigation()
        }

        fun oldUserNavigationBehavior() {
            restartSpeechCount++
        }
    }

    @Test
    fun manualNavigationDetachesFollowWithoutRestartingSpeech() {
        val state = SpeechFollowState()
        val recorder = Recorder()

        recorder.userNavigation(state)

        assertFalse(state.followReadAloudPosition)
        assertEquals(0, recorder.restartSpeechCount)
    }

    @Test
    fun speechDrivenPageAdvanceFollowsOnlyWhileAttached() {
        val state = SpeechFollowState()
        val recorder = Recorder()

        recorder.speechDrivenPageAdvance(state)
        recorder.userNavigation(state)
        recorder.speechDrivenPageAdvance(state)
        state.restoreForNewSpeechSession()
        recorder.speechDrivenPageAdvance(state)

        assertEquals(2, recorder.speechSyncCount)
        assertTrue(state.followReadAloudPosition)
    }

    @Test
    fun detachedSpeechProgressDoesNotApplyToVisibleReader() {
        val state = SpeechFollowState()

        state.detachForManualNavigation()

        assertFalse(state.shouldApplySpeechProgressToVisibleReader(isSpeechPlaying = true))
    }

    @Test
    fun attachedSpeechProgressAppliesOnlyWhileSpeechIsPlaying() {
        val state = SpeechFollowState()

        assertTrue(state.shouldApplySpeechProgressToVisibleReader(isSpeechPlaying = true))
        assertFalse(state.shouldApplySpeechProgressToVisibleReader(isSpeechPlaying = false))
    }

    @Test
    fun detachedSpeechChapterAdvanceDoesNotStopWhenOnlyVisibleSyncWasSuppressed() {
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
    fun detachedSpeechChapterAdvanceStopsAtRealBookEnd() {
        val state = SpeechFollowState()

        state.detachForManualNavigation()

        assertEquals(
            SpeechFollowState.NextChapterDecision.Stop,
            state.nextChapterDecision(
                hasNextSpeechChapter = false,
                visibleSyncMoved = false
            )
        )
    }

    @Test
    fun attachedSpeechChapterAdvanceStillStopsWhenVisibleSyncFails() {
        val state = SpeechFollowState()

        assertEquals(
            SpeechFollowState.NextChapterDecision.Stop,
            state.nextChapterDecision(
                hasNextSpeechChapter = true,
                visibleSyncMoved = false
            )
        )
    }
}
