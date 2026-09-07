package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
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
import io.legado.app.model.localBook.EpubFile
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.toc.ChapterListAdapter
import io.legado.app.ui.book.toc.ChapterListFragment
import io.legado.app.ui.book.toc.TocActivity
import io.legado.app.ui.book.toc.TocListItem
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.dpToPx
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EpubHierarchyNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val titles = listOf("第一卷", "第一章", "第一节", "第二卷", "第一章", "番外 一", "第三卷", "第一章", "番外 二", "番外 三")
    private val depths = listOf(0, 1, 2, 0, 1, 0, 0, 1, 0, 0)

    @Test
    fun reporterEpubRetainsAllTenNavigationNodesAndRealContentUrls() {
        fixture("issue1074-reporter.epub").use { fixture ->
            val toc = EpubFile.getToc(fixture.book)
            assertEquals(titles, toc.map { it.title })
            assertEquals(depths, toc.map { it.depth })
            assertEquals(listOf(null, 0, 1, null, 3, null, null, 6, null, null), toc.map { it.parentId })
            val urls = (1..10).map { "Text/${it.toString().padStart(2, '0')}.html" }
            assertEquals(urls, toc.map { it.href })
            assertEquals(urls, fixture.chapters.map { it.url })
            fixture.chapters.forEachIndexed { index, chapter ->
                assertEquals(index, chapter.index)
                assertEquals(urls.getOrNull(index + 1).orEmpty(), chapter.getVariable("nextUrl"))
                assertTrue(EpubFile.getContent(fixture.book, chapter).orEmpty().contains("正文"))
            }
        }
    }

    @Test
    fun ncxAndEpub3ContainersAliasesAndCrossResourceFragmentsKeepExactBoundaries() {
        for (asset in listOf("issue1074-containers-fragments.epub", "issue1074-nav3-containers.epub")) {
            fixture(asset).use { fixture ->
                val toc = EpubFile.getToc(fixture.book)
                assertEquals(asset, listOf("Container", "First", "Alias", "Second", "Next"), toc.map { it.title })
                assertEquals(listOf(0, 1, 2, 2, 0), toc.map { it.depth })
                assertNull(toc.first().href)
                assertEquals(listOf("a.xhtml#start", "a.xhtml#cut", "b.xhtml#collision"), fixture.chapters.map { it.url })
                assertEquals("a.xhtml#cut", fixture.chapters[0].getVariable("nextUrl"))
                assertEquals("cut", fixture.chapters[0].endFragmentId)
                assertEquals("b.xhtml#collision", fixture.chapters[1].getVariable("nextUrl"))
                val first = EpubFile.getContent(fixture.book, fixture.chapters[0]).orEmpty()
                val second = EpubFile.getContent(fixture.book, fixture.chapters[1]).orEmpty()
                val next = EpubFile.getContent(fixture.book, fixture.chapters[2]).orEmpty()
                assertTrue(first.contains("ALPHA_ONLY"))
                assertFalse(first.contains("BEFORE_START") || first.contains("SECOND_ONLY"))
                assertTrue(second.contains("SECOND_ONLY") && second.contains("TAIL_AFTER_SHARED_ID"))
                assertFalse(second.contains("ALPHA_ONLY") || second.contains("NEXT_RESOURCE_ONLY"))
                assertTrue(next.contains("NEXT_RESOURCE_ONLY"))
                assertFalse(next.contains("BEFORE_NEXT"))
            }
        }
    }

    @Test
    fun oldImportedDirectorySupportsRecursiveUiAndKeepsProgressBookmarksAndReaderTargets() {
        fixture("issue1074-reporter.epub").use { fixture ->
            val book = fixture.book.apply {
                durChapterIndex = 2; durChapterPos = 1; durChapterTitle = titles[2]
            }
            appDb.bookDao.update(book)
            val bookmark = Bookmark(bookName = book.name, chapterIndex = 7, chapterPos = 9, chapterName = titles[7])
            appDb.bookmarkDao.insert(bookmark)
            val identities = identities(book)
            try {
                ActivityScenario.launch<TocActivity>(tocIntent(book)).use { scenario ->
                    await { rows()?.map { it.chapter.title } == titles }
                    assertEquals(depths, rows()!!.map { it.depth })
                    assertVisibleIndent(-1, 0); assertVisibleIndent(-2, 1); assertVisibleIndent(-3, 2)
                    scrollToTop()
                    screenshot("epub-hierarchy-expanded")
                    clickNode(-2, arrow = true)
                    await { rows()?.size == 9 }
                    clickNode(-1, arrow = true)
                    await { rows()?.size == 8 }
                    assertTrue((rows()!!.first() as TocListItem.Volume).containsCurrentChapter)
                    assertTrue(rows()!!.filter { it.chapter.title.startsWith("番外") }.all { it.depth == 0 })
                    screenshot("epub-hierarchy-collapsed")
                    search(scenario, "第一节")
                    await { rows()?.map { it.chapter.title } == titles.take(3) }
                    screenshot("epub-hierarchy-search")
                    closeSearch(scenario)
                    await { rows()?.size == 8 }
                    scenario.onActivity { assertTrue(it.findViewById<View>(R.id.tv_current_chapter_info).performClick()) }
                    await { rows()?.size == 10 }
                    assertTrue(rows()!!.filterIsInstance<TocListItem.Volume>().take(2).all { !it.collapsed })
                    screenshot("epub-hierarchy-current-revealed")
                    reverse(scenario)
                    val reversedTitles = listOf(9, 8, 6, 7, 5, 3, 4, 0, 1, 2).map(titles::get)
                    await { rows()?.map { it.chapter.title } == reversedTitles }
                    screenshot("epub-hierarchy-reversed")
                    scenario.recreate()
                    await { rows()?.map { it.chapter.title } == reversedTitles }
                    assertEquals(2, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
                    assertEquals(1, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
                    assertEquals(identities, identities(book))
                    assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(book.name, book.author))
                }
                ActivityScenario.launch<ReadBookActivity>(readerIntent(book)).use { reader ->
                    await { ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.isCompleted == true }
                    dismissFirstRun(reader)
                    reader.onActivity { it.openChapterList() }
                    await { rows()?.size == 10 }
                    clickNode(-9)
                    await { ReadBook.durChapterIndex == 8 && ReadBook.curTextChapter?.chapter?.url == "Text/09.html" }
                    screenshot("epub-hierarchy-reader-extra")
                    assertEquals(identities, identities(book))
                    assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(book.name, book.author))
                }
            } finally { appDb.bookmarkDao.delete(bookmark) }
        }
    }

    @Test
    fun oldPhysicallyReversedCacheKeepsIndexesAndContainerClicksCannotOpenPreviousContent() {
        fixture("issue1074-containers-fragments.epub").use { fixture ->
            val book = fixture.book.apply {
                setReverseToc(true); durChapterIndex = 1; durChapterPos = 7; durChapterTitle = "Second"
            }
            val reversed = fixture.chapters.reversed().mapIndexed { index, chapter -> chapter.copy(index = index) }
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*reversed.toTypedArray())
            appDb.bookDao.update(book)
            val identities = identities(book)
            ActivityScenario.launch<TocActivity>(tocIntent(book)).use { scenario ->
                await { rows()?.map { it.chapter.title } == listOf("Next", "Container", "First", "Second", "Alias") }
                assertNull(rows()!![1].readingChapter)
                assertEquals(1, rows()!![3].readingChapter!!.index)
                clickNode(-1)
                await { rows()?.map { it.chapter.title } == listOf("Next", "Container") }
                scenario.onActivity { assertFalse(it.isFinishing) }
                clickNode(-1)
                await { rows()?.size == 5 }
                reverse(scenario)
                await { rows()?.map { it.chapter.title } == listOf("Container", "First", "Alias", "Second", "Next") }
                assertEquals(identities, identities(book))
                assertEquals(7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
                screenshot("epub-hierarchy-resource-less-parent")
            }
            ActivityScenario.launch<ReadBookActivity>(readerIntent(book)).use { reader ->
                await { ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.isCompleted == true }
                dismissFirstRun(reader)
                reader.onActivity { it.openChapterList() }
                await { rows()?.size == 5 }
                clickNode(-4)
                await { ReadBook.durChapterIndex == 1 && ReadBook.curTextChapter?.chapter?.url == "a.xhtml#cut" }
                val chapter = ReadBook.curTextChapter!!.chapter
                val content = EpubFile.getContent(book, chapter).orEmpty()
                assertTrue(content.contains("SECOND_ONLY") && content.contains("TAIL_AFTER_SHARED_ID"))
                assertFalse(content.contains("ALPHA_ONLY") || content.contains("NEXT_RESOURCE_ONLY"))
                assertEquals(identities, identities(book))
            }
        }
    }

    private fun fixture(asset: String): Fixture {
        val file = File.createTempFile("epub-hierarchy-", ".epub", context.cacheDir)
        instrumentation.context.assets.open(asset).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val book = Book(bookUrl = file.absolutePath, originName = file.name,
            name = "EPUB ${UUID.randomUUID()}", type = BookType.local or BookType.text, charset = "UTF-8")
        book.setPageAnim(PageAnim.noAnim)
        book.setTocExpanded(true)
        val chapters = EpubFile.getChapterList(book)
        book.totalChapterNum = chapters.size
        book.latestChapterTime = System.currentTimeMillis()
        book.durChapterTitle = chapters.first().title
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        return Fixture(file, book, chapters)
    }

    private class Fixture(val file: File, val book: Book, val chapters: List<BookChapter>) : AutoCloseable {
        override fun close() {
            EpubFile.clear(book.bookUrl)
            appDb.bookDao.delete(book)
            book.coverUrl?.let { File(it).delete() }
            file.delete()
        }
    }

    private fun identities(book: Book) = appDb.bookChapterDao.getChapterList(book.bookUrl).map {
        listOf(it.index, it.url, it.title, it.startFragmentId, it.endFragmentId, it.getVariable("nextUrl"))
    }
    private fun tocIntent(book: Book) = Intent(context, TocActivity::class.java).putExtra("bookUrl", book.bookUrl)
    private fun readerIntent(book: Book) = Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", book.bookUrl)

    private fun rows(): List<TocListItem>? {
        var items: List<TocListItem>? = null
        instrumentation.runOnMainSync { items = (recycler()?.adapter as? ChapterListAdapter)?.getItems()?.toList() }
        return items
    }

    private fun recycler(): RecyclerView? = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<TocActivity>().firstOrNull()
        ?.supportFragmentManager?.fragments?.filterIsInstance<ChapterListFragment>()?.firstOrNull()
        ?.view?.findViewById(R.id.recycler_view)

    private fun clickNode(index: Int, arrow: Boolean = false) {
        await {
            var clicked = false
            instrumentation.runOnMainSync {
                val list = recycler() ?: return@runOnMainSync
                val adapter = list.adapter as? ChapterListAdapter ?: return@runOnMainSync
                val position = adapter.getItems().indexOfFirst { it.chapter.index == index }
                if (position < 0) return@runOnMainSync
                list.scrollToPosition(position)
                val row = list.findViewHolderForAdapterPosition(position)?.itemView ?: return@runOnMainSync
                clicked = (if (arrow) row.findViewById<View>(R.id.end_actions) else row).performClick()
            }
            clicked
        }
    }

    private fun assertVisibleIndent(index: Int, depth: Int) {
        await {
            var ready = false
            instrumentation.runOnMainSync {
                val list = recycler() ?: return@runOnMainSync
                val adapter = list.adapter as? ChapterListAdapter ?: return@runOnMainSync
                val position = adapter.getItems().indexOfFirst { it.chapter.index == index }
                list.scrollToPosition(position)
                val row = list.findViewHolderForAdapterPosition(position)?.itemView ?: return@runOnMainSync
                assertEquals(12.dpToPx() + depth * 10.dpToPx(), row.findViewById<View>(R.id.tv_chapter_item).paddingStart)
                ready = true
            }
            ready
        }
    }

    private fun scrollToTop() = instrumentation.runOnMainSync { recycler()?.scrollToPosition(0) }
    private fun reverse(scenario: ActivityScenario<TocActivity>) = scenario.onActivity { activity ->
        val menu = PopupMenu(activity, activity.window.decorView).menu
        activity.onCompatOptionsItemSelected(menu.add(0, R.id.menu_reverse_toc, 0, "Reverse"))
    }
    private fun search(scenario: ActivityScenario<TocActivity>, query: String) = scenario.onActivity { activity ->
        val search = activity.findViewById<TitleBar>(R.id.title_bar).menu.findItem(R.id.menu_search).actionView as SearchView
        assertTrue(search.findViewById<View>(androidx.appcompat.R.id.search_button).performClick())
        search.setQuery(query, false)
        assertFalse(search.isIconified)
    }
    private fun closeSearch(scenario: ActivityScenario<TocActivity>) = scenario.onActivity { activity ->
        val search = activity.findViewById<TitleBar>(R.id.title_bar).menu.findItem(R.id.menu_search).actionView as SearchView
        search.setQuery("", false)
        assertTrue(search.findViewById<View>(androidx.appcompat.R.id.search_close_btn).performClick())
    }
    private fun dismissFirstRun(reader: ActivityScenario<ReadBookActivity>) = reader.onActivity { activity ->
        activity.supportFragmentManager.fragments.filterIsInstance<ClickActionConfigDialog>()
            .forEach { it.view?.findViewById<View>(R.id.iv_close)?.performClick() }
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream()
                .use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("EPUB directory or reader did not reach the expected state", condition())
    }
}
