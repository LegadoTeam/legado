package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.paragraph.ParagraphItem
import io.legado.app.ui.book.read.paragraph.ParagraphReadView
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises actual RecyclerView input, reader callbacks and local chapter loading together. */
@RunWith(AndroidJUnit4::class)
class ParagraphChapterNavigationUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences = listOf(
        PreferKey.paragraphReadMode, PreferKey.paragraphReadControls,
        PreferKey.paragraphReadProgress, PreferKey.paragraphReadChapterSwipe,
        PreferKey.readStyleSelect, PreferKey.shareLayout,
    ).associateWith { preferences.all[it] }
    private val savedHelp = LocalConfig.all["readMenuHelpVersion"] as? Int
    private val savedReadHelp = LocalConfig.all["readHelpVersion"] as? Int
    private val savedConfigs = ReadBookConfig.configList.toList()
    private val savedShare = ReadBookConfig.shareConfig
    private val savedShared = ReadBookConfig.shareLayout
    private val savedStyle = ReadBookConfig.readStyleSelect
    private val savedComic = ReadBookConfig.isComic
    private val configFiles = listOf(File(ReadBookConfig.configFilePath), File(ReadBookConfig.shareConfigFilePath))
        .associateWith { it.takeIf(File::exists)?.readBytes() }
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    private lateinit var textFile: File
    private lateinit var book: Book
    private var imageFile: File? = null

    @Before fun setUp() {
        LocalConfig.edit().putInt("readMenuHelpVersion", 1).putInt("readHelpVersion", 1).commit()
        instrumentation.runOnMainSync {
            preferences.edit().putBoolean(PreferKey.paragraphReadMode, true)
                .putBoolean(PreferKey.paragraphReadControls, true)
                .putBoolean(PreferKey.paragraphReadProgress, true)
                .remove(PreferKey.paragraphReadChapterSwipe).commit()
            ReadBookConfig.configList.clear()
            repeat(6) { ReadBookConfig.configList += ReadBookConfig.Config(bgStr = "#F4F1E9") }
            ReadBookConfig.shareConfig = ReadBookConfig.Config()
            ReadBookConfig.shareLayout = false
            ReadBookConfig.isComic = false
            ReadBookConfig.readStyleSelect = 0
        }
        textFile = File.createTempFile("paragraph-navigation-", ".txt", context.cacheDir)
        val contents = (0..2).map { chapter ->
            (1..(if (chapter == 2) 1 else 60)).joinToString("\n\n") { paragraph ->
                val value = "CHAPTER-$chapter paragraph-$paragraph 段落切章测试。"
                // Being on the final item is not the same as reaching the list's bottom.
                if (chapter == 0 && paragraph == 60) value + "超长末段用于验证必须滚到真正的底部。".repeat(220)
                else value
            } + "\n\n"
        }
        textFile.writeBytes(contents.joinToString("").toByteArray(Charsets.UTF_8))
        book = Book(bookUrl = textFile.absolutePath, originName = textFile.name, name = textFile.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 3,
            durChapterIndex = 1).apply { setPageAnim(PageAnim.noAnim) }
        appDb.bookDao.insert(book)
        var offset = 0L
        val chapters = contents.mapIndexed { index, content ->
            val start = offset
            offset += content.toByteArray(Charsets.UTF_8).size
            BookChapter(bookUrl = book.bookUrl, url = "paragraph-navigation-$index", index = index,
                title = "CHAPTER-$index", start = start, end = offset)
        }
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl))
        awaitReader(1)
        toTop()
    }

    @After fun tearDown() {
        scenario?.close()
        if (::book.isInitialized) {
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
        }
        if (::textFile.isInitialized) textFile.delete()
        imageFile?.delete()
        TextFile.clear()
        instrumentation.runOnMainSync {
            ReadBookConfig.configList.clear()
            ReadBookConfig.configList.addAll(savedConfigs)
            ReadBookConfig.shareConfig = savedShare
            ReadBookConfig.shareLayout = savedShared
            ReadBookConfig.isComic = savedComic
            ReadBookConfig.readStyleSelect = savedStyle
            preferences.edit().apply {
                savedPreferences.forEach { (key, value) ->
                    when (value) {
                        null -> remove(key)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is String -> putString(key, value)
                    }
                }
            }.commit()
            val display = context.resources.displayMetrics
            ReadBookConfig.upBg(display.widthPixels, display.heightPixels)
            ChapterProvider.upStyle()
            ReadBookConfig.saveNow()
        }
        LocalConfig.edit().apply {
            if (savedHelp == null) remove("readMenuHelpVersion") else putInt("readMenuHelpVersion", savedHelp)
            if (savedReadHelp == null) remove("readHelpVersion") else putInt("readHelpVersion", savedReadHelp)
        }.commit()
        configFiles.forEach { (file, bytes) ->
            if (bytes == null) file.delete() else file.writeBytes(bytes)
        }
    }

    @Test fun defaultOffKeepsNativeBoundaryAndEnablingAllowsOneChapterPerTouch() {
        onActivity {
            assertFalse(AppConfig.paragraphReadChapterSwipe)
            val list = list(it)
            assertFalse(hasAction(list, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
            list.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)
            swipe(list, forward = false)
            assertEquals(1, ReadBook.durChapterIndex)
        }
        enableNavigation()
        toTop()
        onActivity {
            assertFalse(hasAction(list(it), AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
            swipe(list(it), forward = false)
            // A second touch while the target layout is pending must not skip another chapter.
            swipe(list(it), forward = false)
            assertEquals(0, ReadBook.durChapterIndex)
        }
        awaitLastItem(0)
        onActivity {
            assertEquals(ReadBook.curTextChapter!!.paragraphItems.size, list(it).adapter!!.itemCount)
            assertTrue(visibleText(list(it)).all { text -> !text.contains("CHAPTER-1") })
            assertTrue("The complete last paragraph must start at the top", list(it).canScrollVertically(1))
        }
        toBottom()
        onActivity {
            assertFalse(hasAction(list(it), AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            swipe(list(it), forward = true)
            swipe(list(it), forward = true)
            assertEquals(1, ReadBook.durChapterIndex)
        }
        awaitFirstItem(1)
        onActivity {
            assertEquals(ReadBook.curTextChapter!!.paragraphItems.size, list(it).adapter!!.itemCount)
            assertTrue(visibleText(list(it)).all { text -> !text.contains("CHAPTER-0") })
        }
    }

    @Test fun accessibilityScrollStaysWithinChapterAndLongFinalParagraphRequiresTouchAtBoundary() {
        enableNavigation()
        onActivity {
            assertTrue(list(it).performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        }
        await("ordinary accessibility scroll") {
            ReadBook.durChapterIndex == 1 && manager(it).findFirstVisibleItemPosition() > 0 &&
                list(it).scrollState == RecyclerView.SCROLL_STATE_IDLE
        }
        toTop()
        onActivity { swipe(list(it), forward = false) }
        awaitLastItem(0)
        onActivity {
            val lastId = ReadBook.curTextChapter!!.paragraphItems.last().id
            assertEquals(lastId, paragraph(it).topItem!!.id)
            assertTrue(list(it).canScrollVertically(1))
            assertTrue(list(it).performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        }
        await("scrolling inside the long final paragraph") {
            ReadBook.durChapterIndex == 0 && manager(it).findFirstVisibleItemPosition() ==
                ReadBook.curTextChapter!!.paragraphItems.lastIndex &&
                manager(it).findViewByPosition(manager(it).findFirstVisibleItemPosition())!!.top < list(it).paddingTop &&
                list(it).scrollState == RecyclerView.SCROLL_STATE_IDLE
        }
        toBottom()
        onActivity {
            assertFalse(list(it).performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
            assertEquals(0, ReadBook.durChapterIndex)
            swipe(list(it), forward = true)
        }
        awaitFirstItem(1)
        toBottom()
        onActivity { swipe(list(it), forward = true) }
        awaitFirstItem(2)
        toBottom()
        onActivity {
            paragraph(it).scrollByPixels(1000)
            paragraph(it).scrollScreen(true)
            assertEquals("Mouse and volume-style scrolling must not switch chapters", 2, ReadBook.durChapterIndex)
            assertFalse("The last chapter must not advertise a nonexistent next chapter",
                hasAction(list(it), AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            assertFalse(list(it).performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
            assertEquals(2, ReadBook.durChapterIndex)
        }
    }

    @Test fun touchRequiresDeliberateOutwardGestureAndExistingButtonStillOpensChapterStart() {
        onActivity { swipe(list(it), forward = false) }
        instrumentation.waitForIdleSync()
        onActivity { assertEquals(1, ReadBook.durChapterIndex) }
        enableNavigation()
        onActivity {
            swipe(list(it), forward = false, cancel = true)
            swipe(list(it), forward = false, horizontal = true)
        }
        instrumentation.waitForIdleSync()
        onActivity {
            assertEquals(1, ReadBook.durChapterIndex)
            // A horizontal drag can still finish as the existing full-width text click.
            // Close that reading menu before testing a separate outward chapter gesture.
            val menu = it.findViewById<ReadMenu>(R.id.read_menu)
            if (menu.visibility == android.view.View.VISIBLE) {
                android.util.Log.i("ParagraphNavigationTest", "Horizontal drag opened the existing reading menu")
                menu.runMenuOut(anim = false)
            }
        }
        await("reading menu closed before the next touch gesture") {
            it.findViewById<ReadMenu>(R.id.read_menu).visibility != android.view.View.VISIBLE &&
                list(it).scrollState == RecyclerView.SCROLL_STATE_IDLE
        }
        toTop()
        onActivity { swipe(list(it), forward = false) }
        awaitLastItem(0)
        toBottom()
        onActivity { swipe(list(it), forward = true) }
        awaitFirstItem(1)
        onActivity {
            val controls = paragraph(it).getChildAt(3) as ViewGroup
            assertTrue(controls.getChildAt(0).performClick())
        }
        awaitFirstItem(0)
        onActivity {
            assertFalse("The first chapter must not advertise a nonexistent previous chapter",
                hasAction(list(it), AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
        }
    }

    @Test fun previousImageTailUsesItsExactIdAndSurvivesActivityRecreation() {
        enableNavigation()
        await("prefetched previous chapter") { ReadBook.prevTextChapter?.isCompleted == true }
        val image = File.createTempFile("paragraph-navigation-", ".png", context.cacheDir).also { imageFile = it }
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        onActivity {
            val previous = ReadBook.prevTextChapter!!
            val tail = previous.paragraphItems.last()
            previous.paragraphItems = previous.paragraphItems + ParagraphItem(
                id = "navigation-image-tail", text = "", imageSrc = image.toURI().toString(),
                // Images and text can share coordinates; only the exact ID identifies this item.
                chapterPosition = tail.chapterPosition,
            )
            swipe(list(it), forward = false)
            assertEquals(0, ReadBook.durChapterIndex)
        }
        awaitLastItem(0)
        onActivity {
            assertEquals("navigation-image-tail", paragraph(it).topItem!!.id)
            assertEquals("navigation-image-tail", ReadBook.book!!.config.paragraphAnchor!!.itemId)
        }
        scenario!!.recreate()
        awaitReader(0)
        await("image anchor restored after recreation") { paragraph(it).topItem?.id == "navigation-image-tail" }
    }

    @Test fun accessibilityBoundaryActionsNeverSwitchChaptersWithTouchNavigationEnabled() {
        enableNavigation()
        toTop()
        onActivity {
            val list = list(it)
            listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id).forEach { action ->
                assertFalse("The list top must not advertise a previous-chapter scroll action", hasAction(list, action))
                assertFalse(list.performAccessibilityAction(action, null))
                assertEquals(1, ReadBook.durChapterIndex)
            }
        }
        toBottom()
        onActivity {
            val list = list(it)
            listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id).forEach { action ->
                assertFalse("The list bottom must not advertise a next-chapter scroll action", hasAction(list, action))
                assertFalse(list.performAccessibilityAction(action, null))
                assertEquals(1, ReadBook.durChapterIndex)
            }
        }
        instrumentation.waitForIdleSync()
        onActivity { assertEquals(1, ReadBook.durChapterIndex) }
    }

    private fun enableNavigation() {
        onActivity {
            preferences.edit().putBoolean(PreferKey.paragraphReadChapterSwipe, true).commit()
            it.updateParagraphMode()
        }
        awaitReader(ReadBook.durChapterIndex)
    }

    private fun toTop() {
        onActivity { manager(it).scrollToPositionWithOffset(0, 0) }
        await("list top") {
            !list(it).isLayoutRequested && !list(it).canScrollVertically(-1) &&
                list(it).scrollState == RecyclerView.SCROLL_STATE_IDLE
        }
    }

    private fun toBottom() {
        onActivity { manager(it).scrollToPositionWithOffset(list(it).adapter!!.itemCount - 1, 0) }
        await("last item layout") {
            !list(it).isLayoutRequested && manager(it).findLastVisibleItemPosition() == list(it).adapter!!.itemCount - 1
        }
        onActivity { list(it).scrollBy(0, Int.MAX_VALUE / 2) }
        await("list bottom") { !list(it).isLayoutRequested && !list(it).canScrollVertically(1) }
    }

    private fun swipe(list: RecyclerView, forward: Boolean, cancel: Boolean = false, horizontal: Boolean = false) {
        val downTime = SystemClock.uptimeMillis()
        val x = list.width * .35f
        val startY = list.height * if (forward) .75f else .25f
        val endY = list.height * if (forward) .25f else .75f
        val endX = if (horizontal) list.width * .85f else x
        fun event(action: Int, elapsed: Long, eventX: Float, eventY: Float) {
            val event = MotionEvent.obtain(downTime, downTime + elapsed, action, eventX, eventY, 0)
            try {
                list.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
        event(MotionEvent.ACTION_DOWN, 0, x, startY)
        event(MotionEvent.ACTION_MOVE, 60, endX, if (horizontal) startY + 2 else endY)
        event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, 120,
            endX, if (horizontal) startY + 2 else endY)
    }

    private fun hasAction(list: RecyclerView, action: Int): Boolean =
        list.createAccessibilityNodeInfo().actionList.any { it.id == action }

    private fun visibleText(list: RecyclerView): List<String> = (0 until list.childCount).flatMap { row ->
        val container = list.getChildAt(row) as ViewGroup
        (0 until container.childCount).map(container::getChildAt).filterIsInstance<TextView>()
            .map { it.text.toString() }
    }

    private fun paragraph(activity: ReadBookActivity) = activity.findViewById<ParagraphReadView>(R.id.paragraph_read_view)
    private fun list(activity: ReadBookActivity) = paragraph(activity).getChildAt(0) as RecyclerView
    private fun manager(activity: ReadBookActivity) = list(activity).layoutManager as LinearLayoutManager
    private fun onActivity(action: (ReadBookActivity) -> Unit) { scenario!!.onActivity(action) }

    private fun awaitReader(chapterIndex: Int) = await("paragraph chapter $chapterIndex") {
        val chapter = ReadBook.curTextChapter
        ReadBook.book?.bookUrl == book.bookUrl && ReadBook.durChapterIndex == chapterIndex &&
            chapter != null && chapter.chapter.index == chapterIndex && chapter.isCompleted && paragraph(it).isShowing(chapter) &&
            list(it).height > 0 && !list(it).isLayoutRequested && !list(it).hasPendingAdapterUpdates() &&
            it.bottomDialog == 0
    }

    private fun awaitLastItem(chapterIndex: Int) {
        awaitReader(chapterIndex)
        await("last paragraph of chapter $chapterIndex") {
            paragraph(it).topItem?.id == ReadBook.curTextChapter!!.paragraphItems.last().id &&
                manager(it).findViewByPosition(ReadBook.curTextChapter!!.paragraphItems.lastIndex)?.top == list(it).paddingTop
        }
    }

    private fun awaitFirstItem(chapterIndex: Int) {
        awaitReader(chapterIndex)
        await("first paragraph of chapter $chapterIndex") {
            paragraph(it).topItem?.id == ReadBook.curTextChapter!!.paragraphItems.first().id &&
                !list(it).canScrollVertically(-1)
        }
    }

    private fun await(description: String, condition: (ReadBookActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Timed out waiting for $description")
    }
}
