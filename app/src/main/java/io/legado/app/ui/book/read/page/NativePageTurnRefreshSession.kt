package io.legado.app.ui.book.read.page

/**
 * Optional app-owned cleaning counts committed page frames, not input or layout callbacks.
 * Ocean 6 Pro normally delegates periodic cleaning to the system; a positive interval is
 * an explicit user override. Arming alone never advances the counter.
 */
internal class NativePageTurnRefreshSession {
    private data class Submission(val frame: NativePageTurnGate.Frame, val fullRefresh: Boolean)

    private var bookUrl: String? = null
    private var interval: Int? = null
    private var pending: Submission? = null
    var turnsSinceFullRefresh: Int = 0
        private set

    fun isFullRefreshDue(targetBookUrl: String, interval: Int): Boolean {
        if (bookUrl != targetBookUrl || this.interval != interval) {
            bookUrl = targetBookUrl
            this.interval = interval
            pending = null
            turnsSinceFullRefresh = 0
        }
        if (interval <= 0) return false
        return turnsSinceFullRefresh >= interval - 1
    }

    /** Called only after the firmware command pair completes without throwing. */
    fun arm(frame: NativePageTurnGate.Frame, fullRefresh: Boolean) {
        if (bookUrl != frame.bookUrl) {
            bookUrl = frame.bookUrl
            turnsSinceFullRefresh = 0
        }
        pending = Submission(frame, fullRefresh)
    }

    fun frameDrawn(frame: NativePageTurnGate.Frame?) {
        val submission = pending ?: return
        pending = null
        // Once a draw occurs, its request cannot be credited to some later page.
        // Loading frames before arming are handled by NativePageTurnGate instead.
        if (frame != submission.frame) return
        if (submission.fullRefresh) {
            turnsSinceFullRefresh = 0
        } else if (turnsSinceFullRefresh < Int.MAX_VALUE) {
            turnsSinceFullRefresh++
        }
    }

    fun cancelPending() {
        pending = null
    }
}
