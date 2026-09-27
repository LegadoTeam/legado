package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderMenuConfigTest {

    @Test
    fun defaultKeepsAllReaderActionsInOriginalOrder() {
        val config = ReaderMenuConfig.default()

        assertEquals(ReaderMenuConfig.ALL_KEYS, config.primary)
        assertTrue(config.more.isEmpty())
    }

    @Test
    fun jsonRoundTripPreservesPartitionAndOrder() {
        val config = ReaderMenuConfig(
            primary = listOf("help", "bookmark"),
            more = listOf("log", "imageStyle")
        )

        assertEquals(config, ReaderMenuConfig.fromJson(config.toJson()))
    }

    @Test
    fun malformedAndUnknownEntriesAreNormalized() {
        val config = ReaderMenuConfig.fromJson(
            "{\"primary\":[\"help\",\"unknown\",\"help\"]," +
                "\"more\":[\"log\",\"help\"]}"
        ).normalized()

        assertEquals(listOf("help"), config.primary.take(1))
        assertFalse("unknown" in config.primary || "unknown" in config.more)
        assertEquals(1, (config.primary + config.more).count { it == "help" })
        assertEquals(ReaderMenuConfig.ALL_KEYS.toSet(), (config.primary + config.more).toSet())
    }

    @Test
    fun nullJsonFallsBackToDefault() {
        assertEquals(ReaderMenuConfig.default(), ReaderMenuConfig.fromJson("null"))
    }

    @Test
    fun explicitMoreEntriesStayHiddenWhenNewActionsAreAdded() {
        val config = ReaderMenuConfig(
            primary = listOf("bookmark"),
            more = listOf("help")
        ).normalized(ReaderMenuConfig.ALL_KEYS)

        assertEquals(listOf("bookmark") + ReaderMenuConfig.ALL_KEYS.filter {
            it != "bookmark" && it != "help"
        }, config.primary)
        assertEquals(listOf("help"), config.more)
    }
    @Test
    fun reimportIsAddedOnceToOldPreferencesAndKeepsExplicitMorePlacement() {
        val oldKeys = ReaderMenuConfig.ALL_KEYS - "reimportSource"
        val migrated = ReaderMenuConfig(primary = oldKeys.take(2), more = oldKeys.drop(2)).normalized()
        assertEquals(oldKeys.take(2) + "reimportSource", migrated.primary)
        assertEquals(oldKeys.drop(2), migrated.more)
        val hidden = ReaderMenuConfig(primary = oldKeys, more = listOf("reimportSource"))
        assertEquals(hidden, ReaderMenuConfig.fromJson(hidden.toJson()).normalized())
    }

}
