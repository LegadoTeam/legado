package io.legado.app.ui.book.read

object ReadAloudBarVisibility {
    fun shouldShow(
        isRun: Boolean,
        following: Boolean,
        menuVisible: Boolean,
        pauseEnabled: Boolean = true,
        positionEnabled: Boolean = true,
    ): Boolean = isRun && (if (following) pauseEnabled else positionEnabled) && !menuVisible
}
