package io.legado.app.ui.book.read.paragraph

/** Distinguishes user navigation from layout callbacks and invalidates delayed positioning work. */
internal class ParagraphScrollState {
    private var scrollGeneration = 0L
    private var speechGeneration = 0L
    var isProgrammatic = false
        private set
    private var userScrolling = false

    fun invalidate() {
        scrollGeneration++
        speechGeneration++
        isProgrammatic = false
        userScrolling = false
    }

    fun beginUserScroll() {
        invalidate()
        userScrolling = true
    }

    fun beginProgrammaticScroll(): Long {
        speechGeneration++
        isProgrammatic = true
        userScrolling = false
        return ++scrollGeneration
    }

    fun completeProgrammaticScroll(generation: Long): Boolean {
        if (generation != scrollGeneration || !isProgrammatic) return false
        isProgrammatic = false
        return true
    }

    fun onScrolled(): Boolean {
        if (!userScrolling || isProgrammatic) return false
        speechGeneration++
        return true
    }

    fun onIdle() {
        userScrolling = false
    }

    fun requestSpeechFollow(): Long = ++speechGeneration

    fun isSpeechRequestCurrent(generation: Long): Boolean = generation == speechGeneration
}
