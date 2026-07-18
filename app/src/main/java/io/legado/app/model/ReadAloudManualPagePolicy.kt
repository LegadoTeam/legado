package io.legado.app.model

object ReadAloudManualPagePolicy {

    fun shouldRestartFromVisiblePage(
        isReadAloudRunning: Boolean,
        speechDrivenNavigation: Boolean,
        followManualPageTurns: Boolean
    ): Boolean {
        return isReadAloudRunning && !speechDrivenNavigation && followManualPageTurns
    }
}
