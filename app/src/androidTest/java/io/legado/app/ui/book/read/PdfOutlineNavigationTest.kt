package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.model.localBook.PdfFile
import io.legado.app.ui.book.manga.ReadMangaActivity
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.recyclerview.MangaAdapter
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.toc.ChapterListFragment
import io.legado.app.ui.book.toc.ChapterListAdapter
import io.legado.app.ui.book.toc.PdfOutlineAdapter
import io.legado.app.ui.book.toc.TocActivity
import io.legado.app.ui.book.toc.TocViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PdfOutlineNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun outlineRetainsDatabasePositionsAndClickOpensActualTargetImage() {
        PDFBoxResourceLoader.init(context)
        val file = File.createTempFile("navigation-", ".pdf", context.cacheDir)
        PDDocument().use { document ->
            repeat(15) { index ->
                val page = PDPage(PDRectangle(600f, 900f))
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 28f)
                    content.newLineAtOffset(50f, 700f)
                    content.showText("PDF outline: page ${index + 1}")
                    content.endText()
                }
            }
            val outline = PDDocumentOutline()
            document.documentCatalog.documentOutline = outline
            val parent = PDOutlineItem().apply { title = "第一部分" }
            parent.addLast(PDOutlineItem().apply {
                title = "目标十三页"
                setDestination(document.getPage(12))
                addLast(PDOutlineItem().apply {
                    title = "同页小节"
                    setDestination(document.getPage(12))
                })
            })
            outline.addLast(parent)
            outline.addLast(PDOutlineItem().apply { title = "前言"; setDestination(document.getPage(0)) })
            document.save(file)
        }
        val book = Book(bookUrl = file.absolutePath, originName = file.name, type = BookType.local or BookType.text,
            name = "PDF test ${UUID.randomUUID()}", totalChapterNum = 2, durChapterPos = 3)
        book.setImageStyle("FULL")
        val chapters = (0..1).map { BookChapter(bookUrl = book.bookUrl, url = "pdf_$it", title = "分段_$it", index = it) }
        val bookmark = Bookmark(bookName = book.name, chapterIndex = 1, chapterPos = 7, content = "已有书签")
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        appDb.bookmarkDao.insert(bookmark)
        var reader: ActivityScenario<ReadBookActivity>? = null
        var manga: ActivityScenario<ReadMangaActivity>? = null
        try {
            ActivityScenario.launch<TocActivity>(Intent(context, TocActivity::class.java)
                .putExtra("bookUrl", book.bookUrl)).use { tocScenario ->
                waitUntil { outlineRows() == listOf("第一部分", "目标十三页", "同页小节", "前言") }
                screenshot("pdf-outline-expanded")
                clickOutline("第一部分")
                waitUntil { outlineRows() == listOf("第一部分", "前言") }
                screenshot("pdf-outline-collapsed")
                tocScenario.onActivity { ViewModelProvider(it)[TocViewModel::class.java].startChapterListSearch("同页") }
                waitUntil { outlineRows() == listOf("第一部分", "目标十三页", "同页小节") }
                screenshot("pdf-outline-search")
                tocScenario.onActivity { ViewModelProvider(it)[TocViewModel::class.java].startChapterListSearch(null) }
                waitUntil { outlineRows() == listOf("第一部分", "前言") }
                clickOutline("第一部分")
                tocScenario.onActivity { activity ->
                    val menu = PopupMenu(activity, activity.window.decorView).menu
                    activity.onCompatOptionsItemSelected(menu.add(0, R.id.menu_reverse_toc, 0, "反转目录"))
                }
                waitUntil { outlineRows() == listOf("前言", "第一部分", "目标十三页", "同页小节") }
                screenshot("pdf-outline-reversed")
                assertEquals(3, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
                assertEquals(chapters, appDb.bookChapterDao.getChapterList(book.bookUrl))
                assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(book.name, book.author))
            }
            reader = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
                .putExtra("bookUrl", book.bookUrl))
            waitUntil { ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.pages?.isNotEmpty() == true }
            reader.onActivity { it.openChapterList() }
            waitUntil { outlineRows()?.contains("目标十三页") == true }
            clickOutline("目标十三页")
            waitUntil {
                ReadBook.durChapterIndex == 1 && ReadBook.curTextChapter
                    ?.getPageByReadPos(ReadBook.durChapterPos)?.lines
                    ?.flatMap { it.columns }?.filterIsInstance<ImageColumn>()?.firstOrNull()?.src == "12"
            }
            screenshot("pdf-reader-page-13")
            assertEquals(chapters, appDb.bookChapterDao.getChapterList(book.bookUrl))
            assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(book.name, book.author))
            reader.close()
            reader = null
            val mangaScenario = ActivityScenario.launch<ReadMangaActivity>(Intent(context, ReadMangaActivity::class.java)
                .putExtra("bookUrl", book.bookUrl))
            manga = mangaScenario
            waitUntil { ReadManga.book?.bookUrl == book.bookUrl && ReadManga.curMangaChapter != null }
            mangaScenario.onActivity { activity ->
                val menu = PopupMenu(activity, activity.window.decorView).menu
                activity.onCompatOptionsItemSelected(menu.add(0, R.id.menu_catalog, 0, "目录"))
            }
            waitUntil { outlineRows()?.contains("目标十三页") == true }
            clickOutline("目标十三页")
            waitUntil {
                var targetVisible = false
                mangaScenario.onActivity { activity ->
                    val recycler = activity.findViewById<RecyclerView>(R.id.recycler_view)
                    val adapter = recycler.adapter as MangaAdapter
                    targetVisible = (0 until recycler.childCount).any { index ->
                        val position = recycler.getChildAdapterPosition(recycler.getChildAt(index))
                        (adapter.getItem(position) as? MangaPage)?.let {
                            it.chapterIndex == 1 && it.index == 2 && it.mImageUrl == "12"
                        } == true
                    }
                }
                ReadManga.durChapterIndex == 1 && ReadManga.durChapterPos == 2 && targetVisible
            }
            screenshot("pdf-manga-page-13")
            assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(book.name, book.author))
            mangaScenario.close()
            manga = null
            PdfFile.clear(book.bookUrl)
            PDDocument().use { document ->
                repeat(15) { document.addPage(PDPage()) }
                document.save(file)
            }
            ActivityScenario.launch<TocActivity>(Intent(context, TocActivity::class.java)
                .putExtra("bookUrl", book.bookUrl)).use {
                waitUntil {
                    var fallback = false
                    instrumentation.runOnMainSync {
                        val adapter = outlineRecycler()?.adapter
                        fallback = adapter is ChapterListAdapter &&
                            adapter.getItems().map { it.chapter.index } == listOf(1, 0)
                    }
                    fallback
                }
                screenshot("pdf-outline-fallback")
            }
        } finally {
            manga?.close()
            reader?.close()
            PdfFile.clear(book.bookUrl)
            appDb.bookmarkDao.delete(bookmark)
            appDb.bookDao.delete(book)
            file.delete()
        }
    }

    private fun clickOutline(title: String) {
        waitUntil {
            var clicked = false
            instrumentation.runOnMainSync {
                val recycler = outlineRecycler() ?: return@runOnMainSync
                val adapter = recycler.adapter as? PdfOutlineAdapter ?: return@runOnMainSync
                val position = adapter.getItems().indexOfFirst { it.node.title == title }
                if (position < 0) return@runOnMainSync
                recycler.scrollToPosition(position)
                clicked = recycler.findViewHolderForAdapterPosition(position)?.itemView?.performClick() == true
            }
            clicked
        }
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream()
                .use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun outlineRecycler(): RecyclerView? {
        val toc = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<TocActivity>().firstOrNull() ?: return null
        return toc.supportFragmentManager.fragments.filterIsInstance<ChapterListFragment>()
            .firstOrNull()?.view?.findViewById(R.id.recycler_view)
    }

    private fun outlineRows(): List<String>? {
        var rows: List<String>? = null
        instrumentation.runOnMainSync {
            rows = (outlineRecycler()?.adapter as? PdfOutlineAdapter)?.getItems()?.map { it.node.title }
        }
        return rows
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("PDF outline/navigation did not reach the expected state", condition())
    }
}
