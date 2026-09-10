package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.help.ReaderMenuConfig
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.Restore
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportBookSourceViewModel
import io.legado.app.ui.book.read.config.ReaderMenuConfigDialog
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.widget.PopupAction
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Uses the reader's real menu/import UI and uncached HTTP chapter downloads. */
@RunWith(AndroidJUnit4::class)
class ReaderSourceReimportUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val prefKeys = listOf(PreferKey.readerMenuConfig, PreferKey.preDownloadNum,
        PreferKey.clickActionMC, PreferKey.cronet, PreferKey.autoBackup, PreferKey.onlyLatestBackup, PreferKey.importReplaceSource,
        PreferKey.importRememberGroup, PreferKey.importLastGroup, PreferKey.importLastGroupAdd,
        PreferKey.importKeepName, PreferKey.importKeepGroup, PreferKey.importKeepEnable)
    private val savedPrefs = prefKeys.associateWith { prefs.all[it] }
    private val savedLastBackup = LocalConfig.all["lastBackup"]
    private val savedHelp = listOf("readHelpVersion", "readMenuHelpVersion").associateWith { LocalConfig.all[it] }
    private val savedRules = appDb.replaceRuleDao.findEnabledBySourceScope()
    private val savedBackupConfig = HashMap(BackupConfig.ignoreConfig)
    private val id = UUID.randomUUID().toString()
    private val requests = AtomicIntegerArray(3)
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            val index = session.uri.substringAfterLast('/').toIntOrNull()
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "missing")
            requests.incrementAndGet(index)
            return newFixedLengthResponse(Response.Status.OK, "text/html",
                "<div id='before'>OLD HTTP body $index</div><div id='after'>UPDATED HTTP body $index</div>")
                .apply { addHeader("Cache-Control", "no-store") }
        }
    }
    private val source = BookSource(bookSourceUrl = "https://reimport.invalid/$id",
        bookSourceName = "Reimport fixture", bookSourceGroup = "Keep group", lastUpdateTime = 12345L,
        ruleContent = ContentRule(content = "#before@text"))
    private val book = Book(bookUrl = "https://reimport.invalid/book/$id",
        tocUrl = "https://reimport.invalid/book/$id/toc", origin = source.bookSourceUrl,
        name = "Reimport $id", author = "Fixture", type = BookType.text, totalChapterNum = 3,
        canUpdate = false).apply { setPageAnim(PageAnim.noAnim); setUseReplaceRule(false) }
    private val replacement = ReplaceRule(name = "Reimport rule $id", pattern = "#before@text",
        replacement = "#after@text", scopeSource = true, scopeContent = false, isRegex = false)
    private lateinit var chapters: List<BookChapter>
    private lateinit var scenario: ActivityScenario<ReadBookActivity>
    private val backupDir = File(context.cacheDir, "reader-reimport-$id")

    @Before fun setUp() {
        prefs.edit().putInt(PreferKey.preDownloadNum, 0).putInt(PreferKey.clickActionMC, 0)
            .putBoolean(PreferKey.cronet, false).putBoolean(PreferKey.autoBackup, false)
            .putBoolean(PreferKey.onlyLatestBackup, true)
            .putBoolean(PreferKey.importReplaceSource, true).putBoolean(PreferKey.importRememberGroup, false)
            .putBoolean(PreferKey.importKeepName, true).putBoolean(PreferKey.importKeepGroup, true)
            .putBoolean(PreferKey.importKeepEnable, true).commit()
        AppConfig.clickActionMC = 0
        LocalConfig.edit().putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
        savedRules.forEach { appDb.replaceRuleDao.insert(it.copy(isEnabled = false)) }
        appDb.replaceRuleDao.insert(replacement)
        saveReaderMenuConfig(context, ReaderMenuConfig(primary = listOf("reimportSource"),
            more = ReaderMenuConfig.ALL_KEYS - "reimportSource"))
        server.start()
        chapters = (0..2).map { BookChapter(bookUrl = book.bookUrl,
            url = "http://127.0.0.1:${server.listeningPort}/$it", baseUrl = book.bookUrl,
            index = it, title = "Chapter $it") }
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        // The reader eagerly lays out adjacent chapters; only chapter 2 is deliberately uncached.
        chapters.take(2).forEach { BookHelp.saveText(book, it, "Cached chapter ${it.index}. 保留当前正文和阅读位置。") }
        launchReader()
        awaitReader(0)
        source.bookSourceComment = "Fresh database snapshot $id"
        appDb.bookSourceDao.insert(source)
        assertNotEquals(source.bookSourceComment, ReadBook.bookSource?.bookSourceComment)
    }

    @After fun tearDown() {
        if (::scenario.isInitialized && scenario.state != Lifecycle.State.DESTROYED) scenario.close()
        server.stop()
        chapters.forEach { BookHelp.delContent(book, it) }
        CacheBook.cacheBookMap.remove(book.bookUrl)?.stop()
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        appDb.replaceRuleDao.delete(replacement)
        savedRules.forEach { appDb.replaceRuleDao.insert(it) }
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedBackupConfig)
        prefs.edit().apply { savedPrefs.forEach { (key, value) ->
            when (value) {
                null -> remove(key)
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
            }
        } }.commit()
        AppConfig.clickActionMC = prefs.getInt(PreferKey.clickActionMC, 0)
        LocalConfig.edit().apply { savedHelp.forEach { (key, value) ->
            if (value == null) remove(key) else putInt(key, value as Int)
        } }.commit()
        LocalConfig.edit().apply {
            if (savedLastBackup == null) remove("lastBackup") else putLong("lastBackup", savedLastBackup as Long)
        }.commit()
        backupDir.deleteRecursively()
    }

    @Test fun currentDatabaseSourceHasPreviewAndCancelNeverImports() {
        val before = GSON.toJson(appDb.bookSourceDao.getBookSource(source.bookSourceUrl))
        val current = ReadBook.bookSource
        val position = position()
        openReimport()
        main {
            val vm = importer()
            assertEquals(1, vm.allSources.size)
            assertEquals(source.lastUpdateTime, vm.allSources.single().lastUpdateTime)
            assertEquals(listOf(true), vm.selectStatus)
            assertEquals(listOf(false), vm.updateSourceStatus)
            assertTrue(vm.originalSourceJson(0)!!.contains(source.bookSourceComment!!))
            assertEquals("#after@text", vm.allSources.single().ruleContent?.content)
        }
        openPreview()
        screenshot("reader-reimport-preview")
        main {
            val code = dialog().childFragmentManager.fragments.filterIsInstance<CodeDialog>().single()
            assertTrue(code.currentOriginalCode().contains("#before@text"))
            assertTrue(code.binding.codeView.text.toString().contains("#after@text"))
        }
        pressBack()
        onView(withId(R.id.tv_cancel)).inRoot(isDialog()).perform(click())
        await("cancel closes importer") { it.supportFragmentManager.fragments.none { f -> f is ImportBookSourceDialog } }
        assertEquals(before, GSON.toJson(appDb.bookSourceDao.getBookSource(source.bookSourceUrl)))
        assertSame(current, ReadBook.bookSource)
        assertEquals(position, position())
        assertEquals(0, requests.get(2))
    }

    @Test fun confirmedImportSurvivesRecreationAndNextReaderRequestUsesUpdatedRule() {
        val position = position()
        openReimport()
        val oldVm = main { importer() }
        val oldDialog = main { dialog() }
        val oldActivity = main { it }
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val writer = thread(name = "hold-source-import-transaction") {
            try { appDb.runInTransaction { blocked.countDown(); check(release.await(20, TimeUnit.SECONDS)) } }
            catch (failure: Throwable) { error.set(failure) }
        }
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            // Espresso drains the loading animation after a click, which would wait until the
            // transaction gate times out. Invoke the real button without waiting for import idle.
            instrumentation.runOnMainSync {
                val confirm = oldDialog.requireView().findViewById<View>(R.id.tv_ok)
                assertTrue(confirm.isEnabled && confirm.isShown)
                assertTrue(confirm.performClick())
                assertTrue("Import must be pending before recreation", oldVm.sourceUpdatePending.value == true)
                assertFalse(oldVm.importFinished.value == true)
                // ActivityScenario.recreate/onActivity wait for idle, which drains this deliberately
                // blocked import's loading animation. Recreate and inspect through the real lifecycle.
                oldActivity.recreate()
            }
            var restoredPending = false
            val deadline = SystemClock.uptimeMillis() + 15000
            do {
                instrumentation.runOnMainSync {
                    val activity = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<ReadBookActivity>()
                        .singleOrNull { it !== oldActivity }
                    val fragment = activity?.supportFragmentManager?.fragments
                        ?.filterIsInstance<ImportBookSourceDialog>()?.singleOrNull()
                    restoredPending = fragment != null && fragment !== oldDialog &&
                        ViewModelProvider(fragment)[ImportBookSourceViewModel::class.java] === oldVm &&
                        oldVm.sourceUpdatePending.value == true && oldVm.importFinished.value != true
                    if (restoredPending) scenarioDialog = fragment
                }
                if (!restoredPending) SystemClock.sleep(50)
            } while (!restoredPending && SystemClock.uptimeMillis() < deadline)
            assertTrue("Recreated importer must retain the still-pending operation", restoredPending)
        } finally { release.countDown(); writer.join(5000) }
        error.get()?.let { throw AssertionError("DB fixture failed", it) }
        await("successful import publishes and dismisses the recreated dialog") {
            ReadBook.bookSource?.ruleContent?.content == "#after@text" &&
                it.supportFragmentManager.fragments.none { f -> f is ImportBookSourceDialog }
        }
        assertEquals(position, position())
        val savedBook = appDb.bookDao.getBook(book.bookUrl)!!
        assertEquals(book.origin, savedBook.origin)
        assertEquals(book.name, savedBook.name)
        assertEquals(book.author, savedBook.author)
        assertEquals("#after@text", appDb.bookSourceDao.getBookSource(book.origin)!!.ruleContent?.content)
        assertEquals(source.lastUpdateTime, ReadBook.bookSource!!.lastUpdateTime)
        assertEquals(source.bookSourceGroup, ReadBook.bookSource!!.bookSourceGroup)
        assertEquals(0, requests.get(2))
        showMenu()
        onView(withId(R.id.tv_next)).perform(click())
        awaitReader(1)
        await("next uncached reader request finishes") {
            requests.get(2) > 0 && ReadBook.nextTextChapter?.chapter?.index == 2 &&
                ReadBook.nextTextChapter?.isCompleted == true
        }
        assertTrue(BookHelp.getContent(book, chapters[2])!!.contains("UPDATED HTTP body 2"))
        assertFalse(BookHelp.getContent(book, chapters[2])!!.contains("OLD HTTP body"))
        showMenu()
        onView(withId(R.id.tv_next)).perform(click())
        awaitReader(2)
        screenshot("reader-reimport-next-http-chapter")
    }

    @Test fun completedOldImporterCannotReplaceAnotherBooksActiveSource() {
        val otherSource = source.copy(bookSourceUrl = source.bookSourceUrl + "/other", bookSourceName = "Other source")
        val otherBook = book.copy(bookUrl = book.bookUrl + "/other", origin = otherSource.bookSourceUrl,
            name = "Other book $id", totalChapterNum = 1)
        val otherChapter = chapters[0].copy(bookUrl = otherBook.bookUrl)
        appDb.bookSourceDao.insert(otherSource)
        appDb.bookDao.insert(otherBook)
        appDb.bookChapterDao.insert(otherChapter)
        BookHelp.saveText(otherBook, otherChapter, "Other book remains unchanged.")
        try {
            openReimport()
            val pendingVm = main { importer() }
            // The user can open another reader while this importer remains on the back stack.
            main {
                it.startActivity(Intent(it, ReadBookActivity::class.java)
                    .putExtra("bookUrl", otherBook.bookUrl).putExtra("inBookshelf", true))
            }
            await("second real reader completes before the old import") {
                ReadBook.book?.bookUrl == otherBook.bookUrl && it.isInitFinish &&
                    ReadBook.curTextChapter?.chapter?.bookUrl == otherBook.bookUrl &&
                    ReadBook.curTextChapter?.isCompleted == true
            }
            val position = listOf(ReadBook.durChapterIndex, ReadBook.durChapterPos)
            // Execute the retained confirmation with the original dialog VM, as an already queued
            // operation would complete after the current book changes.
            instrumentation.runOnMainSync { pendingVm.importSelect() }
            val importDeadline = SystemClock.uptimeMillis() + 15000
            while (pendingVm.importFinished.value != true && SystemClock.uptimeMillis() < importDeadline) {
                SystemClock.sleep(50)
            }
            assertTrue(pendingVm.importFinished.value == true)
            assertEquals("#after@text", appDb.bookSourceDao.getBookSource(source.bookSourceUrl)!!.ruleContent?.content)
            assertEquals(otherBook.bookUrl, ReadBook.book?.bookUrl)
            assertEquals(otherSource.bookSourceUrl, ReadBook.bookSource?.bookSourceUrl)
            assertEquals("#before@text", ReadBook.bookSource?.ruleContent?.content)
            assertEquals(position, listOf(ReadBook.durChapterIndex, ReadBook.durChapterPos))
        } finally {
            BookHelp.delContent(otherBook, otherChapter)
            CacheBook.cacheBookMap.remove(otherBook.bookUrl)?.stop()
            appDb.bookChapterDao.delByBook(otherBook.bookUrl)
            appDb.bookDao.delete(otherBook)
            appDb.bookSourceDao.delete(otherSource)
        }
    }

    @Test fun menuConfigurationAndSettingsBackupPreserveReimportAction() {
        openOverflow()
        onView(withText(R.string.reader_menu_all_features)).inRoot(isPlatformPopup()).perform(click())
        await("native reader menu configuration") {
            it.supportFragmentManager.fragments.filterIsInstance<ReaderMenuConfigDialog>().any { f -> f.view != null }
        }
        onView(allOf(withId(R.id.check_box), withText(R.string.reimport_book_source))).inRoot(isDialog()).perform(click())
        assertEquals(listOf("reimportSource"), loadReaderMenuConfig(context).more.filter { it == "reimportSource" })
        assertFalse("reimportSource" in loadReaderMenuConfig(context).primary)
        pressBack()
        scenario.recreate()
        awaitReader(0)
        openOverflow()
        onView(withText(R.string.reimport_book_source)).check(doesNotExist())
        onView(withText(R.string.reader_menu_more)).inRoot(isPlatformPopup()).perform(click())
        // Moving an action to More appends it after the other actions, below this popup's viewport.
        onView(withId(R.id.recycler_view)).inRoot(isPlatformPopup()).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(RecyclerView::class.java)
            override fun getDescription() = "scroll More to its reimport action"
            override fun perform(uiController: UiController, view: View) {
                val recycler = view as RecyclerView
                val items = (recycler.adapter as PopupAction.Adapter).getItems()
                val target = items.indexOfFirst { it.title == context.getString(R.string.reimport_book_source) }
                assertTrue("More must contain the configured reimport action: $items", target >= 0)
                recycler.scrollToPosition(target)
                uiController.loopMainThreadUntilIdle()
            }
        })
        onView(withText(R.string.reimport_book_source)).inRoot(isPlatformPopup()).perform(click())
        awaitImporter()
        onView(withId(R.id.tv_cancel)).inRoot(isDialog()).perform(click())
        scenario.close()
        val expected = loadReaderMenuConfig(context)
        runBlocking(Dispatchers.IO) {
            backupDir.mkdirs()
            BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = it != BackupConfig.settingContentKey }
            BackupConfig.ignoreConfig["readConfig"] = false
            Backup.backupLocked(context, backupDir.path, uploadWebDav = false)
            saveReaderMenuConfig(context, ReaderMenuConfig.default())
            Restore.restoreOrThrow(context, File(backupDir, "backup.zip").toUri(), lanTransfer = true)
        }
        assertEquals(expected, loadReaderMenuConfig(context))
    }

    @Test fun deletedSourceDoesNotOpenAnEmptyImporterAndLocalBookHasNoAction() {
        appDb.bookSourceDao.delete(source)
        openOverflow()
        onView(withText(R.string.reimport_book_source)).inRoot(isPlatformPopup()).perform(click())
        // Wait for the actual Room read rather than accepting an absent dialog before it completes.
        await("missing-source lookup completes") {
            !ViewModelProvider(it)[ReadBookViewModel::class.java].sourceReimportLoading
        }
        main {
            assertTrue(it.supportFragmentManager.fragments.none { f -> f is ImportBookSourceDialog })
            assertNull(ViewModelProvider(it)[ReadBookViewModel::class.java].pendingSourceReimport.value)
            ReadBook.book = ReadBook.book!!.copy(origin = BookType.localTag, type = 0)
            it.refreshReaderMenu()
        }
        openOverflow()
        onView(withText(R.string.reimport_book_source)).check(doesNotExist())
        pressBack()
        main { ReadBook.book = book }
    }

    private fun launchReader() {
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl).putExtra("inBookshelf", true))
    }

    private fun position() = main { listOf(ReadBook.book?.bookUrl, ReadBook.book?.origin,
        ReadBook.durChapterIndex.toString(), ReadBook.durChapterPos.toString()) }

    private fun dialog() = checkNotNull(scenarioDialog)
    private var scenarioDialog: ImportBookSourceDialog? = null
    private fun importer() = ViewModelProvider(dialog())[ImportBookSourceViewModel::class.java]

    private fun openReimport() {
        openOverflow()
        onView(withText(R.string.reimport_book_source)).inRoot(isPlatformPopup()).perform(click())
        awaitImporter()
    }

    private fun awaitImporter() = await("one current-source import candidate") {
        scenarioDialog = it.supportFragmentManager.fragments.filterIsInstance<ImportBookSourceDialog>().singleOrNull()
        val dialog = scenarioDialog ?: return@await false
        val vm = ViewModelProvider(dialog)[ImportBookSourceViewModel::class.java]
        vm.successLiveData.value == 1 && vm.sourceUpdatePending.value != true &&
            dialog.view?.findViewById<RecyclerView>(R.id.recycler_view)?.findViewHolderForAdapterPosition(0) != null
    }

    private fun openPreview() {
        main {
            dialog().requireView().findViewById<RecyclerView>(R.id.recycler_view)
                .findViewHolderForAdapterPosition(0)!!.itemView.findViewById<View>(R.id.tv_open).performClick()
        }
        await("native source code preview") {
            dialog().childFragmentManager.fragments.filterIsInstance<CodeDialog>().singleOrNull()
                ?.dialog?.window?.decorView?.hasWindowFocus() == true
        }
    }

    private fun showMenu() {
        awaitDraw()
        if (!main { it.findViewById<ReadMenu>(R.id.read_menu).isVisible }) {
            onView(withId(R.id.read_view)).perform(click())
        }
        await("reader menu visible") { it.findViewById<ReadMenu>(R.id.read_menu).isVisible }
    }

    private fun awaitDraw() {
        instrumentation.waitForIdleSync()
        val rendered = CountDownLatch(1)
        scenario.onActivity {
            val decor = it.window.decorView
            decor.postOnAnimation { decor.postOnAnimation { rendered.countDown() } }
        }
        assertTrue(rendered.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun openOverflow() {
        showMenu()
        onView(allOf(withContentDescription(context.getString(androidx.appcompat.R.string.abc_action_menu_overflow_description)),
            isDisplayed())).perform(click())
    }

    private fun awaitReader(index: Int) = await("reader chapter $index") {
        val chapter = ReadBook.curTextChapter
        ReadBook.book?.bookUrl == book.bookUrl && ReadBook.durChapterIndex == index &&
            chapter?.chapter?.index == index && chapter.isCompleted && it.isInitFinish &&
            !it.findViewById<ReadView>(R.id.read_view).curPage.textPage.isMsgPage
    }

    private fun await(description: String, condition: (ReadBookActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        do {
            if (main(condition)) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out: $description; source=${ReadBook.bookSource?.ruleContent}, chapter=${ReadBook.durChapterIndex}")
    }

    private fun <T> main(block: (ReadBookActivity) -> T): T {
        var value: T? = null
        scenario.onActivity { value = block(it) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = checkNotNull(context.getExternalFilesDir("ui-regression")).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
