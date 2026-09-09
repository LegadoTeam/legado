package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
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
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ImageProvider
import fi.iki.elonen.NanoHTTPD
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Exercises the real reader with cached chapters, controlled HTTP responses and menu gestures. */
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

    @Test fun refreshUsesThemeChangesAndTheExplicitPreloadRange() {
        scenario?.close()
        scenario = null
        val originalCronet = prefs.all[PreferKey.cronet] as Boolean?
        val config = ReadBookConfig.durConfig
        val originalColor = ReadBookConfig.textColor
        val bodyVersion = AtomicInteger(1)
        val imageColor = AtomicInteger(Color.RED)
        val bodyRequests = AtomicIntegerArray(7)
        val imageRequests = AtomicIntegerArray(7)
        val delayNextBody = AtomicBoolean()
        val obsoleteEntered = CountDownLatch(1)
        val releaseObsolete = CountDownLatch(1)
        var obsoleteRead: Deferred<Unit>? = null
        var base = ""
        fun body(index: Int, version: Int) =
            "<img src=\"$base/image/$index.png\">\n" +
                (1..80).joinToString("\n") { "第${it}段。Stable text keeps the same reading position. 阅读位置保持不变。" } +
                "\nVersion $version"
        fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
            return try {
                bitmap.eraseColor(color)
                ByteArrayOutputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally { bitmap.recycle() }
        }
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val index = session.uri.substringAfterLast('/').substringBefore('.').toIntOrNull()
                    ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "missing")
                return if (session.uri.startsWith("/image/")) {
                    imageRequests.incrementAndGet(index)
                    val bytes = png(imageColor.get())
                    newFixedLengthResponse(Response.Status.OK, "image/png", bytes.inputStream(), bytes.size.toLong())
                } else {
                    val version = bodyVersion.get()
                    bodyRequests.incrementAndGet(index)
                    if (index == 3 && delayNextBody.compareAndSet(true, false)) {
                        obsoleteEntered.countDown()
                        check(releaseObsolete.await(15, TimeUnit.SECONDS))
                    }
                    newFixedLengthResponse(Response.Status.OK, "text/plain", body(index, version))
                }.apply { addHeader("Cache-Control", "no-store") }
            }
        }
        server.start()
        base = "http://127.0.0.1:${server.listeningPort}"
        val refreshChapters = (0..6).map {
            BookChapter(bookUrl = book.bookUrl, url = "$base/chapter/$it", index = it,
                title = "Chapter ${it + 1}", baseUrl = base)
        }
        try {
            prefs.edit().putBoolean(PreferKey.cronet, false).putInt(PreferKey.preDownloadNum, 2).commit()
            source.getContentRule().content = "@js:result"
            appDb.bookSourceDao.insert(source)
            book.durChapterIndex = 3
            book.durChapterPos = 0
            book.totalChapterNum = 7
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*refreshChapters.toTypedArray())
            refreshChapters.forEach {
                BookHelp.saveText(book, it, body(it.index, 1))
                BookHelp.writeImage(book, "$base/image/${it.index}.png", png(Color.RED))
            }
            scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
                .putExtra("bookUrl", book.bookUrl).putExtra("inBookshelf", true))
            fun ready(previous: TextChapter? = null) = await("refreshed real reader") {
                val chapter = ReadBook.curTextChapter
                val page = it.findViewById<ReadView>(R.id.read_view).curPage.textPage
                it.isInitFinish && ReadBook.durChapterIndex == 3 && chapter != null &&
                    chapter !== previous && chapter.isCompleted && page.textChapter === chapter && !page.isMsgPage
            }
            fun refresh() {
                showReaderMenu()
                onView(allOf(withId(R.id.menu_refresh), isDisplayed())).perform(click())
            }
            fun pixel(index: Int): Int {
                val bitmap = BitmapFactory.decodeFile(BookHelp.getImage(book, "$base/image/$index.png").path)
                    ?: return 0
                return try { bitmap.getPixel(0, 0) } finally { bitmap.recycle() }
            }
            ready()
            scenario!!.onActivity { ReadBook.skipToPage(1) }
            await("second visible page") { ReadBook.durPageIndex == 1 }
            val position = ReadBook.durChapterPos
            assertTrue(position > 0)
            val before = ReadBook.curTextChapter
            bodyVersion.set(2)
            imageColor.set(Color.GREEN)
            refresh()
            ready(before)
            assertTrue(BookHelp.getContent(book, refreshChapters[3])!!.contains("Version 2"))
            assertEquals("Unchanged theme must preserve the cached image", 0, imageRequests.get(3))
            assertEquals(Color.RED, pixel(3))
            assertEquals(position, ReadBook.durChapterPos)

            scenario!!.onActivity { config.setCurTextColor(originalColor xor 0x00010101) }
            scenario!!.recreate()
            ready()
            val beforeTheme = ReadBook.curTextChapter
            BookHelp.delContent(book, refreshChapters[3])
            delayNextBody.set(true)
            obsoleteRead = CoroutineScope(Dispatchers.IO).async { ReadBook.loadContentAwait(3); Unit }
            assertTrue("An obsolete real reader request must be pending", obsoleteEntered.await(5, TimeUnit.SECONDS))
            bodyVersion.set(3)
            refresh()
            ready(beforeTheme)
            await("same URL has fresh green pixels") { pixel(3) == Color.GREEN }
            assertTrue(BookHelp.getContent(book, refreshChapters[3])!!.contains("Version 3"))
            assertTrue(imageRequests.get(3) > 0)
            assertEquals(position, ReadBook.durChapterPos)
            val refreshedLayout = ReadBook.curTextChapter
            releaseObsolete.countDown()
            runBlocking { withTimeout(5000) { obsoleteRead!!.await() } }
            awaitDraw()
            assertSame("The completed old response must not replace or cancel the fresh layout",
                refreshedLayout, ReadBook.curTextChapter)
            assertTrue(BookHelp.getContent(book, refreshChapters[3])!!.contains("Version 3"))
            scenario!!.onActivity {
                val viewModel = ViewModelProvider(it)[ReadBookViewModel::class.java]
                assertFalse(viewModel.resourceThemeChanged(book))
                val style = ReadBookConfig.styleSelect
                try {
                    ReadBookConfig.styleSelect = (style + 1) % ReadBookConfig.configList.size
                    assertTrue("Switching reader styles must request resource refresh",
                        viewModel.resourceThemeChanged(book))
                } finally { ReadBookConfig.styleSelect = style }
                assertFalse(viewModel.resourceThemeChanged(book))
            }

            val outside = listOf(0, 6).associateWith { BookHelp.getContent(book, refreshChapters[it]) }
            bodyVersion.set(4)
            imageColor.set(Color.BLUE)
            showReaderMenu()
            onView(allOf(withId(R.id.menu_refresh), isDisplayed())).perform(longClick())
            screenshot("resource-refresh-menu")
            var resourceY = 0
            onView(withText(R.string.menu_refresh_resources)).inRoot(isPlatformPopup()).check { view, error ->
                if (error != null) throw error
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                resourceY = location[1]
            }
            listOf(R.string.menu_refresh_dur, R.string.menu_refresh_after, R.string.menu_refresh_all).forEach {
                onView(withText(it)).inRoot(isPlatformPopup()).check { view, error ->
                    if (error != null) throw error
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    assertTrue("Refresh all data must be below the existing actions", location[1] < resourceY)
                }
            }
            val beforeAll = ReadBook.curTextChapter
            onView(withText(R.string.menu_refresh_resources)).inRoot(isPlatformPopup()).perform(click())
            ready(beforeAll)
            await("all five target chapters and image responses") {
                (1..5).all { index ->
                    BookHelp.getContent(book, refreshChapters[index])?.contains("Version 4") == true &&
                        pixel(index) == Color.BLUE
                }
            }
            assertEquals(position, ReadBook.durChapterPos)
            listOf(0, 6).forEach {
                assertEquals(outside[it], BookHelp.getContent(book, refreshChapters[it]))
                assertEquals(0, bodyRequests.get(it))
                assertEquals(0, imageRequests.get(it))
                assertEquals(Color.RED, pixel(it))
            }
            scenario!!.onActivity {
                val bitmap = ImageProvider.getImage(book, "$base/image/3.png", 32)
                assertEquals(Color.BLUE, bitmap.getPixel(0, 0))
            }
            screenshot("resource-refresh-position")
            scenario!!.onActivity { ReadBook.skipToPage(0) }
            await("refreshed image visible on first page") { ReadBook.durPageIndex == 0 }
            closeReaderMenu()
            screenshot("resource-refresh-blue-image")
            File(checkNotNull(context.getExternalFilesDir("ui-regression")), "resource-refresh.txt")
                .writeText("chapter=3 position=$position range=1..5 outside=0,6 preserved; " +
                    "bodyRequests=${(0..6).map { bodyRequests.get(it) }} " +
                    "imageRequests=${(0..6).map { imageRequests.get(it) }}")
        } finally {
            releaseObsolete.countDown()
            obsoleteRead?.cancel()
            scenario?.close()
            scenario = null
            server.stop()
            config.setCurTextColor(originalColor)
            prefs.edit().apply {
                if (originalCronet == null) remove(PreferKey.cronet) else putBoolean(PreferKey.cronet, originalCronet)
            }.commit()
            BookHelp.clearCache(book)
        }
    }

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
            .putExtra("bookUrl", book.bookUrl).putExtra("inBookshelf", true))
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
        awaitDraw()
        var visible = false
        scenario!!.onActivity { visible = it.findViewById<ReadMenu>(R.id.read_menu).isVisible }
        if (!visible) onView(withId(R.id.read_view)).perform(click())
        await("reader menu shown") { it.findViewById<ReadMenu>(R.id.read_menu).isVisible }
    }

    private fun closeReaderMenu() {
        var visible = false
        scenario!!.onActivity { visible = it.findViewById<ReadMenu>(R.id.read_menu).isVisible }
        if (visible) onView(allOf(withId(R.id.vw_menu_bg), isDisplayed())).perform(click())
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
        val page = it.findViewById<ReadView>(R.id.read_view).curPage.textPage
        ReadBook.book?.bookUrl == book.bookUrl && ReadBook.durChapterIndex == index &&
            chapter != null && chapter.chapter.url == chapters[index].url && chapter !== previous && chapter.isCompleted &&
            it.isInitFinish && it.window.decorView.hasWindowFocus() &&
            page.textChapter === chapter && !page.isMsgPage
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
        var pageState = "unavailable"
        scenario!!.onActivity {
            pageState = "messagePage=${it.findViewById<ReadView>(R.id.read_view).curPage.textPage.isMsgPage}, " +
                "readerMenu=${it.findViewById<ReadMenu>(R.id.read_menu).isVisible}, bottomDialog=${it.bottomDialog}"
        }
        throw AssertionError("Timed out waiting for $description; chapter=${ReadBook.durChapterIndex}, " +
            "book=${ReadBook.book?.bookUrl}, url=${chapter?.chapter?.url}, complete=${chapter?.isCompleted}, $pageState, " +
            "cached=${BookHelp.getContent(book, chapters[ReadBook.durChapterIndex.coerceIn(0, 1)])}")
    }

    private fun awaitDraw() {
        instrumentation.waitForIdleSync()
        val rendered = CountDownLatch(1)
        scenario!!.onActivity {
            val decor = it.window.decorView
            decor.postOnAnimation { decor.postOnAnimation { rendered.countDown() } }
        }
        assertTrue(rendered.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        awaitDraw()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = checkNotNull(context.getExternalFilesDir("ui-regression")).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
