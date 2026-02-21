package io.legado.app.utils

fun <T> ArrayList<T>.indexOf(o: T, startIndex: Int): Int {
    if (startIndex >= this.size) {
        return -2
    }
    for (i in startIndex..<this.size) {
        if (o == this[i]) {
            return i
        }
    }
    return -1
}