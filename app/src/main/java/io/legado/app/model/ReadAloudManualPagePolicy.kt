package io.legado.app.model

object ReadAloudManualPagePolicy {

    fun shouldRestartFromVisiblePage(
        isReadAloudRunning: Boolean,
        speechDrivenNavigation: Boolean,
        followManualPageTurns: Boolean,
        followingReadAloudPosition: Boolean
    ): Boolean {
        return isReadAloudRunning && !speechDrivenNavigation && followManualPageTurns &&
                followingReadAloudPosition
    }
}
