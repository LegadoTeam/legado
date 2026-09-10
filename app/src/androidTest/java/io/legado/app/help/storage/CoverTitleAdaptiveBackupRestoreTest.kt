package io.legado.app.help.storage

import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class CoverTitleAdaptiveBackupRestoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences = HashMap(preferences.all)
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val savedLocal = HashMap(LocalConfig.all)

    @Before
    fun setUp() {
        BackupConfig.ignoreConfig.clear()
    }

    @After
    fun tearDown() {
        preferences.edit().clear().apply { savedPreferences.forEach { (key, value) -> putValue(key, value) } }.commit()
        LocalConfig.edit().clear().apply { savedLocal.forEach { (key, value) -> putValue(key, value) } }.commit()
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

    @Test
    fun customFontSizesRestoreTogetherAndRespectIgnoreCoverConfig() {
        val sizes = listOf(PreferKey.coverTitleLargeSize, PreferKey.coverTitleSmallSize,
            PreferKey.coverAuthorLargeSize, PreferKey.coverAuthorSmallSize)
        val directory = File(context.cacheDir, "cover-font-backup-${UUID.randomUUID()}")
        val configDirectory = File(directory, "restored-config")
        try {
            BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = true }
            BackupConfig.ignoreConfig[BackupConfig.settingContentKey] = false
            preferences.edit().putBoolean(PreferKey.onlyLatestBackup, true).commit()
            preferences.edit().putBoolean(PreferKey.coverCustomFontSize, true).apply {
                sizes.forEachIndexed { index, key -> putInt(key, 110 + index * 10) }
            }.commit()
            runBlocking(Dispatchers.IO) { Backup.backupLocked(context, directory.path, uploadWebDav = false) }
            configDirectory.mkdirs()
            ZipFile(File(directory, "backup.zip")).use { zip ->
                File(configDirectory, "config.xml").writeBytes(zip.getInputStream(zip.getEntry("config.xml")).use { it.readBytes() })
            }
            preferences.edit().putBoolean(PreferKey.coverCustomFontSize, false).apply {
                sizes.forEach { putInt(it, 80) }
            }.commit()
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(configDirectory.path) }
            assertTrue(preferences.getBoolean(PreferKey.coverCustomFontSize, false))
            sizes.forEachIndexed { index, key -> assertEquals(110 + index * 10, preferences.getInt(key, 0)) }

            preferences.edit().putBoolean(PreferKey.coverCustomFontSize, false).apply {
                sizes.forEach { putInt(it, 90) }
            }.commit()
            BackupConfig.ignoreConfig["coverConfig"] = true
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(configDirectory.path) }
            assertFalse(preferences.getBoolean(PreferKey.coverCustomFontSize, true))
            sizes.forEach { assertEquals(90, preferences.getInt(it, 0)) }
            runBlocking(Dispatchers.IO) { Backup.backupLocked(context, directory.path, uploadWebDav = false) }
            ZipFile(File(directory, "backup.zip")).use { zip ->
                val xml = zip.getInputStream(zip.getEntry("config.xml")).bufferedReader().use { it.readText() }
                (sizes + PreferKey.coverCustomFontSize).forEach { assertFalse("ignored key $it must not be exported", xml.contains(it)) }
            }

            BackupConfig.ignoreConfig.clear()
            writePreferenceSnapshot(context, configDirectory.path, "config") { }
            preferences.edit().putBoolean(PreferKey.coverCustomFontSize, true).commit()
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(configDirectory.path) }
            assertFalse("legacy backups retain the original cover style",
                preferences.getBoolean(PreferKey.coverCustomFontSize, true))
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
