package io.legado.app.service

class SpeechFollowState {

    enum class NextChapterDecision {
        ContinueWithVisibleSync,
        ContinueSpeechOnly,
        Stop
    }

    var followReadAloudPosition: Boolean = true
        private set

    fun detachForManualNavigation() {
        followReadAloudPosition = false
    }

    fun restoreForNewSpeechSession() {
        followReadAloudPosition = true
    }

    fun shouldSyncSpeechNavigation(): Boolean {
        return followReadAloudPosition
    }

    fun shouldApplySpeechProgressToVisibleReader(isSpeechPlaying: Boolean): Boolean {
        return isSpeechPlaying && followReadAloudPosition
    }

    fun nextChapterDecision(
        hasNextSpeechChapter: Boolean,
        visibleSyncMoved: Boolean
    ): NextChapterDecision {
        if (!hasNextSpeechChapter) {
            return NextChapterDecision.Stop
        }
        if (visibleSyncMoved) {
            return NextChapterDecision.ContinueWithVisibleSync
        }
        return if (followReadAloudPosition) {
            NextChapterDecision.Stop
        } else {
            NextChapterDecision.ContinueSpeechOnly
        }
    }
}
