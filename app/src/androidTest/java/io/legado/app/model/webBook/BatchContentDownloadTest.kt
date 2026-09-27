package io.legado.app.model.webBook

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.help.book.BookHelp
import io.legado.app.model.BatchContentContext
import io.legado.app.model.CacheBook
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class BatchContentDownloadTest {
    private val saveAll = """
        for (var i = 0; i < chapters.length; i++) {
            if (!java.cacheContent(chapters[i], 'batch-' + chapters[i].index)) throw 'save failed';
        }
    """.trimIndent()

    @Test
    fun documentedWrappersAndBareJavascriptSaveRealChapterFiles() = runBlocking {
        for (wrap in listOf<(String) -> String>(
            { it }, { "<js>$it</js>" }, { "@js:$it" }, { "<js>$it</js><js>$it</js>" },
        )) {
            Fixture().use { f ->
                f.source.ruleContent!!.contentBatch = wrap(saveAll)
                assertTrue(WebBook.getContentBatchAwait(f.source, f.book, f.chapters).isEmpty())
                f.chapters.forEach { assertEquals("batch-${it.index}", BookHelp.getContent(f.book, it)) }
            }
        }
    }

    @Test
    fun replacementUsesChapterAbsoluteUrlAndRejectsBlankOutput() = runBlocking {
        Fixture().use { f ->
            f.source.ruleContent!!.contentBatch = saveAll
            f.source.ruleContent!!.replaceRegex = "@js:result + ':' + baseUrl"
            assertTrue(WebBook.getContentBatchAwait(f.source, f.book, f.chapters).isEmpty())
            f.chapters.forEach {
                assertEquals("　　batch-${it.index}:${it.getAbsoluteURL()}", BookHelp.getContent(f.book, it))
            }
            BookHelp.clearCache(f.book)
            f.source.ruleContent!!.replaceRegex = "@js:' \\n\\t '"
            // cacheContent must return false after replacement, even for nonempty input.
            f.source.ruleContent!!.contentBatch = "if (java.cacheContent(chapters[0], 'nonempty')) throw 'blank saved';"
            assertEquals(f.chapters, WebBook.getContentBatchAwait(f.source, f.book, f.chapters))
            assertNull(BookHelp.getContent(f.book, f.chapters[0]))
        }
    }

    @Test
    fun delayedBatchCannotOverwriteAnAuthoritativeEditForEitherSourceType() = runBlocking {
        withTimeout(15000) {
            for (javascriptSource in listOf(false, true)) {
                Fixture().use { f ->
                    val body = """
                        java.ajax('${f.server.url}/batch');
                        if (java.cacheContent(chapters[0], 'stale')) throw 'overwrote edit';
                        java.cacheContent(chapters[1], 'fresh');
                    """.trimIndent()
                    f.setBatch(body, javascriptSource)
                    val batch = async(Dispatchers.IO) { WebBook.getContentBatchAwait(f.source, f.book, f.chapters) }
                    f.server.awaitRequest()
                    val edited = f.chapters[0].copy(title = "edited title")
                    BookHelp.saveText(f.book, edited, "authoritative edit", saveChapterMetadata = true)
                    f.server.release.countDown()
                    assertEquals(listOf(f.chapters[0]), batch.await())
                    assertEquals("authoritative edit", BookHelp.getContent(f.book, f.chapters[0]))
                    assertEquals("edited title", appDb.bookChapterDao.getChapter(f.book.bookUrl, 0)!!.title)
                    assertEquals("fresh", BookHelp.getContent(f.book, f.chapters[1]))
                }
            }
        }
    }

    @Test
    fun readingAndReadAloudJoinBatchAndCancellingOneWaiterKeepsTheProducer() = runBlocking {
        withTimeout(15000) {
            Fixture().use { f ->
                f.setBatch(saveAll)
                assertTrue(f.model.downloadBatchAwait(f.chapters).isEmpty())
                BookHelp.clearCache(f.book)
                val currentModel = CacheBook.getOrCreate(f.source, f.book)
                assertNotSame(f.model, currentModel)
                f.setBatch("java.ajax('${f.server.url}/batch');\n$saveAll")
                val batch = async(Dispatchers.IO) { currentModel.downloadBatchAwait(f.chapters) }
                f.server.awaitRequest()
                currentModel.addDownload(0, 1)
                currentModel.stop()
                assertSame(currentModel, CacheBook.getOrCreate(f.source, f.book))
                // These are the shared entry used by ReadBook and BaseReadAloudService.
                val cancelledReader = async(start = CoroutineStart.UNDISPATCHED) { f.model.downloadAwait(f.chapters[0]) }
                val readAloud = async(start = CoroutineStart.UNDISPATCHED) { f.model.downloadAwait(f.chapters[0]) }
                cancelledReader.cancelAndJoin()
                assertTrue(batch.isActive)
                f.server.release.countDown()
                assertTrue(batch.await().isEmpty())
                assertEquals("batch-0", readAloud.await())
                assertEquals(1, f.server.batchRequests.get())
                assertEquals(0, f.server.singleRequests.get())
                assertEquals(0, currentModel.onDownloadCount)
                assertEquals(0, currentModel.waitCount)
            }
        }
    }

    @Test
    fun cancellingBorrowedManualWorkRestoresQueueAndManualSingleFallbackCompletes() = runBlocking {
        withTimeout(15000) {
            Fixture().use { f ->
                f.setBatch("java.ajax('${f.server.url}/batch');\n$saveAll")
                f.model.addDownload(0, 1)
                val batch = async(Dispatchers.IO) { f.model.downloadBatchAwait(f.chapters) }
                f.server.awaitRequest()
                batch.cancel()
                f.server.release.countDown()
                batch.join()
                assertEquals(2, f.model.waitCount)
                assertEquals(0, f.model.onDownloadCount)
                f.source.ruleContent!!.maxBatchSize = 1
                while (f.model.waitCount > 0) coroutineScope { f.model.download(this, Dispatchers.IO) }
                assertEquals(2, f.server.singleRequests.get())
                f.chapters.forEach { assertTrue(BookHelp.getContent(f.book, it)!!.contains("single")) }
            }
        }
    }

    @Test
    fun partialScriptFailureKeepsSavedChapterAndOnlyRetriesMissingChapter() = runBlocking {
        withTimeout(15000) {
            Fixture().use { f ->
                f.setBatch("java.cacheContent(chapters[0], 'first saved'); throw 'second missing';")
                f.model.addDownload(0, 1)
                assertEquals(listOf(f.chapters[1]), f.model.downloadBatchAwait(f.chapters))
                assertEquals(1, f.model.waitCount)
                coroutineScope { f.model.download(this, Dispatchers.IO) }
                assertEquals("first saved", BookHelp.getContent(f.book, f.chapters[0]))
                assertTrue(BookHelp.getContent(f.book, f.chapters[1])!!.contains("single"))
                assertEquals(1, f.server.singleRequests.get())
                assertEquals(0, f.model.onDownloadCount)
            }
        }
    }

    @Test
    fun cancellationBeforeLazyStartupReleasesEveryClaim() = runBlocking {
        Fixture().use { f ->
            f.setBatch(saveAll)
            f.model.addDownload(0, 1)
            val cancelled = CoroutineScope(Job().apply { cancel() } + Dispatchers.IO)
            f.model.download(cancelled, Dispatchers.IO)
            assertEquals(0, f.model.onDownloadCount)
            assertEquals(2, f.model.waitCount)
            assertNull(BookHelp.getContent(f.book, f.chapters[0]))
        }
    }

    @Test
    fun manualCacheDownloadsImagesFromAlreadyAvailableReaderText() = runBlocking {
        withTimeout(10000) {
            Fixture().use { f ->
                val content = "<img src=\"${f.server.url}/image.png\">"
                BookHelp.saveText(f.book, f.chapters[0], content)
                assertFalse(BookHelp.hasImageContent(f.book, f.chapters[0]))
                f.model.addDownload(0, 0)
                coroutineScope { f.model.download(this, Dispatchers.IO) }
                assertTrue(BookHelp.hasImageContent(f.book, f.chapters[0]))
                assertEquals(content, BookHelp.getContent(f.book, f.chapters[0]))
                assertEquals(1, f.server.imageRequests.get())
                assertEquals(0, f.server.singleRequests.get())
            }
        }
    }

    @Test
    fun contentArrivingBeforeClaimIsRecheckedWithoutAnotherNetworkRequest() = runBlocking {
        withTimeout(10000) {
            Fixture().use { f ->
                // Initialize folder/cache lookup before observing the registry monitor below.
                assertNull(BookHelp.getContent(f.book, f.chapters[0]))
                val result = CompletableDeferred<String>()
                val reader = thread(start = false, isDaemon = true, name = "batch-claim-reader") {
                    runCatching { runBlocking { f.model.downloadAwait(f.chapters[0]) } }
                        .onSuccess { result.complete(it) }
                        .onFailure { result.completeExceptionally(it) }
                }
                synchronized(CacheBook) {
                    reader.start()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (reader.state != Thread.State.BLOCKED && reader.isAlive && System.nanoTime() < deadline) {
                        Thread.sleep(1)
                    }
                    assertEquals("reader did not reach the registry claim", Thread.State.BLOCKED, reader.state)
                    // A normal network cache write keeps version zero; the WebBook edit fence
                    // alone cannot avoid the redundant request in this interval.
                    assertTrue(BookHelp.saveContent(f.source, f.book, f.chapters[0], "arrived before claim"))
                }
                assertEquals("arrived before claim", result.await())
                assertEquals(0, f.server.singleRequests.get())
                assertEquals(0, f.model.onDownloadCount)
            }
        }
    }

    @Test
    fun callbacksAfterBatchClosureAreRejected() {
        Fixture().use { f ->
            val context = BatchContentContext(f.source, f.book, f.chapters,
                saveTokens = f.chapters.associate { it.index to BookHelp.contentSaveToken(f.book, it) })
            context.close()
            assertFalse(context.saveContent(f.chapters[0], "late callback"))
            assertNull(BookHelp.getContent(f.book, f.chapters[0]))
        }
    }

    private class Fixture : Closeable {
        val server = ChapterServer()
        private val id = UUID.randomUUID().toString()
        val source = BookSource(bookSourceUrl = server.url, bookSourceName = "batch test",
            ruleContent = ContentRule(content = "body@text", maxBatchSize = 4))
        val book = Book(bookUrl = "${server.url}/book/$id", tocUrl = "${server.url}/toc/$id",
            name = "batch-$id", origin = source.bookSourceUrl)
        val chapters = (0..1).map {
            BookChapter(bookUrl = book.bookUrl, url = "/chapter/$it", baseUrl = server.url,
                title = "chapter $it", index = it)
        }
        val model: CacheBook.CacheBookModel
        init {
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
            model = CacheBook.getOrCreate(source, book)
        }
        fun setBatch(script: String, javascriptSource: Boolean = false) {
            source.ruleContent!!.contentBatch = script
            source.mainJs = if (javascriptSource) "function getContentBatch(chapters, book) {$script}" else null
        }
        override fun close() {
            server.close()
            model.stop()
            CacheBook.cacheBookMap.remove(book.bookUrl, model)
            BookHelp.clearCache(book)
            appDb.bookDao.delete(book)
        }
    }

    private class ChapterServer : Closeable {
        private val socket = ServerSocket(0)
        val url = "http://127.0.0.1:${socket.localPort}"
        private val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val batchRequests = AtomicInteger()
        val singleRequests = AtomicInteger()
        val imageRequests = AtomicInteger()
        init {
            thread(isDaemon = true, name = "batch-test-server") {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) {
                        client.use {
                            val reader = it.getInputStream().bufferedReader()
                            val request = reader.readLine().orEmpty()
                            while (!reader.readLine().isNullOrEmpty()) { /* HTTP headers */ }
                            val image = request.contains(" /image.png ")
                            if (request.contains(" /batch ")) {
                                batchRequests.incrementAndGet()
                                entered.countDown()
                                release.await(10, TimeUnit.SECONDS)
                            } else if (image) imageRequests.incrementAndGet()
                            else singleRequests.incrementAndGet()
                            val body = if (image) Base64.decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==",
                                Base64.DEFAULT,
                            ) else "<html><body>single chapter content</body></html>".toByteArray()
                            val contentType = if (image) "image/png" else "text/html; charset=utf-8"
                            runCatching {
                                it.getOutputStream().apply {
                                    write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                    write(body)
                                    flush()
                                }
                            }
                        }
                    }
                }
            }
        }
        suspend fun awaitRequest() = withContext(Dispatchers.IO) {
            assertTrue("batch request did not reach server", entered.await(5, TimeUnit.SECONDS))
        }
        override fun close() {
            release.countDown()
            socket.close()
        }
    }
}
