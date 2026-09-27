package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Test

class ChangeSourceResultOptionsTest {

    private val fallback = compareBy<SearchBook> { it.originOrder }

    @Test
    fun `disabled filter keeps default ordering`() {
        val books = listOf(
            book("second", 2000, 100, 2),
            book("first", -1, -1, 1),
        )

        val result = apply(books)

        assertEquals(listOf("first", "second"), result.map { it.bookUrl })
    }

    @Test
    fun `response time sorts measured results and keeps failures last`() {
        val books = listOf(
            book("slow", 2000, 300, 1),
            book("missing-count", -1, 10, 2),
            book("fast", 2000, 80, 3),
            book("missing-time", 2000, -1, 4),
        )

        val result = apply(
            books,
            comparator = ChangeSourceResultOptions.responseTimeComparator(fallback),
        )

        assertEquals(
            listOf("fast", "slow", "missing-count", "missing-time"),
            result.map { it.bookUrl },
        )
    }

    @Test
    fun `absolute range includes boundaries and retains failures`() {
        val books = listOf(
            book("below", 999, 10, 1),
            book("minimum", 1000, 10, 2),
            book("maximum", 5000, 10, 3),
            book("above", 5001, 10, 4),
            book("failure", -1, 10, 0),
        )

        val result = apply(
            books,
            filterMode = ChangeSourceResultOptions.FILTER_ABSOLUTE,
            minimum = 1000,
            maximum = 5000,
            comparator = ChangeSourceResultOptions.measuredFirstComparator(fallback),
        )

        assertEquals(
            listOf("minimum", "maximum", "failure"),
            result.map { it.bookUrl },
        )
    }

    @Test
    fun `relative range uses inclusive integer percentage boundaries`() {
        val books = listOf(
            book("below", 1399, 10, 1),
            book("minimum", 1400, 10, 2),
            book("maximum", 2600, 10, 3),
            book("above", 2601, 10, 4),
            book("failure", 2000, -1, 5),
        )

        val result = apply(
            books,
            filterMode = ChangeSourceResultOptions.FILTER_RELATIVE,
            minimum = 70,
            maximum = 130,
            referenceWordCount = 2000,
        )

        assertEquals(
            listOf("minimum", "maximum", "failure"),
            result.map { it.bookUrl },
        )
    }

    @Test
    fun `missing relative reference does not hide results`() {
        val books = listOf(
            book("one", 100, 10, 1),
            book("two", 10000, 10, 2),
        )

        val result = apply(
            books,
            filterMode = ChangeSourceResultOptions.FILTER_RELATIVE,
            minimum = 70,
            maximum = 130,
            referenceWordCount = null,
        )

        assertEquals(listOf("one", "two"), result.map { it.bookUrl })
    }

    @Test
    fun `filter and response sorting compose`() {
        val books = listOf(
            book("slow", 2000, 300, 1),
            book("hidden", 6000, 20, 2),
            book("fast", 2500, 80, 3),
        )

        val result = apply(
            books,
            filterMode = ChangeSourceResultOptions.FILTER_ABSOLUTE,
            minimum = 1000,
            maximum = 3000,
            comparator = ChangeSourceResultOptions.responseTimeComparator(fallback),
        )

        assertEquals(listOf("fast", "slow"), result.map { it.bookUrl })
    }

    @Test
    fun `pinned source stays first while other sources are filtered and sorted`() {
        val books = listOf(
            book("current", 8000, 500, 1),
            book("hidden", 6000, 20, 2),
            book("slow", 2000, 300, 3),
            book("fast", 2500, 80, 4),
        )

        val result = apply(
            books,
            filterMode = ChangeSourceResultOptions.FILTER_ABSOLUTE,
            minimum = 1000,
            maximum = 3000,
            comparator = ChangeSourceResultOptions.responseTimeComparator(fallback),
            pinnedBookUrl = "current",
        )

        assertEquals(listOf("current", "fast", "slow"), result.map { it.bookUrl })
    }

    private fun apply(
        books: List<SearchBook>,
        filterMode: Int = ChangeSourceResultOptions.FILTER_OFF,
        minimum: Int = 0,
        maximum: Int = 0,
        referenceWordCount: Int? = null,
        comparator: Comparator<SearchBook> = fallback,
        pinnedBookUrl: String? = null,
    ) = ChangeSourceResultOptions.apply(
        books = books,
        filterMode = filterMode,
        minimum = minimum,
        maximum = maximum,
        referenceWordCount = referenceWordCount,
        comparator = comparator,
        pinnedBookUrl = pinnedBookUrl,
    )

    private fun book(
        id: String,
        wordCount: Int,
        respondTime: Int,
        order: Int,
    ) = SearchBook(
        bookUrl = id,
        chapterWordCount = wordCount,
        respondTime = respondTime,
        originOrder = order,
    )
}
