package io.legado.app.help.book

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Size
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
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ContentReversalCacheTest {
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
