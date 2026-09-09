package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.parseReadConfigObject
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.ReadStyleDialog
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import org.hamcrest.Matchers.sameInstance
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReadingLayoutTransitionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun suppliedStylesKeepFullPageAfterCoverScrollCoverTransition() {
        val savedConfigs = ReadBookConfig.configList.map { it.copy() }
        val savedShare = ReadBookConfig.shareConfig.copy()
        val savedShared = ReadBookConfig.shareLayout
        val savedStyle = ReadBookConfig.readStyleSelect
        val savedComic = ReadBookConfig.isComic
        val configFiles = listOf(File(ReadBookConfig.configFilePath), File(ReadBookConfig.shareConfigFilePath))
            .associateWith { it.takeIf(File::exists)?.readBytes() }
        val file = File.createTempFile("layout-transition-", ".txt", context.cacheDir)
        val paragraphs = listOf(
            "窗外的雨渐渐停了。",
            "她推开窗，远处的山谷被薄薄的晨雾笼罩，屋檐上的水珠一颗接一颗落下来。",
            "“现在可以出发了吗？”他把地图展开，指了指那条沿着河岸向北延伸的小路。",
            "大家收拾好行李，仔细检查了门窗，然后穿过安静的院子。石板路上还留着昨夜的积水，天空却已经露出一片清澈的蓝色。",
            "没有人回答。"
        )
        file.writeText((1..140).joinToString("\n\n") { "第${it}段。" + paragraphs[it % paragraphs.size] })
        val book = Book(bookUrl = file.absolutePath, originName = file.name, name = file.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 1, durChapterPos = 1470,
            latestChapterTime = file.lastModified()).apply { setPageAnim(-1) }
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "layout-chapter",
            title = "Layout transition", start = 0L, end = file.length()))
        try {
            instrumentation.runOnMainSync {
                ReadBookConfig.isComic = false
                ReadBookConfig.readStyleSelect = 0
                ReadBookConfig.shareLayout = false
                ReadBookConfig.configList.clear()
                for (name in listOf("cover", "scroll")) {
                    val json = instrumentation.context.assets.open("issue1213-$name.json")
                        .bufferedReader().use { it.readText() }
                    ReadBookConfig.configList += parseReadConfigObject(json).getOrThrow().apply {
                        // The reporter's private font is not attached; retain all supplied layout values.
                        textFont = ""
                    }
                }
                repeat(4) { ReadBookConfig.configList += ReadBookConfig.Config() }
                ReadBookConfig.shareConfig = ReadBookConfig.Config()
            }
            ActivityScenario.launch<ReadBookActivity>(Intent(context, ReadBookActivity::class.java)
                .putExtra("bookUrl", book.bookUrl)).use { scenario ->
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.fragments.filterIsInstance<ClickActionConfigDialog>()
                        .forEach { it.view?.findViewById<View>(R.id.iv_close)?.performClick() }
                }
                awaitReader(scenario, book.bookUrl, false)
                capture(scenario, "layout-cover-before")
                switchStyle(scenario, 1)
                awaitReader(scenario, book.bookUrl, true)
                onView(withId(R.id.read_view)).perform(swipeUp())
                instrumentation.waitForIdleSync()
                await {
                    var stopped = false
                    scenario.onActivity {
                        stopped = it.findViewById<ReadView>(R.id.read_view).pageDelegate?.isRunning != true
                    }
                    stopped
                }
                val scrolled = capture(scenario, "layout-scroll")
                assertTrue("The reproduction must include a nonzero scroll offset", scrolled.offset < 0)
                switchStyle(scenario, 0)
                awaitReader(scenario, book.bookUrl, false)
                val returned = capture(scenario, "layout-cover-returned")
                scenario.recreate()
                awaitReader(scenario, book.bookUrl, false)
                val reopened = capture(scenario, "layout-cover-reopened")
                assertEquals("Horizontal pages must not retain a vertical scroll offset", 0, returned.offset)
                assertTrue("The lower part of the returned page must contain rendered text", returned.bottomPixels > 100)
                assertTrue("Reopening must also render text in the lower part of the page", reopened.bottomPixels > 100)
                assertTrue("The returned page ${returned.pageRange} must contain the last visible reading position ${scrolled.visiblePosition}",
                    scrolled.visiblePosition in returned.pageRange)
                assertTrue("The reopened page must retain the same visible reading position",
                    scrolled.visiblePosition in reopened.pageRange)
            }
        } finally {
            appDb.bookDao.delete(book)
            file.delete()
            TextFile.clear()
            instrumentation.runOnMainSync {
                ReadBookConfig.configList.clear()
                ReadBookConfig.configList.addAll(savedConfigs)
                ReadBookConfig.shareConfig = savedShare
                ReadBookConfig.shareLayout = savedShared
                ReadBookConfig.readStyleSelect = savedStyle
                ReadBookConfig.isComic = savedComic
                ChapterProvider.upStyle()
            }
            configFiles.forEach { (path, bytes) ->
                if (bytes == null) path.delete() else path.writeBytes(bytes)
            }
        }
    }

    private fun switchStyle(scenario: ActivityScenario<ReadBookActivity>, index: Int) {
        scenario.onActivity { ReadStyleDialog().showNow(it.supportFragmentManager, "layout-style") }
        val bounds = Rect()
        lateinit var styleView: View
        try {
            await {
                var visible = false
                scenario.onActivity { activity ->
                    val dialog = activity.supportFragmentManager.findFragmentByTag("layout-style") as? ReadStyleDialog
                    val view = dialog?.view?.findViewById<RecyclerView>(R.id.rv_style)
                        ?.findViewHolderForAdapterPosition(index)?.itemView
                    visible = view?.getGlobalVisibleRect(bounds) == true &&
                        bounds.width() > 20 && bounds.height() > 20
                    if (visible && view != null) {
                        styleView = view
                    }
                }
                visible
            }
            // Espresso waits for the focused dialog and calculates the current screen coordinates.
            // This remains a real touch, required by CircleImageView's circular hit area.
            onView(sameInstance(styleView)).perform(click())
            await { ReadBookConfig.styleSelect == index }
        } finally {
            scenario.onActivity { activity ->
                val dialog = activity.supportFragmentManager.findFragmentByTag("layout-style") as? ReadStyleDialog
                val list = dialog?.view?.findViewById<RecyclerView>(R.id.rv_style)
                File(context.getExternalFilesDir("ui-regression"), "layout-style-window-$index.txt")
                    .writeText("showing=${dialog?.dialog?.isShowing} state=${dialog?.lifecycle?.currentState} " +
                        "focused=${dialog?.dialog?.window?.decorView?.hasWindowFocus()} " +
                        "items=${list?.adapter?.itemCount} children=${list?.childCount} bounds=$bounds")
            }
            capture(scenario, "layout-style-selected-$index")
        }
        pressBack()
    }

    private fun awaitReader(scenario: ActivityScenario<ReadBookActivity>, url: String, scroll: Boolean) {
        await {
            var ready = false
            scenario.onActivity {
                val view = it.findViewById<ReadView>(R.id.read_view)
                ready = ReadBook.book?.bookUrl == url && ReadBook.curTextChapter?.chapter?.bookUrl == url &&
                    ReadBook.curTextChapter?.isCompleted == true && !view.curPage.textPage.isMsgPage &&
                    view.curPage.textPage.lines.size > 3 && view.isScroll == scroll && it.bottomDialog == 0
            }
            ready
        }
        // Header/footer mode changes schedule a 300ms size update before the final pagination.
        SystemClock.sleep(500)
        instrumentation.waitForIdleSync()
    }

    private data class ReadingSnapshot(val offset: Int, val bottomPixels: Int,
        val visiblePosition: Int, val pageRange: IntRange)

    private fun capture(scenario: ActivityScenario<ReadBookActivity>, name: String): ReadingSnapshot {
        val output = context.getExternalFilesDir("ui-regression")!!
        output.mkdirs()
        lateinit var result: ReadingSnapshot
        scenario.onActivity { activity ->
            val readView = activity.findViewById<ReadView>(R.id.read_view)
            val view = readView.curPage.findViewById<ContentTextView>(R.id.content_text_view)
            val offset = ContentTextView::class.java.getDeclaredField("pageOffset").apply { isAccessible = true }.getInt(view)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                var bottomPixels = 0
                for (y in view.height * 2 / 3 until view.height * 9 / 10)
                    for (x in view.width / 10 until view.width * 9 / 10)
                        if (Color.alpha(bitmap.getPixel(x, y)) > 0) bottomPixels++
                File(output, "$name-content.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val page = readView.curPage.textPage
                val visible = readView.getReadPosition()
                val pageRange = page.chapterPosition..(page.chapterPosition + page.charSize)
                result = ReadingSnapshot(offset, bottomPixels, visible?.second?.chapterPosition ?: -1, pageRange)
                File(output, "$name.txt").writeText("scroll=${readView.isScroll} offset=$offset bottomPixels=$bottomPixels\n" +
                    "view=${view.width}x${view.height} provider=${ChapterProvider.viewWidth}x${ChapterProvider.viewHeight}\n" +
                    "visibleChapter=${visible?.first} visiblePosition=${visible?.second?.chapterPosition} visibleText=${visible?.second?.text} pageRange=$pageRange animating=${readView.pageDelegate?.isRunning}\n" +
                    "pageIndex=${page.index} height=${page.height} position=${ReadBook.durChapterPos} lines=" +
                    page.lines.map { "${it.lineTop}:${it.lineBottom}:${it.text}" }.joinToString("\n"))
            } finally { bitmap.recycle() }
        }
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(output, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { screenshot.recycle() }
        return result
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("The actual reader or style selector did not reach the expected state", condition())
    }
}
