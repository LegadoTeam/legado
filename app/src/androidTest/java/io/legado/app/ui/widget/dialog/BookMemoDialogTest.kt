package io.legado.app.ui.widget.dialog

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.text.Spanned
import android.text.TextPaint
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookMemo
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.Restore
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadMenu
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonArray
import io.noties.markwon.core.spans.StrongEmphasisSpan
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class BookMemoDialogTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val savedMemoPref = preferences.all[PreferKey.showBookMemo] as? Boolean
    private val savedComic = ReadBookConfig.isComic
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val savedLastBackup = LocalConfig.lastBackup
    private lateinit var file: File
    private lateinit var book: Book
    private var archive: File? = null
    private var scenario: ActivityScenario<ReadBookActivity>? = null

    @Before fun setUp() {
        preferences.edit().remove(PreferKey.showBookMemo).commit()
        file = File.createTempFile("memo-reader-", ".txt", context.cacheDir)
        file.writeText((1..40).joinToString("\n") { "Line $it: a book with an independent memo." })
        book = Book(bookUrl = file.absolutePath, originName = file.name, name = file.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 1)
            .apply { setPageAnim(PageAnim.noAnim) }
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "memo-chapter",
            title = "Memo chapter", start = 0L, end = file.length()))
        instrumentation.runOnMainSync { ReadBookConfig.isComic = false }
    }

    @After fun cleanUp() {
        scenario?.close()
        appDb.bookDao.delete(book)
        appDb.bookDao.delete(book.copy(bookUrl = book.bookUrl + "-changed"))
        file.delete()
        archive?.parentFile?.deleteRecursively()
        TextFile.clear()
        instrumentation.runOnMainSync { ReadBookConfig.isComic = savedComic }
        preferences.edit().apply {
            if (savedMemoPref == null) remove(PreferKey.showBookMemo)
            else putBoolean(PreferKey.showBookMemo, savedMemoPref)
        }.commit()
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
        LocalConfig.lastBackup = savedLastBackup
    }

    @Test fun defaultOffMenuMarkdownDraftRotationSaveAndClear() {
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl))
        scenario!!.onActivity { activity ->
            activity.supportFragmentManager.fragments.filterIsInstance<ClickActionConfigDialog>()
                .forEach { it.view?.findViewById<View>(R.id.iv_close)?.performClick() }
        }
        await {
            ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.isCompleted == true &&
                !it.findViewById<ReadView>(R.id.read_view).curPage.textPage.isMsgPage && it.bottomDialog == 0
        }
        scenario!!.onActivity {
            it.findViewById<ReadMenu>(R.id.read_menu).runMenuIn(false)
            assertEquals(View.GONE, it.findViewById<View>(R.id.ll_memo).visibility)
        }
        preferences.edit().putBoolean(PreferKey.showBookMemo, true).commit()
        scenario!!.onActivity {
            it.findViewById<ReadMenu>(R.id.read_menu).runMenuIn(false)
        }
        for (id in listOf(R.id.ll_catalog, R.id.ll_read_aloud, R.id.ll_font, R.id.ll_setting, R.id.ll_memo)) {
            onView(withId(id)).check(matches(isCompletelyDisplayed()))
        }
        screenshot("book-memo-five-buttons")
        onView(withId(R.id.ll_memo)).perform(click())
        await { memoDialog(it)?.view?.findViewById<View>(R.id.memo_edit_save)?.isEnabled == true }
        scenario!!.onActivity {
            val window = memoDialog(it)!!.requireDialog().window!!
            val screenHeight = context.resources.displayMetrics.heightPixels
            assertTrue("Memo uses a half-height panel", window.attributes.height in (screenHeight * .4f).toInt()..(screenHeight * .6f).toInt())
        }
        onView(withId(R.id.memo_edit_save)).inRoot(isDialog()).perform(click())
        val markdown = "# 备忘标题\n\n**重要内容**\n\n- 第一条\n- 第二条"
        onView(withId(R.id.memo_editor)).inRoot(isDialog()).perform(replaceText(markdown), closeSoftKeyboard())
        scenario!!.recreate()
        await { memoDialog(it)?.view?.findViewById<EditText>(R.id.memo_editor)?.text?.toString() == markdown }
        onView(withId(R.id.memo_edit_save)).inRoot(isDialog()).perform(click())
        await { memoDialog(it)?.view?.findViewById<View>(R.id.memo_editor)?.visibility == View.GONE &&
            memoDialog(it)?.view?.findViewById<TextView>(R.id.memo_content)?.text?.contains("重要内容") == true }
        assertEquals(markdown, appDb.bookMemoDao.get(book.bookUrl)!!.content)
        scenario!!.onActivity {
            val rendered = memoDialog(it)!!.requireView().findViewById<TextView>(R.id.memo_content).text
            assertTrue(rendered is Spanned)
            val styled = rendered as Spanned
            val bold = styled.getSpans(0, styled.length, StrongEmphasisSpan::class.java).single()
            assertEquals("重要内容", styled.subSequence(styled.getSpanStart(bold), styled.getSpanEnd(bold)).toString())
            val paint = TextPaint()
            bold.updateDrawState(paint)
            assertTrue("Markdown bold must affect actual text drawing", paint.isFakeBoldText)
        }
        screenshot("book-memo-markdown")
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await { it.findViewById<ReadView>(R.id.read_view).width > it.findViewById<ReadView>(R.id.read_view).height &&
            memoDialog(it)?.view?.findViewById<TextView>(R.id.memo_content)?.text?.contains("重要内容") == true }
        onView(withId(R.id.memo_edit_save)).inRoot(isDialog()).check(matches(isCompletelyDisplayed()))
        onView(withId(R.id.memo_clear_cancel)).inRoot(isDialog()).check(matches(isCompletelyDisplayed()))
        screenshot("book-memo-landscape")
        onView(withId(R.id.memo_clear_cancel)).inRoot(isDialog()).perform(click())
        onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click())
        await { memoDialog(it)?.view?.findViewById<TextView>(R.id.memo_content)?.text?.toString() == context.getString(R.string.book_memo_empty) }
        assertEquals("", appDb.bookMemoDao.get(book.bookUrl)!!.content)
    }

    @Test fun actualLanArchiveRestoresMemoWithoutResurrectingClearedContent() = runBlocking {
        BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = it != BackupConfig.bookshelfContentKey }
        BackupConfig.ignoreConfig["localBook"] = false
        appDb.bookMemoDao.save(book.bookUrl, "# Saved in the archive", 100)
        val backup = Backup.backupForLanTransferLocked(context).also { archive = it }
        ZipFile(backup).use { zip ->
            val entry = checkNotNull(zip.getEntry("bookMemo.json"))
            val json = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            val memos = GSON.fromJsonArray<BookMemo>(json).getOrThrow()
            assertEquals("# Saved in the archive", memos.single { it.bookUrl == book.bookUrl }.content)
        }
        appDb.bookDao.delete(book)
        Restore.restoreOrThrow(context, backup.toUri(), lanTransfer = true)
        assertEquals("# Saved in the archive", appDb.bookMemoDao.get(book.bookUrl)!!.content)
        val changed = book.copy(bookUrl = book.bookUrl + "-changed")
        appDb.bookDao.replace(book, changed)
        appDb.bookMemoDao.save(changed.bookUrl, "New memo after changing source", 101)
        // Restoring the old shelf URL also replaces the row through its name/author index.
        Restore.restoreOrThrow(context, backup.toUri(), lanTransfer = true)
        assertNull(appDb.bookMemoDao.get(changed.bookUrl))
        assertEquals("New memo after changing source", appDb.bookMemoDao.get(book.bookUrl)!!.content)
        appDb.bookMemoDao.save(book.bookUrl, "", 102)
        Restore.restoreOrThrow(context, backup.toUri(), lanTransfer = true)
        assertEquals("", appDb.bookMemoDao.get(book.bookUrl)!!.content)
    }

    private fun memoDialog(activity: ReadBookActivity) =
        activity.supportFragmentManager.fragments.filterIsInstance<BookMemoDialog>().firstOrNull()

    private fun await(condition: (ReadBookActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        do {
            var ready = false
            scenario!!.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Book memo did not reach the expected state")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
