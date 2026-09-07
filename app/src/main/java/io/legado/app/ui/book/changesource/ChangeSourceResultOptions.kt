package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.SearchBook

internal object ChangeSourceResultOptions {

    const val FILTER_OFF = 0
    const val FILTER_ABSOLUTE = 1
    const val FILTER_RELATIVE = 2

    fun apply(
        books: List<SearchBook>,
        filterMode: Int,
        minimum: Int,
        maximum: Int,
        referenceWordCount: Int?,
        comparator: Comparator<SearchBook>,
        pinnedBookUrl: String? = null,
    ): List<SearchBook> {
        val lower = minOf(minimum, maximum).coerceAtLeast(0)
        val upper = maxOf(minimum, maximum).coerceAtLeast(0)
        val pinned = pinnedBookUrl?.let { url -> books.firstOrNull { it.bookUrl == url } }
        val results = books.asSequence()
            .filter { pinned == null || it.bookUrl != pinned.bookUrl }
            .filter { book ->
                if (!book.hasMeasurement()) return@filter true
                when (filterMode) {
                    FILTER_ABSOLUTE -> book.chapterWordCount in lower..upper
                    FILTER_RELATIVE -> referenceWordCount
                        ?.takeIf { it > 0 }
                        ?.let { reference ->
                            val count = book.chapterWordCount.toLong() * 100L
                            count >= reference.toLong() * lower &&
                                    count <= reference.toLong() * upper
                        }
                        ?: true

                    else -> true
                }
            }
            .sortedWith(comparator)
            .toList()
        return if (pinned == null) results else listOf(pinned) + results
    }

    fun responseTimeComparator(
        fallback: Comparator<SearchBook>,
    ): Comparator<SearchBook> = Comparator { first, second ->
        val firstMeasured = first.hasMeasurement()
        val secondMeasured = second.hasMeasurement()
        when {
            firstMeasured != secondMeasured -> if (firstMeasured) -1 else 1
            firstMeasured -> first.respondTime.compareTo(second.respondTime)
                .takeIf { it != 0 }
                ?: fallback.compare(first, second)

            else -> fallback.compare(first, second)
        }
    }

    fun measuredFirstComparator(
        fallback: Comparator<SearchBook>,
    ): Comparator<SearchBook> = Comparator { first, second ->
        val firstMeasured = first.hasMeasurement()
        val secondMeasured = second.hasMeasurement()
        if (firstMeasured == secondMeasured) {
            fallback.compare(first, second)
        } else if (firstMeasured) {
            -1
        } else {
            1
        }
    }

    private fun SearchBook.hasMeasurement(): Boolean =
        chapterWordCount >= 0 && respondTime >= 0
}
