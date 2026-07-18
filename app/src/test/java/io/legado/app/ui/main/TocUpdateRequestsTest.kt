package io.legado.app.ui.main

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TocUpdateRequestsTest {

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
        assertTrue(manageActivity.contains("R.string.update_toc_submitted"))
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
