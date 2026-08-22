package io.legado.app.ui.book.info

internal class BookInfoNetworkLoadingCounter(
    private val onLoadingChanged: (Boolean) -> Unit,
) {
    private var activeLoads = 0

    @Synchronized
    fun begin() {
        if (activeLoads++ == 0) onLoadingChanged(true)
    }

    @Synchronized
    fun end() {
        check(activeLoads > 0) { "No active network load" }
        if (--activeLoads == 0) onLoadingChanged(false)
    }
}
