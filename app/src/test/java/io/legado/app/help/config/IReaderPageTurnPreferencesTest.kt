package io.legado.app.help.config

import android.content.SharedPreferences
import io.legado.app.constant.PreferKey
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class IReaderPageTurnPreferencesTest {
    @Test
    fun `ordinary string reads do not copy the preference map`() {
        val preferences = mock(SharedPreferences::class.java)
        `when`(preferences.getString(PreferKey.iReaderPageTurnEffect, null)).thenReturn("2")

        assertEquals(2, readIReaderPageTurnChoice(preferences, PreferKey.iReaderPageTurnEffect, 1, 1..2))
        verify(preferences, org.mockito.Mockito.never()).all
    }

    @Test
    fun `numeric backups can be read before the settings screen is opened`() {
        val preferences = mock(SharedPreferences::class.java)
        `when`(preferences.getString(PreferKey.iReaderPageTurnEffect, null))
            .thenThrow(ClassCastException())
        `when`(preferences.all).thenReturn(mapOf(PreferKey.iReaderPageTurnEffect to 1))

        assertEquals(1, readIReaderPageTurnChoice(preferences, PreferKey.iReaderPageTurnEffect, 1, 1..2))
    }

    @Test
    fun `invalid effect uses the default`() {
        val preferences = mock(SharedPreferences::class.java)
        for (value in listOf("-1", "0", "3", "not a number", "2147483648", null)) {
            `when`(preferences.getString(PreferKey.iReaderPageTurnEffect, null)).thenReturn(value)
            assertEquals(1, readIReaderPageTurnChoice(preferences, PreferKey.iReaderPageTurnEffect, 1, 1..2))
        }
    }

    @Test
    fun `numeric and invalid preferences are repaired together before ListPreference reads them`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, Answers.RETURNS_SELF)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(preferences.all).thenReturn(mapOf(
            PreferKey.iReaderPageTurnEffect to 2L,
            PreferKey.iReaderPageTurnSpeed to "invalid",
        ))

        normalizeIReaderPageTurnPreferences(preferences)

        verify(editor).putString(PreferKey.iReaderPageTurnEffect, "2")
        verify(editor).putString(PreferKey.iReaderPageTurnSpeed, "1")
        verify(editor).apply()
    }

    @Test
    fun `valid saved settings are not rewritten`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(preferences.all).thenReturn(mapOf(
            PreferKey.iReaderPageTurnEffect to "1",
            PreferKey.iReaderPageTurnSpeed to "0",
        ))

        normalizeIReaderPageTurnPreferences(preferences)

        verifyNoInteractions(editor)
    }

    @Test
    fun `imported refresh intervals default to system management unless explicitly supported`() {
        val preferences = mock(SharedPreferences::class.java)
        for (value in listOf(null, "0", "-1", "3", "-99", "2147483648", "invalid")) {
            `when`(preferences.getString(PreferKey.iReaderPageTurnRefreshInterval, null)).thenReturn(value)
            assertEquals(-2, readIReaderPageTurnRefreshInterval(preferences))
        }
        for (value in listOf(-2, 1, 5, 10, 20, 30, 50)) {
            `when`(preferences.getString(PreferKey.iReaderPageTurnRefreshInterval, null))
                .thenReturn(value.toString())
            assertEquals(value, readIReaderPageTurnRefreshInterval(preferences))
        }
        verify(preferences, org.mockito.Mockito.never()).all
    }

    @Test
    fun `numeric refresh interval backups are accepted before opening settings`() {
        val preferences = mock(SharedPreferences::class.java)
        `when`(preferences.getString(PreferKey.iReaderPageTurnRefreshInterval, null))
            .thenThrow(ClassCastException())
        `when`(preferences.all).thenReturn(mapOf(PreferKey.iReaderPageTurnRefreshInterval to 10L))
        assertEquals(10, readIReaderPageTurnRefreshInterval(preferences))
    }

    @Test
    fun `invalid numeric refresh interval is repaired before the list preference reads it`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, Answers.RETURNS_SELF)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(preferences.all).thenReturn(mapOf(
            PreferKey.iReaderPageTurnEffect to "1",
            PreferKey.iReaderPageTurnSpeed to "1",
            PreferKey.iReaderPageTurnRefreshInterval to 0,
        ))
        normalizeIReaderPageTurnPreferences(preferences)
        verify(editor).putString(PreferKey.iReaderPageTurnRefreshInterval, "-2")
        verify(editor).apply()
    }
}
