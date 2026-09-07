package io.legado.app.ui.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceSearchTest {

    @Test
    fun `matches localized preference titles summaries and categories`() {
        assertTrue(configPreferenceMatches("THEME", "Theme settings", null, emptyList()))
        assertTrue(configPreferenceMatches("network", "Other", "Network behavior", emptyList()))
        assertTrue(configPreferenceMatches("night", "Accent", null, listOf("Night colors")))
        assertFalse(configPreferenceMatches(" ", "Theme", "Interface colors", emptyList()))
        assertFalse(configPreferenceMatches("cache", "Theme", "Interface colors", emptyList()))
    }
}
