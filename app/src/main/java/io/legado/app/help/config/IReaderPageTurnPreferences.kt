package io.legado.app.help.config

import android.content.SharedPreferences
import io.legado.app.constant.PreferKey

/** Reads imported numeric preferences without copying the entire map on every turn. */
internal fun readIReaderPageTurnChoice(
    preferences: SharedPreferences,
    key: String,
    defaultValue: Int,
    validValues: IntRange = 0..2,
): Int = readIReaderPreferenceInt(preferences, key)?.takeIf { it in validValues } ?: defaultValue

private fun readIReaderPreferenceInt(preferences: SharedPreferences, key: String): Int? {
    val stored = try {
        preferences.getString(key, null)
    } catch (_: ClassCastException) {
        preferences.all[key]?.toString()
    }
    return stored?.toIntOrNull()
}

/** Keep ListPreference values and imported numeric backups in the same supported domain. */
internal fun normalizeIReaderRefreshInterval(value: Int?): Int =
    value?.takeIf { it == -2 || it == 1 || it == 5 || it == 10 || it == 20 || it == 30 || it == 50 } ?: -2

internal fun readIReaderPageTurnRefreshInterval(preferences: SharedPreferences): Int {
    return normalizeIReaderRefreshInterval(
        readIReaderPreferenceInt(preferences, PreferKey.iReaderPageTurnRefreshInterval),
    )
}

/** ListPreference needs actual strings before inflation, even if the renderer accepts numbers. */
internal fun normalizeIReaderPageTurnPreferences(preferences: SharedPreferences) {
    val stored = preferences.all
    val defaults = mapOf(
        PreferKey.iReaderPageTurnEffect to 1,
        PreferKey.iReaderPageTurnSpeed to 1,
    )
    var editor: SharedPreferences.Editor? = null
    for ((key, defaultValue) in defaults) {
        val validValues = if (key == PreferKey.iReaderPageTurnEffect) 1..2 else 0..2
        val normalized = stored[key]?.toString()?.toIntOrNull()
            ?.takeIf { it in validValues } ?: defaultValue
        if (stored[key] != normalized.toString()) {
            val changes = editor ?: preferences.edit().also { editor = it }
            changes.putString(key, normalized.toString())
        }
    }
    val intervalKey = PreferKey.iReaderPageTurnRefreshInterval
    // An absent value already means system-managed; leave it absent.
    if (stored.containsKey(intervalKey)) {
        val normalized = normalizeIReaderRefreshInterval(stored[intervalKey]?.toString()?.toIntOrNull())
        if (stored[intervalKey] != normalized.toString()) {
            val changes = editor ?: preferences.edit().also { editor = it }
            changes.putString(intervalKey, normalized.toString())
        }
    }
    editor?.apply()
}
