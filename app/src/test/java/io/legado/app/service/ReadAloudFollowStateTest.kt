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
}
