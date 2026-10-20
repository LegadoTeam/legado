package io.legado.app.ui.book.read

/** A position equal to a page end belongs to the following page, when there is one. */
internal fun contentSearchPageIndex(pageLengths: List<Int>, position: Int): Int {
    var pageIndex = 0
    var end = pageLengths.firstOrNull() ?: return 0
    while (end <= position && pageIndex + 1 < pageLengths.size) {
        end += pageLengths[++pageIndex]
    }
    return pageIndex
}
