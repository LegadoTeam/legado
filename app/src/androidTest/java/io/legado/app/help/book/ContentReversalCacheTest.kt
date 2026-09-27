package io.legado.app.help.book

import android.graphics.Bitmap
import android.graphics.Color
import android.app.Application
import android.util.Size
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.getFolderName
import io.legado.app.model.ImageProvider
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.book.read.ReadBookViewModel
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.main.TocUpdatePolicy
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ContentReversalCacheTest {
    @Test fun acceptedResourceGenerationsSurviveBookChangesAndRespectShelfScope() = withChapter { first, _ ->
        withChapter { second, _ -> runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val preferences = context.defaultSharedPreferences
            val oldCronet = preferences.all[PreferKey.cronet] as Boolean?
            val oldPreload = preferences.all[PreferKey.preDownloadNum] as Int?
            val savedBook = ReadBook.book
            val originalColor = ReadBookConfig.textColor
            val config = ReadBookConfig.durConfig
            val version = AtomicInteger(1)
            val imageRequests = AtomicInteger()
            val bodies = AtomicIntegerArray(10)
            val tocRequests = AtomicInteger()
            val failBody = AtomicInteger(-1)
            val failImage = AtomicInteger()
            val gate = AtomicReference<Pair<CountDownLatch, CountDownLatch>?>(null)
            var base = ""
            fun png(): ByteArray {
                val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
                return try {
                    bitmap.eraseColor(if (version.get() == 1) Color.RED else Color.GREEN)
                    ByteArrayOutputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray()
                    }
                } finally { bitmap.recycle() }
            }
            val server = object : NanoHTTPD("127.0.0.1", 0) {
                override fun serve(session: IHTTPSession): Response {
                    val path = session.uri.trim('/').split('/')
                    val response = when (path.first()) {
                        "toc" -> {
                            tocRequests.incrementAndGet()
                            val id = path.last().toInt()
                            newFixedLengthResponse(Response.Status.OK, "text/html", (0..4).joinToString("") {
                                "<a href='$base/book/$id/$it'>Chapter $it</a>"
                            })
                        }
                        "book" -> {
                            val key = path[1].toInt() * 5 + path[2].toInt()
                            bodies.incrementAndGet(key)
                            val body = "<img src=\"$base/shared.png\">\nchapter=$key version=${version.get()}"
                            gate.getAndSet(null)?.let { (entered, release) ->
                                entered.countDown(); check(release.await(15, TimeUnit.SECONDS))
                            }
                            if (failBody.get() == key) newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "")
                            else newFixedLengthResponse(Response.Status.OK, "text/plain", body)
                        }
                        else -> {
                            imageRequests.incrementAndGet()
                            val bytes = if (failImage.get() != 0) byteArrayOf(1, 2, 3) else png()
                            newFixedLengthResponse(Response.Status.OK, "image/png", bytes.inputStream(), bytes.size.toLong())
                        }
                    }
                    return response.apply { addHeader("Cache-Control", "no-store") }
                }
            }
            val store = ViewModelStore()
            server.start()
            base = "http://127.0.0.1:${server.listeningPort}"
            val source = BookSource(bookSourceUrl = "$base/source", bookSourceName = "Resource generation fixture")
            source.getTocRule().apply { chapterList = "a"; chapterName = "text"; chapterUrl = "href" }
            source.getContentRule().content = """
                @js:
                if (result.indexOf('version=') < 0) throw new Error('Missing fixture body');
                chapter.putVariable('acceptedVersion', result.substring(result.lastIndexOf('version=')));
                result + '\nbook=' + book.name
            """.trimIndent()
            val books = listOf(first, second)
            val chapters = books.mapIndexed { id, book ->
                book.origin = source.bookSourceUrl
                book.tocUrl = "$base/toc/$id"
                book.totalChapterNum = 5
                book.durChapterIndex = 1
                book.durChapterPos = 17
                book.canUpdate = true
                book.setUseReplaceRule(false)
                book.setReSegment(false)
                appDb.bookDao.insert(book)
                appDb.bookChapterDao.delByBook(book.bookUrl)
                (0..4).map { index -> BookChapter(bookUrl = book.bookUrl,
                    url = "$base/book/$id/$index", title = "Chapter $index", index = index, baseUrl = base) }
                    .also { appDb.bookChapterDao.insert(*it.toTypedArray()) }
            }
            try {
                preferences.edit().putBoolean(PreferKey.cronet, false).commit()
                appDb.bookSourceDao.insert(source)
                withContext(Main) { ReadBook.book = Book(bookUrl = "$base/foreground", name = "Unrelated foreground") }
                books.forEachIndexed { id, book -> refreshBookResources(source, book, chapters[id]) }
                assertTrue(books.flatMapIndexed { id, book -> chapters[id].map { BookHelp.resourcesOutdated(book, it) } }.none { it })
                val red = BookHelp.getImage(second, "$base/shared.png").readBytes()
                val generation = ResourceThemeGeneration.current()
                withContext(Main) {
                    config.setCurTextColor(originalColor xor 0x00010101)
                    config.setCurTextColor(originalColor)
                }
                assertTrue("A -> B -> A advances provenance", ResourceThemeGeneration.current() > generation)
                assertTrue(ReadBookViewModel(context.applicationContext as Application).resourceThemeChanged(first))
                assertTrue("A new reader model still sees another book's pending resources",
                    ReadBookViewModel(context.applicationContext as Application).resourceThemeChanged(second))
                version.set(2)
                repeat(10) { bodies.set(it, 0) }; imageRequests.set(0)
                val shelf = withContext(Main) { MainViewModel(context.applicationContext as Application).also { store.put("shelf", it) } }
                val jobField = MainViewModel::class.java.getDeclaredField("upTocJob").apply { isAccessible = true }
                suspend fun refreshShelf(book: Book, preload: Int, policy: TocUpdatePolicy) {
                    preferences.edit().putInt(PreferKey.preDownloadNum, preload).commit()
                    val before = tocRequests.get()
                    shelf.upToc(listOf(book), false, policy)
                    val completed = withTimeoutOrNull(30_000) {
                        while (tocRequests.get() == before || shelf.isUpdate(book.bookUrl) || jobField.get(shelf) != null ||
                            CacheBook.cacheBookMap[book.bookUrl]?.isRun() == true) delay(20)
                        true
                    }
                    assertTrue("Shelf ${book.name}, preload=$preload policy=$policy: " +
                        "toc=${tocRequests.get() - before}, updating=${shelf.isUpdate(book.bookUrl)}, " +
                        "tocJob=${jobField.get(shelf)}, cache=${CacheBook.cacheBookMap[book.bookUrl]?.isRun()}, " +
                        "bodies=${(0..9).map { bodies.get(it) }}", completed == true)
                }
                refreshShelf(first, 0, TocUpdatePolicy.ALLOW_PRE_DOWNLOAD)
                refreshShelf(first, 2, TocUpdatePolicy.SKIP_PRE_DOWNLOAD)
                assertEquals("Zero preload and skip policy must not fetch any body", 0, (0..9).sumOf { bodies.get(it) })
                assertEquals(0, imageRequests.get())
                // Ordinary reading stays cache-backed outside a shelf refresh request.
                CacheBook.getOrCreate(source, second).downloadAwait(chapters[1][0])
                assertEquals(0, bodies.get(5))
                refreshShelf(first, 2, TocUpdatePolicy.ALLOW_PRE_DOWNLOAD)
                assertEquals(listOf(0, 1, 1, 1, 0), (0..4).map { bodies.get(it) })
                assertArrayEquals("Refreshing book A must retain book B's image bytes", red,
                    BookHelp.getImage(second, "$base/shared.png").readBytes())
                assertTrue(ReadBookViewModel(context.applicationContext as Application).resourceThemeChanged(second))
                refreshShelf(second, 2, TocUpdatePolicy.ALLOW_PRE_DOWNLOAD)
                assertEquals(listOf(0, 1, 1, 1, 0), (5..9).map { bodies.get(it) })
                books.forEachIndexed { id, book ->
                    assertEquals("Shelf refresh retains the stored position", 17, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
                    for (index in 0..4) {
                        assertEquals(index !in 1..3, BookHelp.resourcesOutdated(book, chapters[id][index]))
                        val text = BookHelp.getContent(book, chapters[id][index])!!
                        assertTrue("Background scripts must use their own book", text.contains("book=${book.name}"))
                        assertTrue(text.contains(if (index in 1..3) "version=2" else "version=1"))
                    }
                    assertEquals(Color.GREEN, ImageProvider.getImage(book, "$base/shared.png", 4).getPixel(0, 0))
                }
                val chapter = chapters[1][2]
                withContext(Main) { config.setCurTextColor(originalColor xor 0x00020202) }
                val oldContent = BookHelp.getContent(second, chapter)
                val oldImage = BookHelp.getImage(second, "$base/shared.png").readBytes()
                val oldMetadata = appDb.bookChapterDao.getChapter(second.bookUrl, 2)!!
                for (failure in listOf("body", "image", "changed again")) {
                    failBody.set(if (failure == "body") 7 else -1)
                    failImage.set(if (failure == "image") 1 else 0)
                    val requested = ResourceThemeGeneration.current()
                    val result = if (failure == "changed again") {
                        val entered = CountDownLatch(1); val release = CountDownLatch(1)
                        gate.set(entered to release)
                        val refresh = async(IO) {
                            runCatching { refreshBookResources(source, second, listOf(chapter), generation = requested) }
                        }
                        try {
                            assertTrue("The obsolete request must already be in flight", entered.await(5, TimeUnit.SECONDS))
                            withContext(Main) {
                                config.setCurTextColor(originalColor)
                                config.setCurTextColor(originalColor xor 0x00020202)
                            }
                        } finally { release.countDown() }
                        refresh.await()
                    } else {
                        runCatching { refreshBookResources(source, second, listOf(chapter), generation = requested) }
                    }
                    assertTrue("Failed $failure refresh cannot publish", result.isFailure)
                    assertEquals(oldContent, BookHelp.getContent(second, chapter))
                    assertArrayEquals(oldImage, BookHelp.getImage(second, "$base/shared.png").readBytes())
                    val metadata = appDb.bookChapterDao.getChapter(second.bookUrl, 2)!!
                    assertEquals(Triple(oldMetadata.title, oldMetadata.imgUrl, oldMetadata.variable),
                        Triple(metadata.title, metadata.imgUrl, metadata.variable))
                    assertTrue(BookHelp.resourcesOutdated(second, chapter))
                }
                failBody.set(7); failImage.set(0)
                val failedEntered = CountDownLatch(1); val failedRelease = CountDownLatch(1)
                gate.set(failedEntered to failedRelease)
                val failedCache = CacheBook.getOrCreate(source, second)
                failedCache.addDownload(2, 2, refreshResources = true)
                CacheBook.errorDownloadMap[chapter.primaryStr()] = 2
                val failedOwner = async(IO) { failedCache.downloadAwait(chapter) }
                try {
                    assertTrue(failedEntered.await(5, TimeUnit.SECONDS))
                    // Start until the shared ticket suspension, so both readers receive the
                    // final failed attempt after its resource-refresh intent is removed.
                    val failedWaiter = async(IO, start = CoroutineStart.UNDISPATCHED) { failedCache.downloadAwait(chapter) }
                    failedRelease.countDown()
                    assertEquals(oldContent, failedOwner.await())
                    assertEquals("A waiting reader must retain the old body on the final failure", oldContent, failedWaiter.await())
                    assertArrayEquals(oldImage, BookHelp.getImage(second, "$base/shared.png").readBytes())
                    assertTrue(BookHelp.resourcesOutdated(second, chapter))
                } finally {
                    failedRelease.countDown()
                    CacheBook.errorDownloadMap.remove(chapter.primaryStr())
                }
                failBody.set(-1)
                val entered = CountDownLatch(1); val release = CountDownLatch(1)
                gate.set(entered to release)
                val cache = CacheBook.getOrCreate(source, second)
                cache.addDownload(2, 2, refreshResources = true)
                val beforeBodies = bodies.get(7); val beforeImages = imageRequests.get()
                val owner = async(IO) { cache.downloadAwait(chapter) }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val waiter = async(IO, start = CoroutineStart.UNDISPATCHED) { cache.downloadAwait(chapter) }
                    release.countDown()
                    assertEquals(owner.await(), waiter.await())
                    assertEquals(beforeBodies + 1, bodies.get(7))
                    assertEquals(beforeImages + 1, imageRequests.get())
                    assertFalse(BookHelp.resourcesOutdated(second, chapter))
                } finally { release.countDown() }
                withContext(Main) { config.setCurTextColor(originalColor) }
                val pendingCache = CacheBook.getOrCreate(source, second)
                pendingCache.addDownload(2, 2)
                val oldTicket = pendingCache.downloads.claimRead(2).first
                val oldToken = BookHelp.contentSaveToken(second, chapter)
                val publishedAfterCancellation = AtomicBoolean()
                val cancelled = async(IO) {
                    val requestJob = currentCoroutineContext().job
                    refreshBookResources(source, second, listOf(chapter), onPublished = { accepted ->
                        assertTrue(BookHelp.isContentSaveCurrent(accepted.single().token))
                        // Cancel immediately after the real disk commit. Publication below must
                        // still execute in this Main turn, before returning to the cancelled IO caller.
                        requestJob.cancel()
                        CacheBook.invalidateChapters(second.bookUrl, listOf(2))
                        publishedAfterCancellation.set(true)
                    })
                }
                assertTrue(runCatching { cancelled.await() }.exceptionOrNull() is CancellationException)
                assertTrue(publishedAfterCancellation.get())
                assertEquals(null, oldTicket.result.await())
                assertFalse(BookHelp.resourcesOutdated(second, chapter))
                assertFalse(BookHelp.saveContent(source, second, chapter, "late old body", oldToken))
            } finally {
                gate.getAndSet(null)?.second?.countDown()
                withContext(Main) { store.clear(); ReadBook.book = savedBook; config.setCurTextColor(originalColor) }
                books.forEach { CacheBook.cacheBookMap.remove(it.bookUrl)?.stop() }
                server.stop()
                appDb.bookSourceDao.delete(source)
                preferences.edit().apply {
                    if (oldCronet == null) remove(PreferKey.cronet) else putBoolean(PreferKey.cronet, oldCronet)
                    if (oldPreload == null) remove(PreferKey.preDownloadNum) else putInt(PreferKey.preDownloadNum, oldPreload)
                }.commit()
            }
        } }
    }

    @Test fun imageRefreshDropsOldPixelsDimensionsAndAnInFlightResponse() = withChapter { book, chapter ->
        runBlocking {
            fun png(width: Int, height: Int, color: Int): ByteArray {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                return try {
                    bitmap.eraseColor(color)
                    ByteArrayOutputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        it.toByteArray()
                    }
                } finally { bitmap.recycle() }
            }
            val oldBytes = png(4, 2, Color.RED)
            val freshBytes = png(8, 3, Color.GREEN)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val requests = AtomicInteger()
            val server = object : NanoHTTPD("127.0.0.1", 0) {
                override fun serve(session: IHTTPSession): Response {
                    val first = requests.incrementAndGet() == 1
                    if (first) {
                        entered.countDown()
                        check(release.await(8, TimeUnit.SECONDS))
                    }
                    val bytes = if (first) oldBytes else freshBytes
                    return newFixedLengthResponse(Response.Status.OK, "image/png",
                        ByteArrayInputStream(bytes), bytes.size.toLong())
                        .apply { addHeader("Cache-Control", "no-store") }
                }
            }
            val preferences = InstrumentationRegistry.getInstrumentation().targetContext.defaultSharedPreferences
            val previousCronet = preferences.all[PreferKey.cronet] as Boolean?
            server.start()
            val src = "http://127.0.0.1:${server.listeningPort}/bubble.png"
            try {
                preferences.edit().putBoolean(PreferKey.cronet, false).commit()
                val file = BookHelp.getImage(book, src)
                BookHelp.writeImage(book, src, oldBytes)
                assertEquals(Size(4, 2), BitmapUtils.getImageSize(file.absolutePath))
                val oldBitmap = ImageProvider.getImage(book, src, 4)
                assertEquals(Color.RED, oldBitmap.getPixel(0, 0))
                ImageProvider.clearImage(book, src)
                assertFalse(file.exists())
                assertTrue(oldBitmap.isRecycled)
                assertEquals(null, ImageProvider.get(file.absolutePath))

                val oldResponse = async(IO) { BookHelp.saveImage(BookSource(), book, src, chapter) }
                assertTrue("The old image request must be in flight", entered.await(5, TimeUnit.SECONDS))
                ImageProvider.clearImage(book, src)
                release.countDown()
                oldResponse.await()
                assertFalse("An old response must not repopulate the deleted image", file.exists())

                BookHelp.saveImage(BookSource(), book, src, chapter)
                assertEquals(2, requests.get())
                assertEquals(Size(8, 3), BitmapUtils.getImageSize(file.absolutePath))
                assertEquals(Color.GREEN, ImageProvider.getImage(book, src, 8).getPixel(0, 0))
            } finally {
                release.countDown()
                server.stop()
                ImageProvider.clearImage(book, src)
                preferences.edit().apply {
                    if (previousCronet == null) remove(PreferKey.cronet) else putBoolean(PreferKey.cronet, previousCronet)
                }.commit()
            }
        }
    }

    @Test fun refreshRejectsAnOlderResponseAndItsChapterMetadata() = withChapter { book, chapter ->
        BookHelp.saveText(book, chapter, "old chapter")
        assertTrue(BookHelp.reverseContent(book, chapter))
        val pendingResponse = BookHelp.contentSaveToken(book, chapter)
        BookHelp.delContent(book, chapter)
        assertFalse(BookHelp.hasContent(book, chapter))
        assertFalse(BookHelp.isContentReversed(book, chapter))

        assertFalse(BookHelp.saveContent(BookSource(), book, chapter.copy(title = "Outdated title"),
            "old response", pendingResponse, saveChapterMetadata = true))
        assertFalse("The old response must not restore the deleted cache", BookHelp.hasContent(book, chapter))
        assertEquals(chapter.title, appDb.bookChapterDao.getChapter(book.bookUrl, chapter.index)?.title)

        assertTrue(BookHelp.saveContent(BookSource(), book, chapter, "fresh response"))
        assertFalse(BookHelp.saveContent(BookSource(), book, chapter, "late response", pendingResponse))
        assertEquals("fresh response", BookHelp.getContent(book, chapter))
    }

    @Test fun reverseRestoreAndFreshDownloadUseTheActualCacheContents() = withChapter { book, chapter ->
        // Reversing this plain text creates a new entity. Re-parsing it on undo
        // would no longer invert the first operation; restore the original bytes.
        val content = ";pma&😀甲"
        BookHelp.saveText(book, chapter, content)
        assertTrue(BookHelp.reverseContent(book, chapter))
        assertEquals("甲😀&amp;", BookHelp.getContent(book, chapter))
        assertTrue(BookHelp.isContentReversed(book, chapter))
        assertTrue(BookHelp.reverseContent(book, chapter))
        assertEquals(content, BookHelp.getContent(book, chapter))
        assertFalse(BookHelp.isContentReversed(book, chapter))
        assertTrue(BookHelp.reverseContent(book, chapter))
        assertTrue(BookHelp.saveContent(BookSource(), book, chapter, "downloaded again"))
        assertEquals("downloaded again", BookHelp.getContent(book, chapter))
        assertFalse(BookHelp.isContentReversed(book, chapter))
        BookHelp.delContent(book, chapter)
        val pendingDownload = BookHelp.contentSaveToken(book, chapter)
        assertFalse(BookHelp.reverseContent(book, chapter))
        assertFalse(BookHelp.isContentReversed(book, chapter))
        assertTrue("A no-op must not invalidate the pending download",
            BookHelp.saveContent(BookSource(), book, chapter, "pending content", pendingDownload))
    }

    @Test fun failedWriteDoesNotToggleStateOrReplaceTheOriginalCache() = withChapter { book, chapter ->
        val original = "甲乙😀"
        BookHelp.saveText(book, chapter, original)
        val file = File(BookHelp.cachePath, "${book.getFolderName()}/${chapter.getFileName()}")
        val marker = File(file.path + ".reversed")
        assertTrue(marker.mkdir())
        try {
            assertTrue("Failed undo-data writes must leave the content unchanged", runCatching {
                BookHelp.reverseContent(book, chapter)
            }.isFailure)
            assertEquals(original, BookHelp.getContent(book, chapter))
            assertFalse(BookHelp.isContentReversed(book, chapter))
        } finally { assertTrue(marker.delete()) }
        for (checked in listOf(false, true)) {
            if (checked) assertTrue(BookHelp.reverseContent(book, chapter))
            val content = BookHelp.getContent(book, chapter)
            // Emulated external storage ignores chmod; obstruct AtomicFile's
            // staging path to produce a real, deterministic filesystem error.
            val staging = File(file.path + ".new")
            assertTrue(staging.mkdir())
            try {
                assertTrue("An obstructed atomic cache write must fail", runCatching {
                    BookHelp.reverseContent(book, chapter)
                }.isFailure)
                assertEquals(content, BookHelp.getContent(book, chapter))
                assertEquals(checked, BookHelp.isContentReversed(book, chapter))
            } finally {
                if (staging.exists()) assertTrue(staging.delete())
            }
        }
    }

    private fun withChapter(test: (Book, BookChapter) -> Unit) {
        val id = UUID.randomUUID().toString()
        val book = Book(bookUrl = "https://example.invalid/reversal-cache/$id", name = id)
        val chapter = BookChapter(bookUrl = book.bookUrl, url = "${book.bookUrl}/1", title = "Chapter")
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(chapter)
        try { test(book, chapter) } finally {
            BookHelp.clearCache(book)
            appDb.bookDao.delete(book)
        }
    }
}
