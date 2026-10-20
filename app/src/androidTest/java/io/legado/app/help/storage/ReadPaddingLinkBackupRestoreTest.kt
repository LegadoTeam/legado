package io.legado.app.help.storage

import android.content.SharedPreferences
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalCache
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ReadPaddingLinkBackupRestoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences = HashMap(preferences.all)
    private val savedLocal = HashMap(LocalConfig.all)
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val directory = File(context.cacheDir, "read-padding-link-${UUID.randomUUID()}")
    private val pathsToPreserve = listOf(
        File(ReadBookConfig.configFilePath),
        File(ReadBookConfig.shareConfigFilePath),
        File(ThemeConfig.configFilePath),
        File(context.filesDir, "backup"),
        File(context.externalFiles, "backup.zip"),
        File(context.externalFiles, "tmp_backup.zip"),
        File(context.externalCache, "readConfig.zip"),
        File(context.externalCache, "readConfig"),
    )
    private val savedPaths = linkedMapOf<File, File?>()
    private val savedConfigs = ReadBookConfig.configList.toList()
    private val savedShare = ReadBookConfig.shareConfig
    private val savedShareLayout = ReadBookConfig.shareLayout
    private val savedStyle = ReadBookConfig.readStyleSelect
    private val savedComicStyle = ReadBookConfig.comicStyleSelect
    private val savedComic = ReadBookConfig.isComic
    private val savedHideStatusBar = ReadBookConfig.hideStatusBar
    private val savedHideNavigationBar = ReadBookConfig.hideNavigationBar
    private val savedAutoReadSpeed = ReadBookConfig.autoReadSpeed

    @Before
    fun setUp() {
        directory.mkdirs()
        pathsToPreserve.forEachIndexed { index, file ->
            val saved = if (file.exists()) File(directory, "saved-$index").also {
                check(file.copyRecursively(it))
            } else null
            savedPaths[file] = saved
        }
        instrumentation.runOnMainSync {
            ReadBookConfig.isComic = false
            ReadBookConfig.readStyleSelect = 0
            ReadBookConfig.shareLayout = false
            ReadBookConfig.configList.clear()
            repeat(6) { ReadBookConfig.configList += ReadBookConfig.Config() }
            ReadBookConfig.shareConfig = ReadBookConfig.Config()
        }
        BackupConfig.ignoreConfig.clear()
        BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = true }
        BackupConfig.ignoreConfig[BackupConfig.settingContentKey] = false
        BackupConfig.ignoreConfig["coverConfig"] = true
        preferences.edit().putBoolean(PreferKey.onlyLatestBackup, true).commit()
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            ReadBookConfig.configList.clear()
            ReadBookConfig.configList.addAll(savedConfigs)
            ReadBookConfig.shareConfig = savedShare
            ReadBookConfig.shareLayout = savedShareLayout
            ReadBookConfig.readStyleSelect = savedStyle
            ReadBookConfig.comicStyleSelect = savedComicStyle
            ReadBookConfig.isComic = savedComic
            ReadBookConfig.hideStatusBar = savedHideStatusBar
            ReadBookConfig.hideNavigationBar = savedHideNavigationBar
            ReadBookConfig.autoReadSpeed = savedAutoReadSpeed
        }
        preferences.edit().clear().apply { savedPreferences.forEach { (key, value) -> putValue(key, value) } }.commit()
        LocalConfig.edit().clear().apply { savedLocal.forEach { (key, value) -> putValue(key, value) } }.commit()
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
        savedPaths.forEach { (file, saved) ->
            check(!file.exists() || file.deleteRecursively())
            if (saved != null) check(saved.copyRecursively(file))
        }
        directory.deleteRecursively()
    }

    @Test
    fun backupArchiveRestoresIndependentSharedAndLegacyPaddingLink() = runBlocking(Dispatchers.IO) {
        for (linked in listOf(false, true)) {
            instrumentation.runOnMainSync {
                ReadBookConfig.configList[0].paddingTopBottomLinked = linked
                ReadBookConfig.configList[1].paddingTopBottomLinked = !linked
                ReadBookConfig.shareConfig.paddingTopBottomLinked = linked
                ReadBookConfig.readStyleSelect = 1
                ReadBookConfig.shareLayout = false
                ReadBookConfig.saveNow()
            }

            Backup.backupLocked(context, directory.path, uploadWebDav = false)
            val archive = File(directory, "backup.zip")
            ZipFile(archive).use { zip ->
                val configs = zip.getInputStream(checkNotNull(zip.getEntry(ReadBookConfig.configFileName)))
                    .bufferedReader().use { JsonParser.parseString(it.readText()).asJsonArray }
                val share = zip.getInputStream(checkNotNull(zip.getEntry(ReadBookConfig.shareConfigFileName)))
                    .bufferedReader().use { JsonParser.parseString(it.readText()).asJsonObject }
                assertEquals(linked, configs[0].asJsonObject["paddingTopBottomLinked"].asBoolean)
                assertEquals(!linked, configs[1].asJsonObject["paddingTopBottomLinked"].asBoolean)
                assertEquals(linked, share["paddingTopBottomLinked"].asBoolean)
            }

            instrumentation.runOnMainSync {
                ReadBookConfig.configList.forEach { it.paddingTopBottomLinked = !it.paddingTopBottomLinked }
                ReadBookConfig.shareConfig.paddingTopBottomLinked = !linked
                ReadBookConfig.readStyleSelect = 0
                ReadBookConfig.shareLayout = true
                ReadBookConfig.saveNow()
            }
            Restore.restoreOrThrow(context, archive.toUri(), lanTransfer = true)

            instrumentation.runOnMainSync {
                assertEquals(linked, ReadBookConfig.configList[0].paddingTopBottomLinked)
                assertEquals(!linked, ReadBookConfig.configList[1].paddingTopBottomLinked)
                assertEquals(linked, ReadBookConfig.shareConfig.paddingTopBottomLinked)
                assertEquals(1, ReadBookConfig.readStyleSelect)
                assertFalse(ReadBookConfig.shareLayout)
                assertEquals(!linked, ReadBookConfig.paddingTopBottomLinked)
                ReadBookConfig.readStyleSelect = 0
                assertEquals(linked, ReadBookConfig.paddingTopBottomLinked)
                ReadBookConfig.shareLayout = true
                assertEquals(linked, ReadBookConfig.paddingTopBottomLinked)
            }
        }

        val legacyArchive = File(directory, "legacy-backup.zip")
        ZipFile(File(directory, "backup.zip")).use { source ->
            ZipOutputStream(legacyArchive.outputStream()).use { zip ->
                source.entries().asSequence().forEach { entry ->
                    val bytes = source.getInputStream(entry).use { it.readBytes() }
                    val legacyBytes = when (entry.name) {
                        ReadBookConfig.configFileName -> JsonParser.parseString(bytes.toString(Charsets.UTF_8)).apply {
                            asJsonArray.forEach { it.asJsonObject.remove("paddingTopBottomLinked") }
                        }.toString().toByteArray(Charsets.UTF_8)
                        ReadBookConfig.shareConfigFileName -> JsonParser.parseString(bytes.toString(Charsets.UTF_8)).apply {
                            asJsonObject.remove("paddingTopBottomLinked")
                        }.toString().toByteArray(Charsets.UTF_8)
                        else -> bytes
                    }
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(legacyBytes)
                    zip.closeEntry()
                }
            }
        }
        instrumentation.runOnMainSync {
            ReadBookConfig.configList.forEach { it.paddingTopBottomLinked = true }
            ReadBookConfig.shareConfig.paddingTopBottomLinked = true
            ReadBookConfig.saveNow()
        }
        Restore.restoreOrThrow(context, legacyArchive.toUri(), lanTransfer = true)
        assertFalse(ReadBookConfig.configList.any { it.paddingTopBottomLinked })
        assertFalse(ReadBookConfig.shareConfig.paddingTopBottomLinked)
    }

    @Test
    fun styleZipExportAndImportPreservePaddingLinkAndLegacyDefaultsToFalse() {
        for (shared in listOf(false, true)) {
            for (linked in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    ReadBookConfig.durConfig.paddingTopBottomLinked = !linked
                    ReadBookConfig.shareConfig.paddingTopBottomLinked = !linked
                    ReadBookConfig.shareLayout = shared
                    ReadBookConfig.paddingTopBottomLinked = linked
                }
                assertEquals(linked, ReadBookConfig.paddingTopBottomLinked)
                assertEquals(
                    !linked,
                    if (shared) ReadBookConfig.durConfig.paddingTopBottomLinked
                    else ReadBookConfig.shareConfig.paddingTopBottomLinked,
                )
                val exported = ReadBookConfig.getExportConfig()
                assertEquals(linked, exported.paddingTopBottomLinked)
                assertEquals(linked, exported.toMap()["paddingTopBottomLinked"])
                val json = GSON.toJson(exported)
                assertEquals(linked, JsonParser.parseString(json).asJsonObject["paddingTopBottomLinked"].asBoolean)
                assertEquals(linked, ReadBookConfig.import(styleZip(json)).paddingTopBottomLinked)
            }
        }

        val legacyJson = GSON.toJson(ReadBookConfig.getExportConfig().toMap() - "paddingTopBottomLinked")
        assertFalse(ReadBookConfig.import(styleZip(legacyJson)).paddingTopBottomLinked)
    }

    private fun styleZip(json: String): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(ReadBookConfig.configFileName))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        output.toByteArray()
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
