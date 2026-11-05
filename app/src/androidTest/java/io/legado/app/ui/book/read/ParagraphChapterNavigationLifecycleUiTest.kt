package io.legado.app.ui.book.read

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.paragraph.ParagraphReadView
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Reader lifecycle coverage; synthetic speech flags never create a speech service. */
@RunWith(AndroidJUnit4::class)
class ParagraphChapterNavigationLifecycleUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences = listOf(
        PreferKey.paragraphReadMode, PreferKey.paragraphReadControls,
        PreferKey.paragraphReadProgress, PreferKey.paragraphReadChapterSwipe,
        PreferKey.readAloudFollowManualPage, PreferKey.readStyleSelect, PreferKey.shareLayout,
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

    @Before fun setUp() {
        LocalConfig.edit().putInt("readMenuHelpVersion", 1).putInt("readHelpVersion", 1).commit()
        instrumentation.runOnMainSync {
            preferences.edit().putBoolean(PreferKey.paragraphReadMode, true)
                .putBoolean(PreferKey.paragraphReadControls, true)
                .putBoolean(PreferKey.paragraphReadProgress, true)
                .putBoolean(PreferKey.paragraphReadChapterSwipe, true)
                .putBoolean(PreferKey.readAloudFollowManualPage, true).commit()
            ReadBookConfig.configList.clear()
            repeat(6) { ReadBookConfig.configList += ReadBookConfig.Config(bgStr = "#F4F1E9") }
            ReadBookConfig.shareConfig = ReadBookConfig.Config()
            ReadBookConfig.shareLayout = false
            ReadBookConfig.isComic = false
            ReadBookConfig.readStyleSelect = 0
        }
        textFile = File.createTempFile("paragraph-navigation-lifecycle-", ".txt", context.cacheDir)
        val contents = (0..2).map { chapter ->
            (1..80).joinToString("\n\n") { paragraph ->
                "STATE-$chapter paragraph-$paragraph 测试切章后保存的精确段落与阅读位置。"
            } + "\n\n"
        }
        textFile.writeText(contents.joinToString(""), Charsets.UTF_8)
        book = Book(bookUrl = textFile.absolutePath, originName = textFile.name, name = textFile.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 3,
            durChapterIndex = 1).apply { setPageAnim(PageAnim.noAnim) }
        appDb.bookDao.insert(book)
        var offset = 0L
        appDb.bookChapterDao.insert(*contents.mapIndexed { index, content ->
            val start = offset
            offset += content.toByteArray(Charsets.UTF_8).size
            BookChapter(bookUrl = book.bookUrl, url = "paragraph-navigation-lifecycle-$index", index = index,
                title = "STATE-$index", start = start, end = offset)
        }.toTypedArray())
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl))
        awaitReader(1)
        onActivity { manager(it).scrollToPositionWithOffset(0, 0) }
        await("initial chapter top") { !list(it).isLayoutRequested && !list(it).canScrollVertically(-1) }
    }

    @After fun tearDown() {
        scenario?.close()
        if (::book.isInitialized) {
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
        }
        if (::textFile.isInitialized) textFile.delete()
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

    @Test fun boundaryBrowsingKeepsPlayingSpeechCursorAndDetachesFollowing() {
        assertSpeechStateIsPreserved(paused = false)
    }

    @Test fun boundaryBrowsingKeepsPausedSpeechCursorAndDetachesFollowing() {
        assertSpeechStateIsPreserved(paused = true)
    }

    private fun assertSpeechStateIsPreserved(paused: Boolean) {
        val names = listOf("isRun", "pause", "readAloudChapterIndex", "readAloudChapterStart")
        val fields = names.associateWith { name ->
            BaseReadAloudService::class.java.getDeclaredField(name).apply { isAccessible = true }
        }
        var originals: Map<String, Any?> = emptyMap()
        var originallyFollowing = true
        onActivity {
            assertFalse("This fixture must not already have a running speech engine", BaseReadAloudService.isRun)
            assertNull(BaseReadAloudService::class.java.getDeclaredField("currentService")
                .apply { isAccessible = true }.get(null))
            originals = fields.mapValues { (_, field) -> field.get(null) }
            originallyFollowing = ReadAloud.followReadAloudPosition
        }
        try {
            onActivity {
                fields.getValue("isRun").setBoolean(null, true)
                fields.getValue("pause").setBoolean(null, paused)
                fields.getValue("readAloudChapterIndex").setInt(null, 1)
                fields.getValue("readAloudChapterStart").setInt(null, 127)
                ReadAloud.restoreReadAloudFollow()
                assertTrue(ReadAloud.followReadAloudPosition)
                swipePrevious(list(it))
                assertEquals(0, ReadBook.durChapterIndex)
                assertFalse(ReadAloud.followReadAloudPosition)
            }
            awaitLastItem(0)
            onActivity {
                assertTrue(BaseReadAloudService.isRun)
                assertEquals(paused, BaseReadAloudService.pause)
                assertEquals(!paused, BaseReadAloudService.isPlay())
                assertEquals(1, ReadAloud.readAloudChapterIndex)
                assertEquals(127, ReadAloud.readAloudChapterStart)
                assertFalse(ReadAloud.followReadAloudPosition)
                assertNull(BaseReadAloudService::class.java.getDeclaredField("currentService")
                    .apply { isAccessible = true }.get(null))
            }
        } finally {
            instrumentation.runOnMainSync {
                originals.forEach { (name, value) -> fields.getValue(name).set(null, value) }
                if (originallyFollowing) ReadAloud.restoreReadAloudFollow() else ReadAloud.detachReadAloudFollow()
            }
        }
    }

    @Test fun failedLoadingRetainsTailLandingAcrossRecreationAndRetry() {
        startPreviousChapterWithError()
        onActivity {
            assertFalse(paragraph(it).isShowing(ReadBook.curTextChapter!!))
            assertFalse(list(it).performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
            assertFalse(list(it).performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
            ReadBook.saveRead(true)
        }
        await("target chapter saved before recreation") {
            appDb.bookDao.getBook(book.bookUrl)?.durChapterIndex == 0
        }
        scenario!!.recreate()
        onActivity { ReadBook.upMsg(null) }
        awaitLastItem(0)
        onActivity {
            assertEquals(ReadBook.curTextChapter!!.paragraphItems.last().id,
                ReadBook.book!!.config.paragraphAnchor!!.itemId)
        }
    }

    @Test fun selectingSameChapterCancelsFailedTailLandingBeforeFurtherBrowsing() {
        startPreviousChapterWithError()
        onActivity {
            // This public callback is the actual chapter/seek-bar selection path.
            it.skipToChapter(0)
            ReadBook.upMsg(null)
        }
        awaitReader(0)
        await("chapter selection opens the beginning rather than the pending tail") {
            paragraph(it).topItem?.id == ReadBook.curTextChapter!!.paragraphItems.first().id
        }
        var middleId = ""
        onActivity {
            val items = ReadBook.curTextChapter!!.paragraphItems
            val middle = items.size / 2
            middleId = items[middle].id
            manager(it).scrollToPositionWithOffset(middle, 0)
        }
        await("manual browsing after selection") { paragraph(it).topItem?.id == middleId && !list(it).isLayoutRequested }
        var chapterBeforeRefresh: TextChapter? = null
        onActivity {
            paragraph(it).reportTopItem()
            chapterBeforeRefresh = ReadBook.curTextChapter
            ReadBook.loadContent(resetPageOffset = false)
        }
        await("a newly loaded chapter after manual browsing") {
            ReadBook.curTextChapter !== chapterBeforeRefresh && ReadBook.curTextChapter?.isCompleted == true
        }
        awaitReader(0)
        await("content refresh preserves the new middle position") { paragraph(it).topItem?.id == middleId }
        onActivity { assertNotEquals(ReadBook.curTextChapter!!.paragraphItems.last().id, paragraph(it).topItem!!.id) }
    }

    private fun startPreviousChapterWithError() {
        await("completed previous chapter prefetch") { ReadBook.prevTextChapter?.isCompleted == true }
        onActivity {
            // Evict only the completed in-memory prefetch so the real local reader loads the target again.
            ReadBook.prevTextChapter = null
            swipePrevious(list(it))
            assertEquals(0, ReadBook.durChapterIndex)
            ReadBook.upMsg(LOAD_ERROR)
        }
        await("completed chapter while its error screen retains the pending landing") {
            ReadBook.durChapterIndex == 0 && ReadBook.curTextChapter?.isCompleted == true &&
                ReadBook.msg == LOAD_ERROR && !paragraph(it).isShowing(ReadBook.curTextChapter!!)
        }
    }

    private fun paragraph(activity: ReadBookActivity) = activity.findViewById<ParagraphReadView>(R.id.paragraph_read_view)

    private fun swipePrevious(list: RecyclerView) {
        val downTime = SystemClock.uptimeMillis()
        val x = list.width * .35f
        val startY = list.height * .25f
        val endY = list.height * .75f
        listOf(MotionEvent.ACTION_DOWN to startY, MotionEvent.ACTION_MOVE to endY,
            MotionEvent.ACTION_UP to endY).forEachIndexed { index, (action, y) ->
            val event = MotionEvent.obtain(downTime, downTime + index * 60L, action, x, y, 0)
            try {
                list.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private fun list(activity: ReadBookActivity) = paragraph(activity).getChildAt(0) as RecyclerView
    private fun manager(activity: ReadBookActivity) = list(activity).layoutManager as LinearLayoutManager
    private fun onActivity(action: (ReadBookActivity) -> Unit) { scenario!!.onActivity(action) }

    private fun awaitReader(chapterIndex: Int) = await("paragraph chapter $chapterIndex") {
        val chapter = ReadBook.curTextChapter
        ReadBook.book?.bookUrl == book.bookUrl && ReadBook.durChapterIndex == chapterIndex &&
            chapter != null && chapter.chapter.index == chapterIndex && chapter.isCompleted && paragraph(it).isShowing(chapter) &&
            list(it).height > 0 && !list(it).isLayoutRequested && !list(it).hasPendingAdapterUpdates() && it.bottomDialog == 0
    }

    private fun awaitLastItem(chapterIndex: Int) {
        awaitReader(chapterIndex)
        await("last paragraph of chapter $chapterIndex") {
            val last = ReadBook.curTextChapter!!.paragraphItems.lastIndex
            paragraph(it).topItem?.id == ReadBook.curTextChapter!!.paragraphItems[last].id &&
                manager(it).findViewByPosition(last)?.top == list(it).paddingTop
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

    companion object {
        private const val LOAD_ERROR = "Paragraph navigation test: loading failed; retry is available."
    }
}
