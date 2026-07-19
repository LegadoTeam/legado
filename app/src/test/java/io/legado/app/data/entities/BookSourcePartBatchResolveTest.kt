package io.legado.app.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSourcePartBatchResolveTest {

    @Test
    fun `query chunk size keeps sqlite bind headroom`() {
        assertEquals(900, BOOK_SOURCE_QUERY_CHUNK_SIZE)
    }

    @Test
    fun `query keys are unique and split below sqlite bind limit`() {
        val expectedKeys = (0..BOOK_SOURCE_QUERY_CHUNK_SIZE).map { "source-$it" }
        val parts = expectedKeys.map { BookSourcePart(bookSourceUrl = it) } +
            BookSourcePart(bookSourceUrl = expectedKeys.first())

        val chunks = parts.bookSourceKeyChunks()

        assertEquals(listOf(BOOK_SOURCE_QUERY_CHUNK_SIZE, 1), chunks.map { it.size })
        assertEquals(expectedKeys, chunks.flatten())
        assertTrue(chunks.all { it.size <= BOOK_SOURCE_QUERY_CHUNK_SIZE })
    }

    @Test
    fun `resolved sources keep selection order missing filtering and duplicates`() {
        val parts = listOf(
            BookSourcePart(bookSourceUrl = "source-b"),
            BookSourcePart(bookSourceUrl = "source-a"),
            BookSourcePart(bookSourceUrl = "missing"),
            BookSourcePart(bookSourceUrl = "source-b"),
        )
        val sourceA = BookSource(bookSourceUrl = "source-a")
        val sourceB = BookSource(bookSourceUrl = "source-b")

        val resolved = parts.orderResolvedBookSources(listOf(sourceA, sourceB))

        assertEquals(listOf(sourceB, sourceA, sourceB), resolved)
    }

    @Test
    fun `empty selection does not produce database chunks`() {
        assertTrue(emptyList<BookSourcePart>().bookSourceKeyChunks().isEmpty())
        assertTrue(emptyList<BookSourcePart>().orderResolvedBookSources(emptyList()).isEmpty())
    }
}
