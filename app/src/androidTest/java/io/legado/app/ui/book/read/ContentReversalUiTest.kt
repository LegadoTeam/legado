package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
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
import io.legado.app.data.entities.BookSource
import io.legado.app.help.ReaderMenuConfig
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.utils.defaultSharedPreferences
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses cached online chapters and real reader/menu gestures, with no source requests. */
@RunWith(AndroidJUnit4::class)
class ContentReversalUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val savedPreferences = listOf(PreferKey.readerMenuConfig, PreferKey.preDownloadNum,
        PreferKey.clickActionMC, PreferKey.adaptSpecialStyle).associateWith { prefs.all[it] }
    private val savedHelp = listOf("readHelpVersion", "readMenuHelpVersion")
        .associateWith { LocalConfig.all[it] }
    private val id = UUID.randomUUID().toString()
    private val source = BookSource(bookSourceUrl = "https://example.invalid/reversal-source/$id",
        bookSourceName = "Content reversal fixture")
    private val book = Book(bookUrl = "https://example.invalid/reversal/$id",
        tocUrl = "https://example.invalid/reversal/$id/toc", origin = source.bookSourceUrl,
        name = "Content reversal $id", author = "Fixture", type = BookType.text,
        totalChapterNum = 2, canUpdate = false).apply {
        setPageAnim(PageAnim.noAnim)
        setUseReplaceRule(false)
        setReSegment(false)
        setImageStyle(Book.imgStyleDefault)
    }
    private val chapters = (0..1).map { index ->
        BookChapter(bookUrl = book.bookUrl, url = "${book.bookUrl}/$index", index = index,
            title = "Chapter ${index + 1}", baseUrl = book.bookUrl)
    }
    private val imageClick = "java.toast('ordinary image')"
    private val reviewClick = "java.toast('paragraph review 37')"
    private val imageSrc = "https://example.invalid/$id/image.png," +
        "{\"style\":\"text\",\"click\":\"$imageClick\"}"
    private val reviewSrc = "https://example.invalid/$id/review.png," +
        "{\"style\":\"TEXT\",\"reviewCount\":\"37\",\"click\":\"$reviewClick\"}"
    private val imageTag = "<img src=\"$imageSrc\">"
    private val reviewTag = "<img src=\"$reviewSrc\">"
    private val html = "<usehtml><b>HTML remains complete</b></usehtml>"
    private val original = "甲乙😀$imageTag" + "丙丁$reviewTag" + "戊己\n$html"
    private val reversed = "😀乙甲$imageTag" + "丁丙$reviewTag" + "己戊\n$html"
    private val secondContent = "Second chapter keeps its own state. 😀\nAnother paragraph."
    private var scenario: ActivityScenario<ReadBookActivity>? = null

    @Before fun setUp() {
        prefs.edit().putInt(PreferKey.preDownloadNum, 0).putInt(PreferKey.clickActionMC, 0)
            .putBoolean(PreferKey.adaptSpecialStyle, true).commit()
        AppConfig.clickActionMC = 0
        AppConfig.adaptSpecialStyle = true
        LocalConfig.edit().putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
        saveReaderMenuConfig(context, ReaderMenuConfig(
            primary = listOf("reverseContent", "editContent"),
            more = ReaderMenuConfig.ALL_KEYS - setOf("reverseContent", "editContent")))
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        BookHelp.saveText(book, chapters[0], original)
        BookHelp.saveText(book, chapters[1], secondContent)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(45, 135, 210))
            val bytes = ByteArrayOutputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
            BookHelp.writeImage(book, imageSrc, bytes)
            BookHelp.writeImage(book, reviewSrc, bytes)
        } finally { bitmap.recycle() }
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl).putExtra("inBookshelf", false))
        awaitReader(0)
    }

    @After fun tearDown() {
        scenario?.close()
        chapters.forEach { BookHelp.delContent(book, it) }
        BookHelp.getImage(book, imageSrc).delete()
        BookHelp.getImage(book, reviewSrc).delete()
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        prefs.edit().apply {
            savedPreferences.forEach { (key, value) ->
                when (value) {
                    null -> remove(key)
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                }
            }
        }.commit()
        AppConfig.clickActionMC = prefs.getInt(PreferKey.clickActionMC, 0)
        AppConfig.adaptSpecialStyle = prefs.getBoolean(PreferKey.adaptSpecialStyle, true)
        LocalConfig.edit().apply {
            savedHelp.forEach { (key, value) ->
                if (value == null) remove(key) else putInt(key, value as Int)
            }
        }.commit()
    }

    @Test fun visibleReverseMenuPreservesImagesAndReviewAndRestoresExactRawCache() {
        assertRenderedImages()
        screenshot("content-reversal-original")
        reverseFromMenu(false, reversed)
        assertRenderedImages()
        assertTrue(BookHelp.getContent(book, chapters[0])!!.contains(html))
        screenshot("content-reversal-rendered")
        openOverflow()
        assertReverseCheck(true)
        screenshot("content-reversal-menu-checked")
        pressBack()
        reverseFromMenu(true, original)
        assertEquals("A second real menu action restores every raw character", original,
            BookHelp.getContent(book, chapters[0]))
        assertRenderedImages()
        openOverflow()
        assertReverseCheck(false)
        screenshot("content-reversal-menu-restored")
        pressBack()
    }

    @Test fun chapterCheckedStateRemainsIndependentWhenReturningAndRecreatingReader() {
        reverseFromMenu(false, reversed)
        navigateTo(1)
        openOverflow()
        assertReverseCheck(false)
        pressBack()
        reverseFromMenu(false)
        assertTrue(BookHelp.isContentReversed(book, chapters[1]))
        navigateTo(0)
        openOverflow()
        assertReverseCheck(true)
        pressBack()
        reverseFromMenu(true, original)
        assertTrue("Restoring chapter 1 must keep chapter 2 reversed",
            BookHelp.isContentReversed(book, chapters[1]))
        navigateTo(1)
        scenario!!.recreate()
        awaitReader(1)
        openOverflow()
        assertReverseCheck(true)
        screenshot("content-reversal-second-chapter-returned")
        pressBack()
        assertFalse(BookHelp.isContentReversed(book, chapters[0]))
    }

    @Test fun savingInContentEditorAndReplacingRefreshedCacheClearCheckedState() {
        reverseFromMenu(false, reversed)
        openOverflow()
        onView(withText(R.string.edit_content)).inRoot(isPlatformPopup()).perform(click())
        await("content editor loaded") { activity ->
            activity.supportFragmentManager.fragments.filterIsInstance<ContentEditDialog>()
                .any { it.view != null && it.viewModel.hasDraft }
        }
        val edited = "Saved by the visible content editor. 😀"
        val beforeEdit = ReadBook.curTextChapter
        onView(withId(R.id.content_view)).inRoot(isDialog())
            .perform(replaceText(edited), closeSoftKeyboard())
        onView(withId(R.id.menu_save)).inRoot(isDialog()).perform(click())
        await("editor saved exact text") { BookHelp.getContent(book, chapters[0]) == edited }
        awaitReader(0, beforeEdit)
        openOverflow()
        assertReverseCheck(false)
        pressBack()
        reverseFromMenu(false)
        val refreshed = "Fresh downloaded chapter content. 🙂"
        val beforeRefresh = ReadBook.curTextChapter
        // Exercise the real cache invalidation/write/reload boundary without a source request.
        BookHelp.delContent(book, chapters[0])
        BookHelp.saveText(book, chapters[0], refreshed)
        scenario!!.onActivity { ReadBook.loadContent(0, resetPageOffset = false) }
        awaitReader(0, beforeRefresh)
        assertEquals(refreshed, BookHelp.getContent(book, chapters[0]))
        openOverflow()
        assertReverseCheck(false)
        screenshot("content-reversal-refresh-cleared")
        pressBack()
    }

    private fun reverseFromMenu(wasChecked: Boolean, expectedRaw: String? = null) {
        val index = ReadBook.durChapterIndex
        val previous = ReadBook.curTextChapter
        openOverflow()
        assertReverseCheck(wasChecked)
        onView(withText(R.string.reverse_content)).inRoot(isPlatformPopup()).perform(click())
        await("chapter $index reverse state changed") {
            BookHelp.isContentReversed(book, chapters[index]) != wasChecked &&
                (expectedRaw == null || BookHelp.getContent(book, chapters[index]) == expectedRaw)
        }
        awaitReader(index, previous)
        closeReaderMenu()
    }

    private fun navigateTo(index: Int) {
        showReaderMenu()
        onView(withId(if (index > ReadBook.durChapterIndex) R.id.tv_next else R.id.tv_pre)).perform(click())
        awaitReader(index)
        closeReaderMenu()
    }

    private fun showReaderMenu() {
        var visible = false
        scenario!!.onActivity { visible = it.findViewById<ReadMenu>(R.id.read_menu).isVisible }
        if (!visible) onView(withId(R.id.read_view)).perform(click())
        await("reader menu shown") { it.findViewById<ReadMenu>(R.id.read_menu).isVisible }
    }

    private fun closeReaderMenu() {
        var visible = false
        scenario!!.onActivity { visible = it.findViewById<ReadMenu>(R.id.read_menu).isVisible }
        if (visible) onView(withId(R.id.vw_menu_bg)).perform(click())
        await("reader menu hidden") { !it.findViewById<ReadMenu>(R.id.read_menu).isVisible }
    }

    private fun openOverflow() {
        showReaderMenu()
        val description = context.getString(androidx.appcompat.R.string.abc_action_menu_overflow_description)
        onView(allOf(withContentDescription(description), isDisplayed())).perform(click())
    }

    private fun assertReverseCheck(expected: Boolean) {
        onView(withText(R.string.reverse_content)).inRoot(isPlatformPopup()).check { view, error ->
            if (error != null) throw error
            val row = view.parent as ViewGroup
            val info = row.createAccessibilityNodeInfo()
            assertTrue("The real popup row must expose a checkable action", info.isCheckable)
            assertEquals("The popup accessibility state follows this chapter", expected, info.isChecked)
            assertEquals("The visible check mark follows this chapter", expected,
                row.findViewById<View>(R.id.iv_check_end).isVisible)
        }
    }

    private fun assertRenderedImages() {
        scenario!!.onActivity {
            val chapter = checkNotNull(ReadBook.curTextChapter)
            val images = chapter.pages.flatMap { page -> page.lines }
                .flatMap { line -> line.columns }.filterIsInstance<ImageColumn>()
            assertEquals("Both raw images must survive the actual layout", listOf(imageSrc, reviewSrc),
                images.map { column -> column.src })
            assertEquals(listOf(imageClick, reviewClick), images.map { column -> column.click })
            assertTrue(images.all { column -> column.end > column.start })
            val review = ImageColumn::class.java.getDeclaredField("reviewColumn")
                .apply { isAccessible = true }.get(images[1]) as? ReviewColumn
            assertNotNull("The legacy image must still create the native review bubble", review)
            assertEquals(37, review!!.count)
            assertFalse("The review bubble must stay inline", images[1].textLine.isImage)
            assertTrue("The HTML unit must still produce formatted text",
                chapter.pages.any { page -> page.lines.any { line -> line.isHtml } })
        }
    }

    private fun awaitReader(index: Int, previous: TextChapter? = null) = await("reader chapter $index completed") {
        val chapter = ReadBook.curTextChapter
        ReadBook.book?.bookUrl == book.bookUrl && ReadBook.durChapterIndex == index &&
            chapter != null && chapter.chapter.url == chapters[index].url && chapter !== previous && chapter.isCompleted &&
            !it.findViewById<ReadView>(R.id.read_view).curPage.textPage.isMsgPage
    }

    private fun await(description: String, condition: (ReadBookActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        do {
            var ready = false
            scenario!!.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        val chapter = ReadBook.curTextChapter
        throw AssertionError("Timed out waiting for $description; chapter=${ReadBook.durChapterIndex}, " +
            "url=${chapter?.chapter?.url}, complete=${chapter?.isCompleted}, " +
            "cached=${BookHelp.getContent(book, chapters[ReadBook.durChapterIndex.coerceIn(0, 1)])}")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val rendered = CountDownLatch(1)
        scenario!!.onActivity {
            val decor = it.window.decorView
            decor.postOnAnimation { decor.postOnAnimation { rendered.countDown() } }
        }
        assertTrue(rendered.await(5, TimeUnit.SECONDS))
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = checkNotNull(context.getExternalFilesDir("ui-regression")).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
