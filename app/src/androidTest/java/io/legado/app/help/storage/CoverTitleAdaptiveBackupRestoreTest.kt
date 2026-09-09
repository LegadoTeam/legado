package io.legado.app.help.storage

import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CoverTitleAdaptiveBackupRestoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences = HashMap(preferences.all)
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)

    @Before
    fun setUp() {
        BackupConfig.ignoreConfig.clear()
    }

    @After
    fun tearDown() {
        preferences.edit().clear().apply { savedPreferences.forEach { (key, value) -> putValue(key, value) } }.commit()
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
    }

    @Test
    fun backupRestoresAdaptiveSettingAndLegacyDefaultsToEnabled() {
        val directory = File(context.cacheDir, "cover-title-backup-${UUID.randomUUID()}")
        try {
            writePreferenceSnapshot(context, directory.path, "config") {
                putBoolean(PreferKey.coverTitleAdaptive, false)
            }
            preferences.edit().putBoolean(PreferKey.coverTitleAdaptive, true).commit()
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.path) }
            assertFalse(preferences.getBoolean(PreferKey.coverTitleAdaptive, true))

            writePreferenceSnapshot(context, directory.path, "config") { }
            preferences.edit().putBoolean(PreferKey.coverTitleAdaptive, false).commit()
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.path) }
            assertTrue(preferences.getBoolean(PreferKey.coverTitleAdaptive, false))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ignoredCoverConfigDoesNotRestoreAdaptiveSetting() {
        val directory = File(context.cacheDir, "cover-title-backup-ignore-${UUID.randomUUID()}")
        try {
            writePreferenceSnapshot(context, directory.path, "config") {
                putBoolean(PreferKey.coverTitleAdaptive, false)
            }
            preferences.edit().putBoolean(PreferKey.coverTitleAdaptive, true).commit()
            BackupConfig.ignoreConfig["coverConfig"] = true
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.path) }
            assertTrue(preferences.getBoolean(PreferKey.coverTitleAdaptive, false))
        } finally {
            directory.deleteRecursively()
        }
    }
}

private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
    when (value) {
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is String -> putString(key, value)
        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
    }
}
