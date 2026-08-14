package io.legado.app.ui.book.info

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookInfoLoadGuardTest {

    @Test
    fun staleGenerationIsDroppedEvenIfUrlMatches() {
        assertFalse(
            BookInfoLoadGuard.shouldApply(
                currentGeneration = 2,
                callbackGeneration = 1,
                currentBookUrl = "https://a/book",
                requestedBookUrl = "https://a/book",
            )
        )
    }

    @Test
    fun switchedBookUrlIsDroppedEvenIfGenerationMatches() {
        assertFalse(
            BookInfoLoadGuard.shouldApply(
                currentGeneration = 1,
                callbackGeneration = 1,
                currentBookUrl = "https://a/book",
                requestedBookUrl = "https://b/book",
            )
        )
    }

    @Test
    fun currentBookAndGenerationApply() {
        assertTrue(
            BookInfoLoadGuard.shouldApply(
                currentGeneration = 3,
                callbackGeneration = 3,
                currentBookUrl = "https://a/book",
                requestedBookUrl = "https://a/book",
            )
        )
    }

    @Test
    fun missingCurrentBookIsDropped() {
        assertFalse(
            BookInfoLoadGuard.shouldApply(
                currentGeneration = 1,
                callbackGeneration = 1,
                currentBookUrl = null,
                requestedBookUrl = "https://a/book",
            )
        )
    }
}
