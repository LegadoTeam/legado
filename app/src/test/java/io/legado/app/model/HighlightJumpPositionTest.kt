package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightJumpPositionTest {

    @Test
    fun `saved highlight follows current title length`() {
        assertEquals(
            19,
            resolveHighlightChapterPosition(
                rawPosition = 15,
                sourceTitleLength = 5,
                currentTitleLength = 9
            )
        )
    }

    @Test
    fun `hidden title keeps the same body position`() {
        assertEquals(
            10,
            resolveHighlightChapterPosition(
                rawPosition = 15,
                sourceTitleLength = 5,
                currentTitleLength = 0
            )
        )
    }

    @Test
    fun `legacy highlight keeps its raw position`() {
        assertEquals(
            15,
            resolveHighlightChapterPosition(
                rawPosition = 15,
                sourceTitleLength = -1,
                currentTitleLength = 9
            )
        )
    }

    @Test
    fun `legacy highlight before a longer title starts after the title`() {
        assertEquals(
            9,
            resolveHighlightChapterPosition(
                rawPosition = 5,
                sourceTitleLength = -1,
                currentTitleLength = 9
            )
        )
    }
}
