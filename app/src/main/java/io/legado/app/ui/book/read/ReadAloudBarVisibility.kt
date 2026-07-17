package io.legado.app.ui.book.read

object ReadAloudBarVisibility {
    fun shouldShow(isRun: Boolean, following: Boolean, menuVisible: Boolean): Boolean =
        isRun && !following && !menuVisible
}
