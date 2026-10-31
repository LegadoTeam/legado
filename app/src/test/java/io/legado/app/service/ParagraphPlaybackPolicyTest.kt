package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphPlaybackPolicyTest {
    @Test fun automaticChapterCompletionCannotUndoPauseWhileLoading() {
        assertFalse(shouldPlayParagraphCommand(true, preservePlaybackState = true, paused = true))
        assertTrue(shouldPlayParagraphCommand(true, preservePlaybackState = true, paused = false))
    }

    @Test fun explicitReadFromHereCanStartANewSessionAfterPausing() {
        assertTrue(shouldPlayParagraphCommand(true, preservePlaybackState = false, paused = true))
    }

    @Test fun PreparingAPausedSessionNeverStartsAudio() {
        for (preserve in listOf(false, true)) {
            for (paused in listOf(false, true)) {
                assertFalse(shouldPlayParagraphCommand(false, preserve, paused))
            }
        }
    }

    @Test fun browsingAwayDuringChapterLayoutStartsOneIndependentSpeechLoad() {
        assertTrue(shouldLoadDetachedParagraphChapter(true, following = false, alreadyLoading = false))
        assertFalse(shouldLoadDetachedParagraphChapter(true, following = false, alreadyLoading = true))
        assertFalse(shouldLoadDetachedParagraphChapter(true, following = true, alreadyLoading = false))
        assertFalse(shouldLoadDetachedParagraphChapter(false, following = false, alreadyLoading = false))
    }
}
