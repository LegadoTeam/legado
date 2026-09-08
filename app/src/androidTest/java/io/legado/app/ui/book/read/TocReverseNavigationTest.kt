package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.toc.ChapterListAdapter
import io.legado.app.ui.book.toc.ChapterListFragment
import io.legado.app.ui.book.toc.TocActivity
import io.legado.app.ui.book.toc.TocListItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TocReverseNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun visibleTitlesReverseImmediatelyAndSurviveReopening() {
        fixture(listOf("Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4", "Chapter 5")).use { fixture ->
            ActivityScenario.launch<TocActivity>(tocIntent(fixture.book)).use { scenario ->
                await { visibleTitles() == fixture.titles }
                for (reversed in listOf(true, false)) {
                    reverse()
                    val expected = if (reversed) fixture.titles.reversed() else fixture.titles
                    await { rows()?.map { it.chapter.title } == expected }
                    instrumentation.waitForIdleSync()
                    screenshot("toc-flat-reversed-$reversed")
                    assertEquals("Displayed titles must match the new row identities without reopening", expected, visibleTitles())
                    val stored = appDb.bookDao.getBook(fixture.book.bookUrl)!!
                    assertFalse("The source parser order must not change", stored.getReverseToc())
                    scenario.recreate()
                    await { visibleTitles() == expected }
                }
            }
        }
    }

    @Test fun reverseRetainsVolumeMembershipProloguesAndCurrentVolume() {
        val titles = listOf("Prologue 1", "Prologue 2", "Prologue 3", "Volume A", "A1", "A2", "Volume B", "B1", "B2")
        fixture(titles, setOf(3, 6), current = 7, expanded = false).use { fixture ->
            ActivityScenario.launch<TocActivity>(tocIntent(fixture.book)).use { scenario ->
                await { rows()?.map { it.chapter.title } == listOf("Prologue 1", "Prologue 2", "Prologue 3", "Volume A", "Volume B", "B1", "B2") }
                reverse()
                await { rows()?.firstOrNull()?.chapter?.title == "Volume B" }
                screenshot("toc-current-volume-reversed")
                val current = appDb.bookDao.getBook(fixture.book.bookUrl)!!
                assertEquals("Current chapter identity must survive reversal", "B1",
                    appDb.bookChapterDao.getChapter(current.bookUrl, current.durChapterIndex)!!.title)
                assertEquals(147, current.durChapterPos)
                val expected = listOf("Volume B", "B2", "B1", "Volume A", "Prologue 3", "Prologue 2", "Prologue 1")
                assertEquals(expected, rows()!!.map { it.chapter.title })
                val items = rows()!!
                val volumeB = items.filterIsInstance<TocListItem.Volume>().single { it.chapter.title == "Volume B" }
                assertTrue(volumeB.containsCurrentChapter)
                assertFalse(volumeB.collapsed)
                items.filterIsInstance<TocListItem.Chapter>().forEach { item ->
                    assertEquals(item.chapter.title,
                        if (item.chapter.title.startsWith("Prologue")) null else volumeB.chapter.index,
                        item.parentVolumeIndex)
                }
                scenario.recreate()
                await { rows()?.map { it.chapter.title } == expected }
                reverse()
                await { rows()?.firstOrNull()?.chapter?.title == "Prologue 1" }
                val restored = appDb.bookDao.getBook(current.bookUrl)!!
                assertEquals("B1", appDb.bookChapterDao.getChapter(restored.bookUrl, restored.durChapterIndex)!!.title)
                assertEquals(147, restored.durChapterPos)
            }
        }
    }

    @Test fun returningToReaderKeepsActualChapterAndPositionAndBookmarkTarget() {
        fixture((1..5).map { "Chapter $it" }, current = 3).use { fixture ->
            ActivityScenario.launch<ReadBookActivity>(Intent(context, ReadBookActivity::class.java)
                .putExtra("bookUrl", fixture.book.bookUrl)).use { reader ->
                reader.onActivity { activity ->
                    activity.supportFragmentManager.fragments.filterIsInstance<ClickActionConfigDialog>()
                        .forEach { it.view?.findViewById<View>(R.id.iv_close)?.performClick() }
                }
                await { ReadBook.book?.bookUrl == fixture.book.bookUrl && ReadBook.curTextChapter?.isCompleted == true }
                val originalUrl = ReadBook.curTextChapter!!.chapter.url
                val originalPosition = ReadBook.durChapterPos
                reader.onActivity { it.openChapterList() }
                await { rows()?.size == 5 }
                reverse()
                await { rows()?.firstOrNull()?.chapter?.title == "Chapter 5" }
                instrumentation.runOnMainSync { toc()!!.onBackPressedDispatcher.onBackPressed() }
                await {
                    var readerResumed = false
                    instrumentation.runOnMainSync {
                        readerResumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .any { it is ReadBookActivity }
                    }
                    readerResumed && ReadBook.curTextChapter?.isCompleted == true
                }
                instrumentation.waitForIdleSync()
                screenshot("toc-reverse-return-to-reader")
                assertEquals("Returning from reversed TOC must keep actual text chapter", originalUrl, ReadBook.curTextChapter!!.chapter.url)
                assertEquals("Reversal must not reset reading offset", originalPosition, ReadBook.durChapterPos)
                val bookmark = appDb.bookmarkDao.getByBook(fixture.book.name, fixture.book.author).single()
                assertEquals("Bookmark must still target the same chapter", "Chapter 4",
                    appDb.bookChapterDao.getChapter(fixture.book.bookUrl, bookmark.chapterIndex)!!.title)
            }
        }
    }

    @Test fun legacyStoredReverseOrderKeepsItsChapterIndexesAndParserPreference() {
        fixture((1..5).map { "Chapter $it" }).use { fixture ->
            val book = fixture.book
            val reversed = appDb.bookChapterDao.getChapterList(book.bookUrl).reversed()
                .mapIndexed { index, chapter -> chapter.copy(index = index) }
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*reversed.toTypedArray())
            book.setReverseToc(true)
            book.durChapterIndex = 3
            appDb.bookDao.update(book)
            fun identities() = appDb.bookChapterDao.getChapterList(book.bookUrl).map { listOf(it.index, it.url, it.title) }
            val original = identities()
            ActivityScenario.launch<TocActivity>(tocIntent(book)).use { scenario ->
                await { visibleTitles() == fixture.titles.reversed() }
                reverse()
                await { visibleTitles() == fixture.titles }
                assertEquals(original, identities())
                assertTrue(appDb.bookDao.getBook(book.bookUrl)!!.getReverseToc())
                assertEquals(3, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
                scenario.recreate()
                await { visibleTitles() == fixture.titles }
                reverse()
                await { visibleTitles() == fixture.titles.reversed() }
                assertEquals(original, identities())
            }
        }
    }

    private fun fixture(titles: List<String>, volumes: Set<Int> = emptySet(), current: Int = 1,
                        expanded: Boolean = true): Fixture {
        val file = File.createTempFile("toc-reverse-", ".txt", context.cacheDir)
        val book = Book(bookUrl = file.absolutePath, originName = file.name, name = file.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = titles.size,
            durChapterIndex = current, durChapterPos = 147, durChapterTitle = titles[current]).apply {
            setPageAnim(PageAnim.noAnim)
            setTocExpanded(expanded)
        }
        var offset = 0L
        val chapters = titles.mapIndexed { index, title ->
            val text = (1..40).joinToString("\n", postfix = "\n") { "$title content line $it preserves its original chapter identity." }
            val bytes = text.toByteArray(Charsets.UTF_8)
            file.appendBytes(bytes)
            BookChapter(bookUrl = book.bookUrl, url = "chapter-$index", index = index, title = title,
                isVolume = index in volumes, start = offset, end = offset + bytes.size).also { offset += bytes.size }
        }
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        val bookmark = Bookmark(bookName = book.name, bookAuthor = book.author,
            chapterIndex = current, chapterPos = 147, chapterName = titles[current], bookText = "original location")
        appDb.bookmarkDao.insert(bookmark)
        return Fixture(file, book, bookmark, titles)
    }

    private class Fixture(val file: File, val book: Book, val bookmark: Bookmark, val titles: List<String>) : AutoCloseable {
        override fun close() {
            appDb.bookmarkDao.delete(bookmark)
            appDb.bookDao.delete(book)
            TextFile.clear()
            file.delete()
        }
    }

    private fun tocIntent(book: Book) = Intent(context, TocActivity::class.java).putExtra("bookUrl", book.bookUrl)
    private fun toc() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
        .filterIsInstance<TocActivity>().firstOrNull()
    private fun recycler() = toc()?.supportFragmentManager?.fragments?.filterIsInstance<ChapterListFragment>()
        ?.firstOrNull()?.view?.findViewById<RecyclerView>(R.id.recycler_view)
    private fun rows(): List<TocListItem>? {
        var result: List<TocListItem>? = null
        instrumentation.runOnMainSync { result = (recycler()?.adapter as? ChapterListAdapter)?.getItems()?.toList() }
        return result
    }
    private fun visibleTitles(): List<String>? {
        var result: List<String>? = null
        instrumentation.runOnMainSync {
            val list = recycler() ?: return@runOnMainSync
            result = (0 until (list.adapter?.itemCount ?: 0)).mapNotNull { position ->
                list.findViewHolderForAdapterPosition(position)?.itemView?.findViewById<TextView>(R.id.tv_chapter_name)?.text?.toString()
            }
        }
        return result
    }
    private fun reverse() = instrumentation.runOnMainSync {
        val activity = checkNotNull(toc())
        val menu = PopupMenu(activity, activity.window.decorView).menu
        activity.onCompatOptionsItemSelected(menu.add(0, R.id.menu_reverse_toc, 0, "Reverse"))
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream()
                .use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("TOC or reader did not reach expected state", condition())
    }
}
