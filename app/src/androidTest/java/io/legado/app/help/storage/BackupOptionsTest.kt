package io.legado.app.help.storage

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.preference.Preference
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.config.BackupConfigFragment
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class BackupOptionsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences = HashMap(preferences.all)
    private val savedLocal = HashMap(LocalConfig.all)
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val directory = File(context.cacheDir, "backup-options-${UUID.randomUUID()}")
    private val book = Book(bookUrl = "backup-options:${UUID.randomUUID()}", name = "Backup options", author = "Test", durChapterIndex = 7)
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var upload: ByteArray? = null
    @Volatile private var failUpload = false
    @Volatile private var failAuthorization = false
    private var scenario: ActivityScenario<ConfigActivity>? = null
    private val defaultArchive = File(context.externalFiles, "backup.zip")
    private var savedDefaultArchive: ByteArray? = null
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            requests += "${session.method} ${session.uri}"
            if (failAuthorization) return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "invalid credentials")
            val files = HashMap<String, String>()
            session.parseBody(files)
            if (session.method == Method.PUT) {
                upload = files["content"]?.let { File(it).readBytes() }
                return newFixedLengthResponse(if (failUpload) Response.Status.INTERNAL_ERROR else Response.Status.CREATED,
                    "text/plain", "upload")
            }
            return newFixedLengthResponse(Response.Status.OK, "application/xml",
                """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"/>""")
        }
    }

    @Before fun setUp() {
        directory.mkdirs()
        savedDefaultArchive = defaultArchive.takeIf { it.exists() }?.readBytes()
        server.start()
        preferences.edit().putString(PreferKey.backupPath, directory.path)
            .putBoolean(PreferKey.autoBackup, true).putBoolean(PreferKey.autoBackupWebDav, false)
            .putInt(PreferKey.autoBackupIntervalDays, 7).putBoolean(PreferKey.onlyLatestBackup, true)
            .putString(PreferKey.webDavUrl, "http://127.0.0.1:${server.listeningPort}/")
            .putString(PreferKey.webDavDir, "").putString(PreferKey.webDavAccount, "test")
            .putString(PreferKey.webDavPassword, "test").commit()
        LocalConfig.edit().putInt("backupHelpVersion", 1).putLong("lastBackup", 0).commit()
        BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = true }
        listOf(BackupConfig.bookshelfContentKey, BackupConfig.settingContentKey, BackupConfig.backgroundContentKey)
            .forEach { BackupConfig.ignoreConfig[it] = false }
        appDb.bookDao.insert(book)
        runBlocking(Dispatchers.IO) { AppWebDav.upConfig() }
        assertTrue("The fake WebDav must be configured before checking for zero requests", AppWebDav.isOk)
        requests.clear()
    }

    @After fun tearDown() {
        scenario?.close()
        appDb.bookDao.delete(book)
        preferences.edit().clear().apply { savedPreferences.forEach { (key, value) -> putValue(key, value) } }.commit()
        LocalConfig.edit().clear().apply { savedLocal.forEach { (key, value) -> putValue(key, value) } }.commit()
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
        runBlocking(Dispatchers.IO) { AppWebDav.upConfig() }
        server.stop()
        directory.deleteRecursively()
        savedDefaultArchive?.let { defaultArchive.writeBytes(it) } ?: defaultArchive.delete()
    }

    @Test fun manualChoiceConfirmsBeforeCreatingLocalAndWebDavArchives() {
        defaultArchive.delete()
        preferences.edit().remove(PreferKey.backupPath).commit()
        launchSettings()
        clickPreference("web_dav_backup")
        onView(withText(R.string.backup_local_only)).check(matches(isDisplayed()))
        onView(withText(R.string.backup_local_webdav)).check(matches(isDisplayed()))
        screenshot("backup-manual-destinations")
        assertFalse(defaultArchive.exists())
        assertEquals(0L, LocalConfig.lastBackup)
        assertTrue(requests.isEmpty())
        pressBack()
        assertFalse(defaultArchive.exists())
        clickPreference("web_dav_backup")
        onView(withText(R.string.backup_local_only)).perform(click())
        await { LocalConfig.lastBackup > 0 }
        assertArchive(defaultArchive)
        assertTrue("Local choice must not check, upload or sync backgrounds: $requests", requests.isEmpty())
        clickPreference("web_dav_backup")
        onView(withText(R.string.backup_local_webdav)).perform(click())
        await { upload != null && !File(Backup.zipFilePath).exists() }
        assertTrue(requests.any { it.startsWith("PUT /backup") })
        val received = File(directory, "received.zip").apply { writeBytes(checkNotNull(upload)) }
        assertArchive(received)
        assertArrayEquals(defaultArchive.readBytes(), received.readBytes())
    }

    @Test fun settingsDialogKeepsTargetsIntervalAndEnablementAndMovesPasswordAboveActualPath() {
        preferences.edit().remove(PreferKey.autoBackup).remove(PreferKey.autoBackupWebDav)
            .remove(PreferKey.autoBackupIntervalDays).remove(PreferKey.backupPath).commit()
        launchSettings()
        scenario!!.onActivity { activity ->
            val fragment = activity.supportFragmentManager.findFragmentByTag(ConfigTag.BACKUP_CONFIG) as BackupConfigFragment
            val password = checkNotNull(fragment.findPreference<Preference>("localPassword"))
            val path = checkNotNull(fragment.findPreference<Preference>(PreferKey.backupPath))
            assertSame(password.parent, path.parent)
            assertEquals(context.getString(R.string.backup_restore), password.parent!!.title)
            assertTrue(password.order < path.order)
            assertTrue(path.summary.toString().contains(context.externalFiles.absolutePath))
        }
        clickPreference("localPassword")
        onView(withId(R.id.edit_view)).perform(replaceText("backup-options-password"), closeSoftKeyboard())
        onView(withText(R.string.ok)).perform(click())
        assertEquals("backup-options-password", LocalConfig.password)
        assertFalse(preferences.contains("password"))
        scenario!!.onActivity { activity ->
            (activity.supportFragmentManager.findFragmentByTag(ConfigTag.BACKUP_CONFIG) as BackupConfigFragment)
                .scrollToPreference(PreferKey.backupPath)
        }
        instrumentation.waitForIdleSync()
        onView(withText(containsString(context.externalFiles.absolutePath))).check(matches(isDisplayed()))
        screenshot("backup-password-and-default-directory")
        clickPreference(PreferKey.autoBackup)
        assertTrue(AppConfig.autoBackup)
        assertTrue(AppConfig.autoBackupWebDav)
        assertEquals(1, AppConfig.autoBackupIntervalDays)
        screenshot("backup-auto-defaults")
        onView(withId(R.id.local_only)).perform(click())
        onView(withId(R.id.interval_days)).perform(replaceText("0"), closeSoftKeyboard())
        onView(withText(R.string.ok)).perform(click())
        onView(withId(R.id.interval_days)).check(matches(isDisplayed()))
        assertEquals(1, AppConfig.autoBackupIntervalDays)
        onView(withId(R.id.interval_days)).perform(replaceText("7"), closeSoftKeyboard())
        onView(withText(R.string.ok)).perform(click())
        assertFalse(AppConfig.autoBackupWebDav)
        assertEquals(7, AppConfig.autoBackupIntervalDays)
        scenario!!.recreate()
        clickPreference(PreferKey.autoBackup)
        screenshot("backup-auto-local-seven-days")
        onView(withId(R.id.enabled)).perform(click())
        onView(withText(R.string.ok)).perform(click())
        assertFalse(AppConfig.autoBackup)
        clickPreference(PreferKey.autoBackup)
        onView(withId(R.id.enabled)).perform(click())
        onView(withId(R.id.local_webdav)).perform(click())
        onView(withText(R.string.cancel)).perform(click())
        assertFalse(AppConfig.autoBackup)
        assertFalse(AppConfig.autoBackupWebDav)
        assertEquals(7, AppConfig.autoBackupIntervalDays)
        assertTrue(requests.isEmpty())
    }

    @Test fun automaticLocalBackupRespectsIntervalAndFailuresDoNotAdvanceSuccessTime() = runBlocking(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        LocalConfig.lastBackup = now - TimeUnit.DAYS.toMillis(7) + 1
        assertFalse(Backup.shouldBackup(now))
        assertTrue(Backup.shouldBackup(now + 1))
        LocalConfig.lastBackup = now - TimeUnit.DAYS.toMillis(6)
        Backup.autoBackupLocked(context)
        assertFalse(File(directory, "backup.zip").exists())
        LocalConfig.lastBackup = now - TimeUnit.DAYS.toMillis(8)
        preferences.edit().putBoolean(PreferKey.autoBackup, false).commit()
        Backup.autoBackupLocked(context)
        assertFalse(File(directory, "backup.zip").exists())
        preferences.edit().putBoolean(PreferKey.autoBackup, true).commit()
        Backup.autoBackupLocked(context)
        assertArchive(File(directory, "backup.zip"))
        val firstSuccess = LocalConfig.lastBackup
        assertTrue(firstSuccess >= now)
        Backup.autoBackupLocked(context)
        assertEquals(firstSuccess, LocalConfig.lastBackup)
        assertTrue("Automatic local mode must make zero WebDav requests: $requests", requests.isEmpty())

        val blocked = File(directory, "not-a-directory").apply { writeText("keep me") }
        preferences.edit().putString(PreferKey.backupPath, blocked.path).commit()
        LocalConfig.lastBackup = 123
        assertTrue(runCatching { Backup.autoBackupLocked(context) }.isFailure)
        assertEquals(123L, LocalConfig.lastBackup)
        assertEquals("keep me", blocked.readText())
        preferences.edit().putString(PreferKey.backupPath, directory.path)
            .putBoolean(PreferKey.autoBackupWebDav, true).commit()
        failUpload = true
        assertTrue(runCatching { Backup.autoBackupLocked(context) }.isFailure)
        assertArchive(File(directory, "backup.zip"))
        assertEquals("A failed cloud upload must remain due for retry", 123L, LocalConfig.lastBackup)
        assertTrue(requests.any { it.startsWith("PUT /backup") })
    }

    @Test fun selectedCloudBackupDoesNotReportSuccessWithoutAuthorization() = runBlocking(Dispatchers.IO) {
        failAuthorization = true
        AppWebDav.upConfig()
        assertFalse(AppWebDav.isOk)
        requests.clear()
        LocalConfig.lastBackup = 123
        assertTrue(runCatching { Backup.backupLocked(context, directory.path, uploadWebDav = true) }.isFailure)
        assertArchive(File(directory, "backup.zip"))
        assertEquals(123L, LocalConfig.lastBackup)
        assertFalse(requests.any { it.startsWith("PUT ") })
        preferences.edit().remove(PreferKey.webDavAccount).remove(PreferKey.webDavPassword).commit()
        AppWebDav.upConfig()
        assertTrue(runCatching { Backup.backupLocked(context, directory.path, uploadWebDav = true) }.isFailure)
        assertEquals(123L, LocalConfig.lastBackup)
        Backup.backupLocked(context, directory.path, uploadWebDav = false)
        assertTrue(LocalConfig.lastBackup > 123)
        assertTrue(requests.isEmpty())
    }

    @Test fun actualArchiveRestoresExplicitAutoSettingsAndLegacyMissingKeysUseDefaults() = runBlocking(Dispatchers.IO) {
        preferences.edit().putBoolean(PreferKey.autoBackup, false).commit()
        Backup.backupLocked(context, directory.path, uploadWebDav = false)
        val archive = File(directory, "backup.zip")
        assertArchive(archive)
        preferences.edit().putBoolean(PreferKey.autoBackup, true).putBoolean(PreferKey.autoBackupWebDav, true)
            .putInt(PreferKey.autoBackupIntervalDays, 1).commit()
        Restore.restoreOrThrow(context, archive.toUri(), lanTransfer = true)
        assertFalse(AppConfig.autoBackup)
        assertFalse(AppConfig.autoBackupWebDav)
        assertEquals(7, AppConfig.autoBackupIntervalDays)
        val legacy = File(directory, "legacy").apply { mkdirs() }
        Restore.restoreLocked(legacy.path)
        assertFalse("A backup without settings must leave them alone", AppConfig.autoBackup)
        assertEquals(7, AppConfig.autoBackupIntervalDays)
        writePreferenceSnapshot(context, legacy.path, "config") { }
        Restore.restoreLocked(legacy.path)
        assertTrue(AppConfig.autoBackup)
        assertTrue(AppConfig.autoBackupWebDav)
        assertEquals(1, AppConfig.autoBackupIntervalDays)
        assertTrue(requests.isEmpty())
    }

    private fun launchSettings() {
        scenario = ActivityScenario.launch(Intent(context, ConfigActivity::class.java)
            .putExtra("configTag", ConfigTag.BACKUP_CONFIG))
    }

    private fun clickPreference(key: String) {
        var title = ""
        scenario!!.onActivity { activity ->
            val fragment = activity.supportFragmentManager.findFragmentByTag(ConfigTag.BACKUP_CONFIG) as BackupConfigFragment
            title = checkNotNull(fragment.findPreference<Preference>(key)).title.toString()
            fragment.scrollToPreference(key)
        }
        instrumentation.waitForIdleSync()
        onView(withText(title)).perform(click())
    }

    private fun assertArchive(file: File) {
        assertTrue("Actual archive missing: $file", file.isFile && file.length() > 0)
        ZipFile(file).use { zip ->
            val books = zip.getInputStream(checkNotNull(zip.getEntry("bookshelf.json"))).bufferedReader().use {
                GSON.fromJsonArray<Book>(it.readText()).getOrThrow()
            }
            assertEquals(7, books.single { it.bookUrl == book.bookUrl }.durChapterIndex)
            assertNotNull(zip.getEntry("config.xml"))
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("The real backup did not finish", condition())
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { image.recycle() }
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
