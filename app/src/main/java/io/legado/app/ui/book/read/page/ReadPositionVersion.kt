package io.legado.app.ui.book.read.page

internal class ReadPositionVersion {

    @Volatile
    private var value = 0L

    fun snapshot(): Long = value

    fun markChanged() {
        value++
    }

    fun isCurrent(snapshot: Long): Boolean = value == snapshot
}
