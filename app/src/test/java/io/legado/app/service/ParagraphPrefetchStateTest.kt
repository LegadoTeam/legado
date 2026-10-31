package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphPrefetchStateTest {
    @Test fun readyTargetAndDuplicateLayoutNotificationsStartOnlyOneAttempt() {
        val state = ParagraphPrefetchState<String>()
        state.arm("book:chapter:session")
        assertTrue(state.claimAttempt("book:chapter:session"))
        repeat(100) {
            state.arm("book:chapter:session")
            assertFalse(state.claimAttempt("book:chapter:session"))
        }
    }

    @Test fun waitingDoesNotStartAnAttemptUntilAReadyEventArrives() {
        val state = ParagraphPrefetchState<String>()
        state.arm("next")
        repeat(100) { assertTrue(state.isPending("next")) }
        assertTrue(state.claimAttempt("next"))
        assertFalse(state.isPending("next"))
    }

    @Test fun sharedAndPrivateLayoutRaceStillStartsOnlyOneDownload() {
        val state = ParagraphPrefetchState<String>()
        state.arm("next")
        assertTrue(state.claimLoad("next"))
        assertFalse(state.claimLoad("next"))
        assertTrue(state.claimAttempt("next"))
        assertFalse(state.claimAttempt("next"))
        assertFalse(state.claimLoad("next"))
    }

    @Test fun failureDoesNotRearmTheSameTarget() {
        val state = ParagraphPrefetchState<String>()
        state.arm("next")
        assertTrue(state.claimAttempt("next"))
        state.arm("next")
        assertFalse(state.claimAttempt("next"))
    }

    @Test fun newSessionDiscardsLateResultsButAllowsItsOwnAttempt() {
        val state = ParagraphPrefetchState<String>()
        state.arm("old-session")
        state.arm("new-session")
        assertFalse(state.claimAttempt("old-session"))
        assertFalse(state.claimLoad("old-session"))
        assertTrue(state.claimAttempt("new-session"))
    }

    @Test fun stoppingDiscardsWaitingAndAlreadyLoadingTargets() {
        val state = ParagraphPrefetchState<String>()
        state.arm("next")
        state.claimLoad("next")
        state.clear()
        assertFalse(state.isPending("next"))
        assertFalse(state.claimAttempt("next"))
        assertFalse(state.claimLoad("next"))
    }
}
