package io.legado.app.ui.book.read

import android.content.Intent
import android.os.SystemClock
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.PdfFile
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.toc.ChapterListFragment
import io.legado.app.ui.book.toc.PdfOutlineAdapter
import io.legado.app.ui.book.toc.TocActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PdfOutlineNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun outlineRetainsDatabasePositionsAndClickOpensActualTargetImage() {
        PDFBoxResourceLoader.init(context)
        val file = File.createTempFile("navigation-", ".pdf", context.cacheDir)
        PDDocument().use { document ->
            repeat(15) { document.addPage(PDPage(PDRectangle(600f, 900f))) }
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
        val book = Book(bookUrl = file.absolutePath, originName = file.name,
            name = "PDF test ${UUID.randomUUID()}", totalChapterNum = 2, durChapterPos = 3)
        book.setImageStyle("FULL")
        val chapters = (0..1).map { BookChapter(bookUrl = book.bookUrl, url = "pdf_$it", title = "分段_$it", index = it) }
        val bookmark = Bookmark(bookName = book.name, chapterIndex = 1, chapterPos = 7, content = "已有书签")
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        appDb.bookmarkDao.insert(bookmark)
        var reader: ActivityScenario<ReadBookActivity>? = null
        try {
            ActivityScenario.launch<TocActivity>(Intent(context, TocActivity::class.java)
                .putExtra("bookUrl", book.bookUrl)).use { tocScenario ->
                waitUntil { outlineRows() == listOf("第一部分", "目标十三页", "同页小节", "前言") }
                val reversed = CountDownLatch(1)
                tocScenario.onActivity { it.viewModel.reverseToc { reversed.countDown() } }
                assertTrue(reversed.await(10, TimeUnit.SECONDS))
                assertEquals(3, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
                assertEquals(chapters, appDb.bookChapterDao.getChapterList(book.bookUrl))
                assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(book.name, book.author))
            }
            reader = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
                .putExtra("bookUrl", book.bookUrl))
            waitUntil { ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.pages?.isNotEmpty() == true }
            reader.onActivity { it.openChapterList() }
            waitUntil { outlineRows()?.contains("目标十三页") == true }
            var clicked = false
            waitUntil {
                instrumentation.runOnMainSync {
                    val recycler = outlineRecycler() ?: return@runOnMainSync
                    val adapter = recycler.adapter as? PdfOutlineAdapter ?: return@runOnMainSync
                    val position = adapter.getItems().indexOfFirst { it.node.title == "目标十三页" }
                    if (position < 0) return@runOnMainSync
                    recycler.scrollToPosition(position)
                    clicked = recycler.findViewHolderForAdapterPosition(position)?.itemView?.performClick() == true
                }
                clicked
            }
            waitUntil {
                ReadBook.durChapterIndex == 1 && ReadBook.curTextChapter
                    ?.getPageByReadPos(ReadBook.durChapterPos)?.lines
                    ?.flatMap { it.columns }?.filterIsInstance<ImageColumn>()?.firstOrNull()?.src == "12"
            }
            assertEquals(chapters, appDb.bookChapterDao.getChapterList(book.bookUrl))
            assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(book.name, book.author))
        } finally {
            reader?.close()
            PdfFile.clear(book.bookUrl)
            appDb.bookmarkDao.delete(bookmark)
            appDb.bookDao.delete(book)
            file.delete()
        }
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
