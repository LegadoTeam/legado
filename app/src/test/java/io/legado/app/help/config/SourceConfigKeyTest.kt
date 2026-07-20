package io.legado.app.help.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceConfigKeyTest {

    @Test
    fun `source cleanup only matches the exact source and its derived keys`() {
        val sourceUrl = "https://example.com/source"
        val protectedSourceUrl = "${sourceUrl}_backup"
        val protectedOrigins = listOf(protectedSourceUrl)

        assertTrue(belongsToSource(sourceUrl, sourceUrl, protectedOrigins))
        assertTrue(belongsToSource("${sourceUrl}_book_author", sourceUrl, protectedOrigins))
        assertFalse(belongsToSource("${sourceUrl}/child", sourceUrl, protectedOrigins))
        assertFalse(belongsToSource("${sourceUrl}2", sourceUrl, protectedOrigins))
        assertFalse(belongsToSource(protectedSourceUrl, sourceUrl, protectedOrigins))
        assertFalse(
            belongsToSource(
                "${protectedSourceUrl}_book_author",
                sourceUrl,
                protectedOrigins,
            )
        )
    }

    @Test
    fun `longer source URL owns its exact and derived keys`() {
        val shortSourceUrl = "https://example.com/source"
        val longSourceUrl = "${shortSourceUrl}_backup"

        assertTrue(
            belongsToSource(
                longSourceUrl,
                longSourceUrl,
                listOf(shortSourceUrl),
            )
        )
        assertTrue(
            belongsToSource(
                "${longSourceUrl}_book_author",
                longSourceUrl,
                listOf(shortSourceUrl),
            )
        )
    }
}
