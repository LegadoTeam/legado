package io.legado.app.help.config

import org.junit.Assert.assertEquals
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

    @Test
    fun `batch cleanup scans keys once and keeps overlapping source settings`() {
        val removed = listOf(
            "https://example.com/source",
            "https://example.com/other",
        )
        val protected = listOf("https://example.com/source_backup")

        assertEquals(
            setOf(
                "https://example.com/source",
                "https://example.com/source_book_author",
                "https://example.com/other_book_author",
            ),
            sourceConfigKeysToRemove(
                listOf(
                    "https://example.com/source",
                    "https://example.com/source_book_author",
                    "https://example.com/source_backup",
                    "https://example.com/source_backup_book_author",
                    "https://example.com/other_book_author",
                    "unrelated",
                ),
                removed,
                protected,
            )
        )
    }
}
