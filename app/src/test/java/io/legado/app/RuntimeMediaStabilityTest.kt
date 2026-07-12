package io.legado.app

import io.legado.app.data.entities.Book
import io.legado.app.service.buildHttpTtsCacheFileName
import io.legado.app.ui.book.info.normalizeWebFileName
import io.legado.app.utils.calculateSvgBitmapSize
import io.legado.app.utils.isForegroundServiceStartDenied
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMediaStabilityTest {

    @Test
    fun webFileNameNormalizesDeclaredSuffix() {
        assertEquals("book.txt", normalizeWebFileName("book.txt", "txt"))
        assertEquals("book.TXT", normalizeWebFileName("book.TXT", ".txt"))
        assertEquals("book.epub", normalizeWebFileName("book.txt", "epub"))
        assertEquals("book.txt", normalizeWebFileName("book", " txt "))
        assertEquals("book.txt", normalizeWebFileName("book.", "txt"))
        assertEquals("book", normalizeWebFileName("book", "..."))
        assertEquals("book", normalizeWebFileName("book", "../txt"))
        assertEquals(
            "Version 2.0.txt",
            normalizeWebFileName("Version 2.0", "txt", replaceExistingSuffix = false)
        )
    }

    @Test
    fun svgBitmapSizeFitsAndFillsTargetBounds() {
        assertEquals(100 to 50, calculateSvgBitmapSize(200, 100, 100, 100))
        assertEquals(200 to 100, calculateSvgBitmapSize(100, 50, 200, 200))
        assertEquals(100 to 67, calculateSvgBitmapSize(150, 100, 100, 100))
        assertEquals(2048 to 2048, calculateSvgBitmapSize(100, 100, 100_000, 100_000))
        assertEquals(4 to 4096, calculateSvgBitmapSize(1, 1000, 100_000, null))
    }

    @Test
    fun customCoverOnlyInheritsIdentityOnTheSameOrigin() {
        val book = Book(
            origin = "https://books.example.com",
            coverUrl = "https://books.example.com/cover.jpg",
        )
        assertEquals(book.origin, book.getCoverSourceOrigin())

        book.customCoverUrl = "https://books.example.com/custom-cover.jpg"
        assertEquals(book.origin, book.getCoverSourceOrigin())

        book.customCoverUrl = "https://images.example.com/cover.jpg"
        assertNull(book.getCoverSourceOrigin())

        book.customCoverUrl = "https://images.example.net/cover.jpg"
        assertNull(book.getCoverSourceOrigin())
    }

    @Test
    fun httpTtsCacheKeyTracksSessionInputs() {
        val base = buildHttpTtsCacheFileName(
            "chapter",
            "https://tts.example",
            10,
            "voice=a",
            "Authorization: token-a",
            "content",
        )
        assertEquals(
            base,
            buildHttpTtsCacheFileName(
                "chapter",
                "https://tts.example",
                10,
                "voice=a",
                "Authorization: token-a",
                "content",
            )
        )
        assertNotEquals(
            base,
            buildHttpTtsCacheFileName(
                "chapter",
                "https://tts.example",
                10,
                "voice=b",
                "Authorization: token-a",
                "content",
            )
        )
        assertNotEquals(
            base,
            buildHttpTtsCacheFileName(
                "chapter",
                "https://tts.example",
                10,
                "voice=a",
                "Authorization: token-b",
                "content",
            )
        )
        assertNotEquals(
            buildHttpTtsCacheFileName("chapter", "url", 10, "a", "b-|-c", "content"),
            buildHttpTtsCacheFileName("chapter", "url", 10, "a-|-b", "c", "content"),
        )
    }

    @Test
    fun foregroundServiceDenialIsRecognizedThroughCauses() {
        val denied = ForegroundServiceStartNotAllowedException()
        assertTrue(denied.isForegroundServiceStartDenied())
        assertTrue(IllegalStateException(denied).isForegroundServiceStartDenied())
        assertFalse(IllegalStateException("other").isForegroundServiceStartDenied())
    }

    private class ForegroundServiceStartNotAllowedException : RuntimeException()
}
