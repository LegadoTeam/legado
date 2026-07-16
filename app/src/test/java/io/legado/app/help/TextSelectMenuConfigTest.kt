package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSelectMenuConfigTest {

    @Test
    fun defaultMatchesCurrentMenuOrder() {
        val config = TextSelectMenuConfig.default()

        assertEquals(
            listOf("replace", "copy", "bookmark", "aloud", "dict"),
            config.bar
        )
        assertEquals(
            listOf("search", "browser", "share", "processText"),
            config.more
        )
    }

    @Test
    fun jsonRoundTripPreservesOrder() {
        val config = TextSelectMenuConfig(
            listOf("copy", "processText"),
            listOf("share", "dict")
        )

        assertEquals(config, TextSelectMenuConfig.fromJson(config.toJson()))
    }

    @Test
    fun malformedJsonReturnsDefault() {
        assertEquals(TextSelectMenuConfig.default(), TextSelectMenuConfig.fromJson("{not json"))
    }

    @Test
    fun normalizedDropsUnknownAndDuplicateKeys() {
        val config = TextSelectMenuConfig(
            listOf("copy", "unknown", "copy"),
            listOf("share", "copy")
        ).normalized()

        assertEquals(listOf("copy"), config.bar)
        assertFalse("unknown" in config.bar || "unknown" in config.more)
        assertEquals(1, (config.bar + config.more).count { it == "copy" })
        assertEquals(TextSelectMenuConfig.ALL_KEYS.toSet(), (config.bar + config.more).toSet())
    }

    @Test
    fun expandedPreferenceMigrationKeepsEveryActionInBar() {
        val config = TextSelectMenuConfig.migrateFrom(true)

        assertEquals(TextSelectMenuConfig.ALL_KEYS, config.bar)
        assertTrue(config.more.isEmpty())
    }

    @Test
    fun partitionMovesProcessTextActionsAsAGroup() {
        val builtIn = mapOf(
            "copy" to "copy item",
            "share" to "share item"
        )
        val processText = listOf("translate app", "dictionary app")
        val allItems = listOf("copy item", "share item") + processText + "future item"
        val config = TextSelectMenuConfig(
            bar = listOf("copy", "processText"),
            more = listOf("share")
        )

        val partition = config.partitionItems(builtIn, processText, allItems)

        assertEquals(listOf("copy item", "translate app", "dictionary app"), partition.bar)
        assertEquals(listOf("share item", "future item"), partition.more)
    }
}
