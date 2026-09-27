package io.legado.app.ui.main

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.book.updateTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TocUpdateRequestsTest {

    @Test
    fun `book state merge preserves video and sync progress`() {
        val current = Book(
            type = BookType.audio or BookType.notShelf or BookType.updateError,
            kind = "old kind",
            coverUrl = "old cover",
            intro = "old intro",
            latestChapterTitle = "old latest",
            totalChapterNum = 8,
            durChapterIndex = 7,
            durVolumeIndex = 2,
            chapterInVolumeIndex = 3,
            syncTime = 123L,
        )
        val refreshed = Book(
            type = BookType.text,
            kind = "new kind",
            coverUrl = "new cover",
            intro = "new intro",
            latestChapterTitle = "new latest",
            totalChapterNum = 12,
        )

        current.updateTo(refreshed)

        assertEquals(7, refreshed.durChapterIndex)
        assertEquals(2, refreshed.durVolumeIndex)
        assertEquals(3, refreshed.chapterInVolumeIndex)
        assertEquals(123L, refreshed.syncTime)
        assertEquals(
            BookType.text or BookType.notShelf or BookType.updateError,
            refreshed.type,
        )
        assertEquals("new kind", refreshed.kind)
        assertEquals("new cover", refreshed.coverUrl)
        assertEquals("new intro", refreshed.intro)
        assertEquals("new latest", refreshed.latestChapterTitle)
        assertEquals(12, refreshed.totalChapterNum)
    }

    @Test
    fun `selected update filters local and update-disabled books`() {
        val remote = Book(
            bookUrl = "remote",
            origin = "https://example.com",
            type = BookType.text,
        )
        val local = Book(
            bookUrl = "local",
            type = BookType.text or BookType.local,
        )
        val disabled = Book(
            bookUrl = "disabled",
            origin = "https://example.org",
            type = BookType.text,
            canUpdate = false,
        )

        assertEquals(listOf(remote), filterBooksForTocUpdate(listOf(remote, local, disabled)))
    }

    @Test
    fun `skip pre-download wins when merged into a running regular update`() {
        val requests = TocUpdateRequests()
        requests.enqueue("book", TocUpdatePolicy.ALLOW_PRE_DOWNLOAD)
        val request = requireNotNull(requests.poll())

        requests.enqueue("book", TocUpdatePolicy.SKIP_PRE_DOWNLOAD)

        assertEquals(TocUpdatePolicy.SKIP_PRE_DOWNLOAD, requests.close(request))
        requests.finish(request)
        assertTrue(requests.isIdle())
    }

    @Test
    fun `regular update cannot override an existing skip policy`() {
        val requests = TocUpdateRequests()
        requests.enqueue("book", TocUpdatePolicy.SKIP_PRE_DOWNLOAD)
        requests.enqueue("book", TocUpdatePolicy.ALLOW_PRE_DOWNLOAD)
        val request = requireNotNull(requests.poll())

        assertEquals(TocUpdatePolicy.SKIP_PRE_DOWNLOAD, requests.close(request))
    }

    @Test
    fun `selected update refreshes book info when merged before execution`() {
        val requests = TocUpdateRequests()
        requests.enqueue("book", TocUpdatePolicy.ALLOW_PRE_DOWNLOAD)
        requests.enqueue(
            "book",
            TocUpdatePolicy.SKIP_PRE_DOWNLOAD,
            refreshBookInfo = true,
        )
        val request = requireNotNull(requests.poll())

        assertTrue(requests.takeRefreshBookInfo(request))
        assertEquals(TocUpdatePolicy.SKIP_PRE_DOWNLOAD, requests.close(request))
    }

    @Test
    fun `late selected update queues a book info refresh`() {
        val requests = TocUpdateRequests()
        requests.enqueue("book", TocUpdatePolicy.ALLOW_PRE_DOWNLOAD)
        val running = requireNotNull(requests.poll())
        assertFalse(requests.takeRefreshBookInfo(running))

        requests.enqueue(
            "book",
            TocUpdatePolicy.SKIP_PRE_DOWNLOAD,
            refreshBookInfo = true,
        )
        assertEquals(TocUpdatePolicy.SKIP_PRE_DOWNLOAD, requests.close(running))
        requests.finish(running, persistedBookUrl = "moved-book")

        val followUp = requireNotNull(requests.poll())
        assertEquals("moved-book", followUp.bookUrl)
        assertNotEquals(running.generation, followUp.generation)
        assertTrue(requests.takeRefreshBookInfo(followUp))
        assertEquals(TocUpdatePolicy.SKIP_PRE_DOWNLOAD, requests.close(followUp))
    }

    @Test
    fun `closed decision remains running until finally cleanup`() {
        val requests = TocUpdateRequests()
        requests.enqueue("book", TocUpdatePolicy.ALLOW_PRE_DOWNLOAD)
        assertTrue(requests.hasQueued())
        val oldRequest = requireNotNull(requests.poll())
        assertFalse(requests.hasQueued())
        assertEquals(TocUpdatePolicy.ALLOW_PRE_DOWNLOAD, requests.close(oldRequest))
        assertEquals(1, requests.pendingCount())
        assertFalse(requests.isIdle())

        requests.enqueue("book", TocUpdatePolicy.SKIP_PRE_DOWNLOAD)
        assertEquals(1, requests.pendingCount())
        requests.finish(oldRequest)

        assertTrue(requests.isIdle())
        requests.enqueue("book", TocUpdatePolicy.SKIP_PRE_DOWNLOAD)
        val newRequest = requireNotNull(requests.poll())
        assertNotEquals(oldRequest.generation, newRequest.generation)
        assertEquals(TocUpdatePolicy.SKIP_PRE_DOWNLOAD, requests.close(newRequest))
    }

    @Test
    fun `failure cleanup allows the same book to be queued again`() {
        val requests = TocUpdateRequests()
        requests.enqueue("book", TocUpdatePolicy.SKIP_PRE_DOWNLOAD)
        val failedRequest = requireNotNull(requests.poll())

        requests.finish(failedRequest)
        requests.enqueue("book", TocUpdatePolicy.ALLOW_PRE_DOWNLOAD)

        assertEquals(1, requests.pendingCount())
        assertFalse(requests.isIdle())
        assertEquals("book", requests.poll()?.bookUrl)
    }

    @Test
    fun `cancellation clears queued and running policies`() {
        val requests = TocUpdateRequests()
        requests.enqueue("running", TocUpdatePolicy.SKIP_PRE_DOWNLOAD)
        requests.enqueue("queued", TocUpdatePolicy.ALLOW_PRE_DOWNLOAD)
        requireNotNull(requests.poll())

        requests.cancelAll()

        assertTrue(requests.isIdle())
        assertEquals(0, requests.pendingCount())
        assertNull(requests.poll())
    }

    @Test
    fun `management action is wired to the skip pre-download policy`() {
        val manageActivity = source(
            "app/src/main/java/io/legado/app/ui/book/manage/BookshelfManageActivity.kt"
        )
        val mainActivity = source("app/src/main/java/io/legado/app/ui/main/MainActivity.kt")
        val menu = source("app/src/main/res/menu/bookshelf_menage_sel.xml")

        assertTrue(menu.contains("android:id=\"@+id/menu_update_toc\""))
        assertTrue(manageActivity.contains("R.id.menu_update_toc -> updateBooksToc()"))
        assertTrue(manageActivity.contains("postEvent(EventBus.UP_BOOKS_TOC, books)"))
        assertTrue(mainActivity.contains("onlyUpdateRead = false"))
        assertTrue(mainActivity.contains("policy = TocUpdatePolicy.SKIP_PRE_DOWNLOAD"))
        assertTrue(mainActivity.contains("refreshBookInfo = true"))
        assertTrue(menu.contains("android:title=\"@string/update_book_info_toc\""))
        assertTrue(manageActivity.contains("R.string.update_book_info_toc_submitted"))
    }

    @Test
    fun `book info refresh preserves identity and runs toc pre-update rules`() {
        val viewModel = source("app/src/main/java/io/legado/app/ui/main/MainViewModel.kt")
        val bookExtensions = source("app/src/main/java/io/legado/app/help/book/BookExtensions.kt")

        assertTrue(viewModel.contains("tocUpdateRequests.takeRefreshBookInfo(request)"))
        assertTrue(
            viewModel.contains(
                "WebBook.getBookInfoAwait(source, book, canReName = false)"
            )
        )
        assertTrue(viewModel.contains("runPerJs = refreshBookInfo"))
        assertTrue(viewModel.contains("isFromBookInfo = refreshBookInfo"))
        assertTrue(viewModel.contains("appDb.runInTransaction"))
        assertTrue(viewModel.contains("currentBook.origin != source.bookSourceUrl"))
        assertTrue(viewModel.contains("currentBook.name.ifBlank { book.name }"))
        assertTrue(viewModel.contains("currentBook.author.ifBlank { book.author }"))
        assertTrue(viewModel.contains("book.sync(currentBook, toc)"))
        assertTrue(viewModel.contains("replacedBook = currentBook"))
        assertTrue(viewModel.contains("appDb.bookDao.replace(currentBook, book)"))
        assertTrue(viewModel.contains("persistedBookUrl = book.bookUrl"))
        assertTrue(viewModel.contains("tocUpdateRequests.finish(request, persistedBookUrl)"))
        assertTrue(viewModel.contains("appDb.bookDao.getBook(persistedBookUrl)"))
        assertTrue(bookExtensions.contains("newBook.durVolumeIndex = durVolumeIndex"))
        assertTrue(bookExtensions.contains("newBook.chapterInVolumeIndex = chapterInVolumeIndex"))
        assertTrue(bookExtensions.contains("newBook.syncTime = syncTime"))
        assertTrue(bookExtensions.contains("BookHelp.getDurChapter(currentBook, toc)"))
        assertTrue(bookExtensions.contains("ContentProcessor.get(this).getTitleReplaceRules()"))
    }

    @Test
    fun `worker ownership and shelf callback cleanup remain explicit`() {
        val viewModel = source("app/src/main/java/io/legado/app/ui/main/MainViewModel.kt")

        assertTrue(viewModel.contains("private var upTocJobGeneration = 0L"))
        assertTrue(viewModel.contains("start = CoroutineStart.LAZY"))
        assertTrue(viewModel.contains("private fun startUpTocJob()"))
        assertTrue(viewModel.contains("private fun completeUpTocJob("))
        assertTrue(viewModel.contains("if (generation != upTocJobGeneration) return"))
        assertTrue(viewModel.contains("if (tocUpdateRequests.hasQueued())"))
        assertTrue(viewModel.contains("finishShelfRefreshCallbacks()"))
        assertTrue(viewModel.contains("SourceCallBack.END_SHELF_REFRESH"))
    }

    private fun source(relativePath: String): String {
        return File(repositoryRoot(), relativePath).readText()
    }

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
