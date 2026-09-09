package io.legado.app.ui.association

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.file.HandleFileActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class SharedFileImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val savedPrefs = HashMap(prefs.all)
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val savedLocal = listOf("privacyPolicyOk", "readHelpVersion", "readMenuHelpVersion")
        .associateWith { LocalConfig.all[it] }
    private val savedBackupTime = LocalConfig.lastBackup
    private val id = UUID.randomUUID().toString()
    private val directory = File(context.cacheDir, "shared-file-$id")
    private val books = arrayListOf<Book>()
    private val sources = arrayListOf<BookSource>()
    private val rules = arrayListOf<HighlightRule>()
    private val replacements = arrayListOf<ReplaceRule>()

    @Before fun setUp() {
        directory.mkdirs()
        File(directory, "books").mkdirs()
        prefs.edit().putBoolean(PreferKey.cronet, false)
            .putBoolean(PreferKey.autoBackup, false)
            .putString(PreferKey.defaultBookTreeUri, File(directory, "books").path)
            .putBoolean(PreferKey.onlyLatestBackup, true).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true)
            .putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
    }

    @After fun tearDown() {
        closeReaders()
        rules.forEach { rule -> appDb.highlightRuleDao.all.find { it.uuid == rule.uuid }
            ?.let { appDb.highlightRuleDao.delete(it) } }
        replacements.forEach { appDb.replaceRuleDao.delete(it) }
        books.forEach { book ->
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
            BookHelp.clearCache(book)
        }
        sources.forEach { appDb.bookSourceDao.delete(it) }
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
        prefs.edit().clear().apply {
            savedPrefs.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> { @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>) }
                }
            }
        }.commit()
        LocalConfig.edit().apply {
            savedLocal.forEach { (key, value) ->
                when (value) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                }
            }
        }.commit()
        LocalConfig.lastBackup = savedBackupTime
        directory.deleteRecursively()
    }

    @Test fun sharedBookListConfirmsThenUsesEnabledSourceSearchAndPersistsTheMatchedBook() {
        val name = "Shared book $id"
        val author = "Shared author"
        val requests = AtomicInteger()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests.incrementAndGet()
                return newFixedLengthResponse("<article><h2>$name</h2><span class='author'>$author</span>" +
                    "<a href='/book/$id'>Details</a></article>")
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val source = BookSource("http://127.0.0.1:${server.listeningPort}", "Share fixture",
            customOrder = Int.MIN_VALUE, searchUrl = "/search?key={{key}}",
            ruleSearch = SearchRule(bookList = "article", name = "h2@text", author = ".author@text", bookUrl = "a@href"))
        sources.add(source)
        appDb.bookSourceDao.insert(source)
        val file = File(directory, "renamed-list.json").apply {
            writeText(GSON.toJson(listOf(mapOf("name" to name, "author" to author, "intro" to "intro"))))
        }
        try {
            launchShare(file, "application/json").use { scenario ->
                awaitDialog(scenario)
                onView(withText(R.string.import_bookshelf)).inRoot(isDialog()).check(matches(isDisplayed()))
                assertFalse(appDb.bookDao.has(name, author))
                assertEquals(0, requests.get())
                scenario.recreate()
                awaitDialog(scenario)
                onView(withText(R.string.import_bookshelf)).inRoot(isDialog()).check(matches(isDisplayed()))
                screenshot("share-bookshelf-confirmation")
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                await { appDb.bookDao.has(name, author) }
                val book = checkNotNull(appDb.bookDao.getBook(name, author))
                books.add(book)
                assertEquals("${source.bookSourceUrl}/book/$id", book.bookUrl)
                assertEquals(source.bookSourceUrl, book.origin)
                assertTrue(requests.get() > 0)
            }
        } finally { server.stop() }
    }

    @Test fun rawAndTypedHighlightFilesUseTheHighlightPreviewWhileReplacementFilesKeepTheirRoute() {
        for (typed in listOf(false, true)) {
            val rule = HighlightRule(name = "Shared highlight $typed", pattern = "target-$id-$typed",
                style = "{\"bold\":true,\"textColor\":123456}", group = "Share group",
                applyToTitle = true, applyToBody = false, isEnabled = false)
            rules.add(rule)
            val file = File(directory, "unrelated-$typed.json").apply {
                writeText(GSON.toJson(if (typed) HighlightRuleFile(HighlightRuleFile.TYPE, listOf(rule)) else listOf(rule)))
            }
            launchShare(file, "application/octet-stream").use { scenario ->
                awaitDialog(scenario)
                onView(withText(rule.name)).check(matches(isDisplayed()))
                assertFalse(appDb.highlightRuleDao.all.any { it.uuid == rule.uuid })
                screenshot("share-highlight-$typed")
                onView(withId(R.id.tv_ok)).perform(click())
                await { appDb.highlightRuleDao.all.any { it.uuid == rule.uuid } }
                val saved = appDb.highlightRuleDao.all.single { it.uuid == rule.uuid }
                assertEquals(rule.styleObj(), saved.styleObj())
                assertEquals(rule.pattern, saved.pattern)
                assertEquals(rule.group, saved.group)
                assertEquals(rule.applyToBody, saved.applyToBody)
                assertFalse(saved.isEnabled)
            }
        }
        val rule = ReplaceRule(id = System.currentTimeMillis(), name = "Shared replacement", pattern = id, replacement = "changed")
        replacements.add(rule)
        val file = File(directory, "misleading-highlight-name.json").apply { writeText(GSON.toJson(listOf(rule))) }
        launchShare(file, "application/json").use { scenario ->
            awaitDialog(scenario)
            onView(withText(R.string.import_replace_rule)).check(matches(isDisplayed()))
            onView(withText(rule.name)).check(matches(isDisplayed()))
            onView(withId(R.id.tv_ok)).perform(click())
            await { appDb.replaceRuleDao.all.any { it.id == rule.id } }
            assertEquals("changed", appDb.replaceRuleDao.all.single { it.id == rule.id }.replacement)
            assertFalse(appDb.highlightRuleDao.all.any { it.pattern == id })
        }
    }

    @Test fun actualBackupZipConfirmsBeforeRestoringBooksSourcesRulesAndSettings() = runBlocking {
        val source = BookSource("https://shared-backup-$id.invalid", "Backup source")
        val book = Book(bookUrl = "${source.bookSourceUrl}/book", origin = source.bookSourceUrl,
            name = "Backup book $id", author = "Author", durChapterIndex = 7)
        val rule = HighlightRule(name = "Backup rule $id", pattern = id, style = "{\"bold\":true}")
        sources.add(source); books.add(book); rules.add(rule)
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        appDb.highlightRuleDao.insert(rule)
        val marker = "shared-backup-$id"
        prefs.edit().putString(marker, "restored value").commit()
        BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = true }
        listOf(BackupConfig.bookshelfContentKey, BackupConfig.annotationContentKey,
            BackupConfig.sourceContentKey, BackupConfig.settingContentKey)
            .forEach { BackupConfig.ignoreConfig[it] = false }
        val output = File(directory, "backup").apply { mkdirs() }
        Backup.backupLocked(context, output.path, uploadWebDav = false)
        val archive = output.listFiles()!!.single { it.extension == "zip" }
        ZipFile(archive).use { zip ->
            listOf("bookshelf.json", "bookSource.json", "highlightRule.json", "config.xml")
                .forEach { assertNotNull(it, zip.getEntry(it)) }
        }
        val renamed = File(directory, "any-name.zip")
        archive.copyTo(renamed)
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        appDb.highlightRuleDao.all.find { it.uuid == rule.uuid }!!.let { appDb.highlightRuleDao.delete(it) }
        prefs.edit().remove(marker).commit()
        launchShare(renamed, "application/zip").use { scenario ->
            awaitDialog(scenario)
            onView(withText(R.string.restore_confirmation)).inRoot(isDialog()).check(matches(isDisplayed()))
            assertFalse(appDb.bookDao.has(book.bookUrl))
            screenshot("share-backup-confirmation")
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
        }
        assertFalse(appDb.bookDao.has(book.bookUrl))
        assertFalse(prefs.contains(marker))
        launchShare(renamed, "application/zip").use { scenario ->
            awaitDialog(scenario)
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            await { appDb.bookDao.has(book.bookUrl) && prefs.getString(marker, null) == "restored value" }
            assertEquals(7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
            assertEquals(source.bookSourceName, appDb.bookSourceDao.getBookSource(source.bookSourceUrl)!!.bookSourceName)
            assertEquals(rule.styleObj(), appDb.highlightRuleDao.all.single { it.uuid == rule.uuid }.styleObj())
        }
    }

    @Test fun sharedTxtEpubPdfAndBookZipCopyDurablyAndOpenTheRealReader() {
        val txt = File(directory, "shared-txt-$id.txt").apply { writeText("Chapter one\n" + "SHARED_TEXT_VISIBLE $id\n".repeat(20)) }
        val epub = File(directory, "shared-epub-$id.epub").apply {
            instrumentation.context.assets.open("issue1074-containers-fragments.epub").use { input -> outputStream().use(input::copyTo) }
        }
        val pdf = File(directory, "shared-pdf-$id.pdf").apply {
            instrumentation.context.assets.open("pdf-outline-direct-named.pdf").use { input -> outputStream().use(input::copyTo) }
        }
        listOf(txt to "text/plain", epub to "application/epub+zip", pdf to "application/pdf").forEach { (file, mime) ->
            val expected = file.readBytes()
            launchShare(file, mime).use {
                val copied = File(directory, "books/${file.name}")
                await { copied.isFile && appDb.bookDao.has(copied.path) }
                val book = appDb.bookDao.getBook(copied.path)!!
                books.add(book)
                assertArrayEquals(expected, copied.readBytes())
                assertTrue(file.delete())
                awaitReader(book)
                val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
                assertTrue(chapters.isNotEmpty())
                assertFalse(LocalBook.getContent(book, chapters.first()).isNullOrBlank())
                screenshot("share-reader-${copied.extension}")
                closeReaders()
            }
        }
        val archive = File(directory, "book-container.zip")
        val chapter = "Ordinary archive text $id"
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("archive-$id.txt")); zip.write(chapter.toByteArray()); zip.closeEntry()
        }
        launchShare(archive, "application/zip").use {
            await { ReadBook.book?.originName == "archive-$id.txt" }
            val book = ReadBook.book!!
            books.add(book)
            awaitReader(book)
            assertTrue(ReadBook.curTextChapter!!.pages.any { it.text.contains(chapter) })
            closeReaders()
        }
    }

    @Test fun firstSharedBookContinuesWithItsStreamAfterTheFolderResult() {
        prefs.edit().remove(PreferKey.defaultBookTreeUri).commit()
        val file = File(directory, "first-share-$id.txt").apply { writeText("FIRST_SHARED_STREAM $id\n".repeat(15)) }
        val folderRequests = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != HandleFileActivity::class.java.name) return null
                assertEquals(HandleFileContract.DIR_SYS, intent.getIntExtra("mode", -1))
                folderRequests.incrementAndGet()
                return Instrumentation.ActivityResult(Activity.RESULT_OK,
                    Intent().setData(Uri.fromFile(File(directory, "books"))))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            launchShare(file, "text/plain").use {
                val copied = File(directory, "books/${file.name}")
                await { copied.exists() && appDb.bookDao.has(copied.path) }
                val book = appDb.bookDao.getBook(copied.path)!!
                books.add(book)
                assertEquals(1, folderRequests.get())
                assertEquals(file.readText(), copied.readText())
                awaitReader(book)
                assertTrue(ReadBook.curTextChapter!!.pages.any { it.text.contains("FIRST_SHARED_STREAM") })
                closeReaders()
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun launchShare(file: File, mime: String): ActivityScenario<FileAssociationActivity> {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
        val intent = Intent(Intent.ACTION_SEND).setType(mime).setPackage(context.packageName)
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(file.name, uri)
        assertTrue(context.packageManager.queryIntentActivities(intent, 0)
            .any { it.activityInfo.name == FileAssociationActivity::class.java.name })
        intent.component = ComponentName(context, FileAssociationActivity::class.java)
        return ActivityScenario.launch(intent)
    }

    private fun awaitReader(book: Book) {
        await {
            ReadBook.book?.bookUrl == book.bookUrl &&
                ReadBook.curTextChapter?.isCompleted == true &&
                ReadBook.curTextChapter?.pages?.isNotEmpty() == true
        }
    }

    private fun awaitDialog(scenario: ActivityScenario<FileAssociationActivity>) {
        await {
            var ready = false
            scenario.onActivity { activity ->
                ready = activity.supportFragmentManager.fragments.filterIsInstance<DialogFragment>()
                    .any { dialog ->
                        dialog.dialog?.isShowing == true &&
                            (dialog.view?.findViewById<RecyclerView>(R.id.recycler_view)?.adapter?.itemCount ?: 1) > 0
                    }
            }
            ready
        }
    }

    private fun closeReaders() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ReadBookActivity>().forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("Shared import did not reach the expected database/reader state", condition())
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "ui-regression/$name.png").apply {
            parentFile!!.mkdirs()
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }
}
