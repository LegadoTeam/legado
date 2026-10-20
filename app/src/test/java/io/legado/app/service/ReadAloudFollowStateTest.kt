package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudFollowStateTest {

    @Test
    fun followPositionCanDetachAndRestore() {
        val state = SpeechFollowState()

        assertTrue(state.followReadAloudPosition)

        state.detachForManualNavigation()
        assertFalse(state.followReadAloudPosition)

        state.restoreForNewSpeechSession()
        assertTrue(state.followReadAloudPosition)
    }

    @Test
    fun manualNavigationKeepsSpeechNavigationDetachedUntilNewSession() {
        val state = SpeechFollowState()

        state.detachForManualNavigation()

        assertFalse(state.shouldSyncSpeechNavigation())
        assertFalse(state.followReadAloudPosition)

        state.restoreForNewSpeechSession()
        assertTrue(state.shouldSyncSpeechNavigation())
    }
    @Test
    fun pendingSeekRejectsOldFollowRestoration() {
        val state = SpeechFollowState()
        val token = state.beginPositionChange()
        state.restoreForNewSpeechSession()
        assertFalse(state.shouldApplySpeechProgressToVisibleReader(true))
        assertTrue(state.finishPositionChange(token))
        state.restoreForNewSpeechSession()
        assertTrue(state.shouldApplySpeechProgressToVisibleReader(true))
    }

    @Test
    fun stalePreparationCannotFinishNewSeek() {
        val state = SpeechFollowState()
        val old = state.beginPositionChange()
        val current = state.beginPositionChange()
        assertFalse(state.finishPositionChange(old))
        state.restoreForNewSpeechSession()
        assertFalse(state.followReadAloudPosition)
        assertTrue(state.finishPositionChange(current))
    }

    @Test
    fun failedSeekCanRemainDetachedAndStopClearsPending() {
        val state = SpeechFollowState()
        val token = state.beginPositionChange()
        assertTrue(state.finishPositionChange(token))
        assertFalse(state.followReadAloudPosition)
        val next = state.beginPositionChange()
        state.cancelPositionChanges()
        assertFalse(state.finishPositionChange(next))
        state.restoreForNewSpeechSession()
        assertTrue(state.followReadAloudPosition)
    }
}
