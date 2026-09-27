package io.legado.app.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceBooleanCompatibilityTest {

    @Test
    fun `parse legacy string booleans`() {
        assertTrue(parseBooleanPreference("true", false))
        assertFalse(parseBooleanPreference(" false ", true))
    }

    @Test
    fun `invalid legacy value uses default`() {
        assertTrue(parseBooleanPreference("invalid", true))
        assertFalse(parseBooleanPreference(1, false))
    }
}
