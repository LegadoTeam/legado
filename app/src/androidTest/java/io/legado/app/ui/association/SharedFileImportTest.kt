package io.legado.app.ui.association

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
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
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
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
import java.util.concurrent.atomic.AtomicBoolean
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

    @Test(timeout = 120_000) fun sharedBookListConfirmsThenUsesEnabledSourceSearchAndPersistsTheMatchedBook() {
        val name = "Shared book $id"
        val author = "Shared author"
        val missing = "Missing book $id"
        val mixed = AtomicBoolean()
        val requests = AtomicInteger()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests.incrementAndGet()
                if (mixed.get()) {
                    if (session.parameters["key"]?.firstOrNull() == missing) return newFixedLengthResponse("<html></html>")
                    Thread.sleep(600)
                }
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
                lateinit var model: FileAssociationViewModel
                scenario.onActivity { model = ViewModelProvider(it)[FileAssociationViewModel::class.java] }
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                await { model.importedData.value == true }
                val book = checkNotNull(appDb.bookDao.getBook(name, author))
                books.add(book)
                assertEquals("${source.bookSourceUrl}/book/$id", book.bookUrl)
                assertEquals(source.bookSourceUrl, book.origin)
                assertTrue(requests.get() > 0)
            }
            val savedEnabled = appDb.bookSourceDao.allEnabled.filter { it.bookSourceUrl != source.bookSourceUrl }
            savedEnabled.forEach { appDb.bookSourceDao.enable(it.bookSourceUrl, false) }
            try {
                appDb.bookDao.delete(books.single())
                mixed.set(true)
                requests.set(0)
                file.writeText(GSON.toJson(listOf(mapOf("name" to missing, "author" to author),
                    mapOf("name" to name, "author" to author))))
                launchShare(file, "application/json").use { scenario ->
                    awaitDialog(scenario)
                    onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                    await { appDb.bookDao.has(name, author) && AppLog.logs.any { it.second.contains(missing) } }
                    assertFalse(appDb.bookDao.has(missing, author))
                    assertTrue(requests.get() >= 2)
                    assertEquals(source.bookSourceUrl, appDb.bookDao.getBook(name, author)!!.origin)
                }
            } finally { savedEnabled.forEach { appDb.bookSourceDao.enable(it.bookSourceUrl, true) } }
        } finally { server.stop() }
    }

    @Test(timeout = 120_000) fun rawAndTypedHighlightFilesUseTheHighlightPreviewWhileReplacementFilesKeepTheirRoute() {
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

    @Test(timeout = 120_000) fun actualBackupZipConfirmsBeforeRestoringBooksSourcesRulesAndSettings() = runBlocking {
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
            lateinit var model: FileAssociationViewModel
            scenario.onActivity { model = ViewModelProvider(it)[FileAssociationViewModel::class.java] }
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            await { model.importedData.value == true }
            assertEquals("restored value", prefs.getString(marker, null))
            assertEquals(7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
            assertEquals(source.bookSourceName, appDb.bookSourceDao.getBookSource(source.bookSourceUrl)!!.bookSourceName)
            assertEquals(rule.styleObj(), appDb.highlightRuleDao.all.single { it.uuid == rule.uuid }.styleObj())
        }
    }

    @Test(timeout = 120_000) fun sharedTxtEpubPdfAndBookZipCopyDurablyAndOpenTheRealReader() {
        val txt = File(directory, "shared-txt-$id.txt").apply { writeText("Chapter one\n" + "SHARED_TEXT_VISIBLE $id\n".repeat(20)) }
        val epub = File(directory, "shared-epub-$id.epub").apply {
            instrumentation.context.assets.open("issue1074-containers-fragments.epub").use { input -> outputStream().use(input::copyTo) }
        }
        val pdf = File(directory, "shared-pdf-$id.pdf").apply {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
                page.canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 100, 170) }
                page.canvas.drawRect(24f, 24f, 576f, 120f, paint)
                paint.color = Color.WHITE
                paint.textSize = 32f
                page.canvas.drawText("SHARED PDF VISIBLE", 40f, 85f, paint)
                document.finishPage(page)
                outputStream().use(document::writeTo)
            } finally { document.close() }
        }
        listOf(txt to "text/plain", epub to "application/epub+zip", pdf to "application/pdf").forEach { (file, mime) ->
            val expected = file.readBytes()
            launchShare(file, mime).use { scenario ->
                confirmLocalPreview(scenario)
                val copied = File(directory, "books/${file.name}")
                await { copied.isFile && appDb.bookDao.has(copied.path) }
                val book = appDb.bookDao.getBook(copied.path)!!
                books.add(book)
                assertArrayEquals(expected, copied.readBytes())
                assertTrue(file.delete())
                openReader(book)
                awaitReader(book)
                val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
                assertTrue(chapters.isNotEmpty())
                assertFalse(LocalBook.getContent(book, chapters.first()).isNullOrBlank())
                if (file.extension == "pdf") await { pdfMarkerIsVisible() }
                screenshot("share-reader-${copied.extension}")
                closeReaders()
            }
        }
        val archive = File(directory, "book-container.zip")
        val chapter = "Ordinary archive text $id"
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("archive-$id.txt")); zip.write(chapter.toByteArray()); zip.closeEntry()
        }
        launchShare(archive, "application/zip").use { scenario ->
            confirmLocalPreview(scenario)
            val path = File(directory, "books/archive-$id.txt").path
            await { appDb.bookDao.has(path) }
            val book = appDb.bookDao.getBook(path)!!
            openReader(book)
            books.add(book)
            awaitReader(book)
            assertTrue(ReadBook.curTextChapter!!.pages.any { it.text.contains(chapter) })
            closeReaders()
        }
    }

    @Test(timeout = 120_000) fun firstSharedBookRetainsItsSelectedBatchAcrossRecreationAndTheFolderResult() {
        prefs.edit().remove(PreferKey.defaultBookTreeUri).commit()
        val file = File(directory, "first-share-$id.txt").apply { writeText("FIRST_SHARED_STREAM $id\n".repeat(15)) }
        val folderRequests = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != HandleFileActivity::class.java.name) return null
                assertEquals(HandleFileContract.DIR_SYS, intent.getIntExtra("mode", -1))
                folderRequests.incrementAndGet()
                val activity = listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED)
                    .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                    .filterIsInstance<FileAssociationActivity>().single()
                val model = ViewModelProvider(activity)[FileAssociationViewModel::class.java]
                assertEquals(1, model.pendingLocalBooks.size)
                return Instrumentation.ActivityResult(Activity.RESULT_OK,
                    Intent().setData(Uri.fromFile(File(directory, "books"))))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            launchShare(file, "text/plain").use { scenario ->
                awaitLocalPreview(scenario)
                scenario.recreate()
                confirmLocalPreview(scenario)
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                val copied = File(directory, "books/${file.name}")
                await { copied.exists() && appDb.bookDao.has(copied.path) }
                val book = appDb.bookDao.getBook(copied.path)!!
                books.add(book)
                assertEquals(1, folderRequests.get())
                assertEquals(file.readText(), copied.readText())
                openReader(book)
                awaitReader(book)
                assertTrue(ReadBook.curTextChapter!!.pages.any { it.text.contains("FIRST_SHARED_STREAM") })
                closeReaders()
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test(timeout = 120_000) fun archivePreviewsEverySupportedTypeAndOnlyImportsTheSelection() {
        val pdfFile = File(directory, "batch-$id.pdf")
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
            page.canvas.drawColor(Color.WHITE)
            pdf.finishPage(page)
            pdfFile.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        val contents = linkedMapOf(
            "alpha-$id.txt" to "ALPHA $id".toByteArray(),
            "omit-$id.txt" to "OMITTED $id".toByteArray(),
            "nested/gamma-$id.TXT" to "GAMMA $id".toByteArray(),
            "batch-$id.epub" to instrumentation.context.assets.open("issue1074-containers-fragments.epub").use { it.readBytes() },
            pdfFile.name to pdfFile.readBytes(),
            "notes.json" to "{}".toByteArray(), "picture.jpg" to byteArrayOf(1, 2, 3))
        val archive = File(directory, "five-books-$id.zip")
        writeArchive(archive, contents)
        val original = archive.readBytes()
        val previousBook = ReadBook.book?.bookUrl
        launchShare(archive, "application/zip").use { scenario ->
            awaitLocalPreview(scenario)
            lateinit var model: FileAssociationViewModel
            scenario.onActivity { model = ViewModelProvider(it)[FileAssociationViewModel::class.java] }
            val items = model.localBookBatch.value!!
            assertEquals(5, items.size)
            assertEquals(setOf("txt", "epub", "pdf"), items.map { it.file.name.substringAfterLast('.').lowercase() }.toSet())
            assertEquals(previousBook, ReadBook.book?.bookUrl)
            items.forEach { assertFalse(appDb.bookDao.has(it.preview!!.name, it.preview.author)) }
            val omitted = items.indexOfFirst { it.file.name.startsWith("omit-") }
            scenario.onActivity { activity ->
                activity.supportFragmentManager.findFragmentByTag("sharedLocalBooks")!!.requireView()
                    .findViewById<RecyclerView>(R.id.recycler_view).scrollToPosition(omitted)
            }
            val omittedBook = items[omitted].preview!!
            val label = if (omittedBook.author.isBlank()) omittedBook.name
                else "${omittedBook.name} / ${omittedBook.author}"
            onView(withText(label)).inRoot(isDialog()).perform(click())
            assertEquals(4, model.selectedLocalBooks.size)
            scenario.recreate()
            awaitLocalPreview(scenario)
            assertEquals(4, model.selectedLocalBooks.size)
            screenshot("share-local-archive-selection")
            val selected = items.filter { it.file.uri in model.selectedLocalBooks }
            confirmLocalPreview(scenario)
            await { model.importedLocalBooks.value == true }
            assertEquals(previousBook, ReadBook.book?.bookUrl)
            selected.forEach { item ->
                val book = appDb.bookDao.getBook(File(directory, "books/${item.file.name}").path)!!
                books.add(book)
                assertEquals(item.preview!!.name, book.name)
                assertArrayEquals(contents.entries.single { File(it.key).name == item.file.name }.value,
                    File(book.bookUrl).readBytes())
            }
            assertFalse(appDb.bookDao.has(items[omitted].preview!!.name, items[omitted].preview!!.author))
            assertFalse(File(directory, "books/${items[omitted].file.name}").exists())
            assertArrayEquals(original, archive.readBytes())
            assertEquals(File(directory, "books").path, AppConfig.defaultBookTreeUri)
        }
    }

    @Test(timeout = 120_000) fun multipleSharesKeepAllThreeIdentityConflictsAndOriginalFiles() {
        AppConfig.bookImportFileName = "name='Shared identity $id';author='Shared author';"
        val existingFile = File(directory, "books/one.txt").apply { writeText("EXISTING BOOK") }
        val existing = LocalBook.importFile(Uri.fromFile(existingFile)).also(books::add)
        val missingOriginal = Book(bookUrl = File(directory, "books/two.txt").path,
            originName = "two.txt", name = "Missing original $id", author = "Preserved author")
        appDb.bookDao.insert(missingOriginal)
        books.add(missingOriginal)
        val originals = listOf("one.txt", "two.txt", "three.txt").mapIndexed { index, name ->
            File(directory, name).apply { writeText("SHARED COPY $index $id") }
        }
        val unsupported = File(directory, "ignore.png").apply { writeBytes(byteArrayOf(1, 2)) }
        val uris = (originals + unsupported).map(::providerUri)
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").setPackage(context.packageName)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("books", uris[0]).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        assertTrue(context.packageManager.queryIntentActivities(intent, 0)
            .any { it.activityInfo.name == FileAssociationActivity::class.java.name })
        intent.component = ComponentName(context, FileAssociationActivity::class.java)
        ActivityScenario.launch<FileAssociationActivity>(intent).use { scenario ->
            awaitLocalPreview(scenario)
            lateinit var model: FileAssociationViewModel
            scenario.onActivity { model = ViewModelProvider(it)[FileAssociationViewModel::class.java] }
            val preview = model.localBookBatch.value!!.map { it.preview!! }
            assertEquals(listOf(2, 3, 4).map { "${existing.name} ($it)" }, preview.map { it.name })
            assertEquals(3, preview.size)
            preview.forEach { assertFalse(appDb.bookDao.has(it.name, it.author)) }
            screenshot("share-local-multiple-conflicts")
            confirmLocalPreview(scenario)
            await { model.importedLocalBooks.value == true }
            preview.forEachIndexed { index, item ->
                val copy = appDb.bookDao.getBook(item.name, item.author)!!
                books.add(copy)
                assertNotEquals(existing.bookUrl, copy.bookUrl)
                assertArrayEquals(originals[index].readBytes(), File(copy.bookUrl).readBytes())
                assertTrue(originals[index].isFile)
            }
            assertEquals("EXISTING BOOK", existingFile.readText())
            assertEquals(existing, appDb.bookDao.getBook(existing.bookUrl))
            assertEquals(missingOriginal.name, appDb.bookDao.getBook(missingOriginal.bookUrl)!!.name)
            assertFalse(File(missingOriginal.bookUrl).exists())
            assertFalse(File(directory, "books/ignore.png").exists())
        }
    }

    @Test(timeout = 120_000) fun unsupportedArchiveReportsTheFormatAndEndsInsteadOfLoadingForever() {
        val archive = File(directory, "no-books-$id.zip")
        writeArchive(archive, linkedMapOf("readme.md" to "No books".toByteArray(), "data.bin" to byteArrayOf(1)))
        launchShare(archive, "application/zip").use { scenario ->
            lateinit var model: FileAssociationViewModel
            scenario.onActivity { model = ViewModelProvider(it)[FileAssociationViewModel::class.java] }
            await { model.errorLive.value == context.getString(R.string.unsupport_archivefile_entry) }
            assertNull(model.localBookBatch.value)
            scenario.onActivity { assertFalse(it.findViewById<android.view.View>(R.id.rotate_loading).isShown) }
            screenshot("share-local-unsupported-archive")
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            assertTrue(archive.isFile)
        }
    }

    @Test(timeout = 120_000) fun openWithCopiesToPrivateStorageAfterDefaultDismissOrCancelledPicker() {
        prefs.edit().remove(PreferKey.defaultBookTreeUri).commit()
        for (choice in listOf("default", "dismiss", "picker")) {
            val file = File(directory, "private-$choice-$id.txt").apply {
                writeText("PRIVATE COPY $choice $id\n".repeat(20))
            }
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                    if (intent.component?.className == HandleFileActivity::class.java.name)
                        Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null) else null
            }
            instrumentation.addMonitor(monitor)
            try {
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(providerUri(file), "text/plain")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .setComponent(ComponentName(context, FileAssociationActivity::class.java))
                ActivityScenario.launch<FileAssociationActivity>(intent).use { scenario ->
                    onView(withText(R.string.shared_local_books_storage)).inRoot(isDialog()).check(matches(isDisplayed()))
                    scenario.onActivity {
                        assertNull(it.supportFragmentManager.findFragmentByTag("sharedLocalBooks"))
                    }
                    if (choice == "default") screenshot("share-local-private-folder")
                    when (choice) {
                        "default" -> onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
                        "dismiss" -> pressBack()
                        else -> onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                    }
                    await { ReadBook.book?.originName == file.name }
                    val book = ReadBook.book!!.also(books::add)
                    val copy = File(book.bookUrl)
                    assertEquals(File(context.filesDir, "books").canonicalFile, copy.parentFile!!.canonicalFile)
                    assertArrayEquals(file.readBytes(), copy.readBytes())
                    assertTrue(file.isFile)
                    awaitReader(book)
                    assertTrue(ReadBook.curTextChapter!!.pages.any { it.text.contains("PRIVATE COPY $choice") })
                    closeReaders()
                    assertTrue(copy.delete())
                    assertNull(AppConfig.defaultBookTreeUri)
                }
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }

    private fun providerUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)

    private fun writeArchive(file: File, contents: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            contents.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }
    private fun awaitLocalPreview(scenario: ActivityScenario<FileAssociationActivity>) {
        await {
            var ready = false
            scenario.onActivity { activity ->
                ready = activity.supportFragmentManager.findFragmentByTag("sharedLocalBooks")
                    ?.view?.findViewById<RecyclerView>(R.id.recycler_view)?.adapter?.itemCount?.let { it > 0 } == true
            }
            ready
        }
    }

    private fun confirmLocalPreview(scenario: ActivityScenario<FileAssociationActivity>) {
        awaitLocalPreview(scenario)
        onView(withId(R.id.tv_ok)).inRoot(isDialog()).perform(click())
    }

    private fun openReader(book: Book) {
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, ReadBookActivity::class.java)
                .putExtra("bookUrl", book.bookUrl).putExtra("inBookshelf", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
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
                ReadBook.curTextChapter?.chapter?.bookUrl == book.bookUrl &&
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
                            dialog.dialog?.window?.decorView?.hasWindowFocus() == true &&
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

    private fun pdfMarkerIsVisible(): Boolean {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return false
        return try {
            (0 until bitmap.height step 4).sumOf { y ->
                (0 until bitmap.width step 4).count { x ->
                    val pixel = bitmap.getPixel(x, y)
                    Color.red(pixel) < 40 && Color.green(pixel) in 80..120 && Color.blue(pixel) > 150
                }
            } > 100
        } finally { bitmap.recycle() }
    }
}
