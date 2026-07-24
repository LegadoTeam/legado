package io.legado.app.ui.book.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioPlayBookResolverTest {

    @Test
    fun `requested book is loaded instead of another cached book`() {
        val cachedBook = TestBook("book-b")
        val databaseBook = TestBook("book-a")

        val result = resolveAudioPlayBook(
            requestedBookUrl = "book-a",
            cachedBook = cachedBook,
            bookUrlOf = TestBook::bookUrl,
            findBook = { databaseBook },
        )

        assertSame(databaseBook, result)
    }

    @Test
    fun `matching cached book avoids database lookup`() {
        val cachedBook = TestBook("book-a")
        var databaseLookupCount = 0

        val result = resolveAudioPlayBook(
            requestedBookUrl = "book-a",
            cachedBook = cachedBook,
            bookUrlOf = TestBook::bookUrl,
            findBook = {
                databaseLookupCount++
                TestBook("book-a")
            },
        )

        assertSame(cachedBook, result)
        assertEquals(0, databaseLookupCount)
    }

    @Test
    fun `notification restore without extras uses current cached book`() {
        val cachedBook = TestBook("book-a")

        val result = resolveAudioPlayBook(
            requestedBookUrl = null,
            cachedBook = cachedBook,
            bookUrlOf = TestBook::bookUrl,
            findBook = { error("database lookup should not run") },
        )

        assertSame(cachedBook, result)
    }

    @Test
    fun `missing requested book never falls back to another cached book`() {
        val result = resolveAudioPlayBook(
            requestedBookUrl = "book-a",
            cachedBook = TestBook("book-b"),
            bookUrlOf = TestBook::bookUrl,
            findBook = { null },
        )

        assertNull(result)
    }

    @Test
    fun `audio notifications carry book identity`() {
        val playService = projectFile(
            "src/main/java/io/legado/app/service/AudioPlayService.kt"
        ).readText()
        val cacheService = projectFile(
            "src/main/java/io/legado/app/service/AudioCacheService.kt"
        ).readText()

        assertTrue(playService.contains("putExtra(\"bookUrl\", it.bookUrl)"))
        assertFalse(playService.contains("putExtra(\"inBookshelf\""))
        assertTrue(cacheService.contains("putExtra(\"bookUrl\", bookUrl)"))
        assertTrue(cacheService.contains("notificationBuilder.setContentIntent(contentIntent)"))
        assertTrue(cacheService.contains("currentBookUrl.takeIf { it.isNotBlank() }"))
    }

    @Test
    fun `audio activity consumes notification updates`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/audio/AudioPlayActivity.kt"
        ).readText()
        val onNewIntent = activity.substringAfter("override fun onNewIntent(intent: Intent)")
            .substringBefore("override fun onCompatCreateOptionsMenu")

        assertTrue(onNewIntent.contains("setIntent(intent)"))
        assertTrue(onNewIntent.contains("viewModel.initData("))
        assertTrue(onNewIntent.contains("intent = intent"))
    }

    @Test
    fun `audio initialization is serialized and refreshes shelf state`() {
        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/book/audio/AudioPlayViewModel.kt"
        ).readText()

        assertTrue(viewModel.contains("private val initSemaphore = Semaphore(1)"))
        assertTrue(viewModel.contains("initTask?.cancel()"))
        assertTrue(viewModel.contains("execute(semaphore = initSemaphore)"))
        assertTrue(viewModel.contains("cachedBook = cachedBook"))
        assertFalse(viewModel.contains("cachedBook.takeUnless"))
        assertFalse(viewModel.contains("getBooleanExtra(\"inBookshelf\""))
        assertTrue(viewModel.contains("durChapterIndex = cachedChapterIndex"))
        assertTrue(viewModel.contains("durChapterPos = cachedChapterPos"))
        assertTrue(viewModel.contains("val temporaryBook = targetBook.copy().apply"))
        assertTrue(viewModel.contains("appDb.bookDao.insertIgnore(temporaryBook)"))
        assertTrue(viewModel.contains("val concurrentBook = appDb.bookDao.getBook(requestedBookUrl)"))
        assertTrue(viewModel.contains("databaseBook = concurrentBook"))
        assertTrue(viewModel.contains("else -> !(databaseBook ?: targetBook).isNotShelf"))
    }

    @Test
    fun `source change refreshes the running notification`() {
        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/book/audio/AudioPlayViewModel.kt"
        ).readText()
        val service = projectFile(
            "src/main/java/io/legado/app/service/AudioPlayService.kt"
        ).readText()
        val updateAction = service.substringAfter("ACTION_UPDATE_NOTIFICATION ->")
            .substringBefore("IntentAction.stop ->")

        assertTrue(viewModel.contains("AudioPlayService.updateNotification(context)"))
        assertTrue(updateAction.contains("upMediaMetadata()"))
        assertTrue(updateAction.contains("upAudioPlayNotification()"))
        assertTrue(viewModel.contains("appDb.bookDao.getBook(it.bookUrl)?.isNotShelf ?: true"))
        assertTrue(viewModel.contains("if (wasNotShelf) book.addType(BookType.notShelf)"))
        assertTrue(viewModel.contains("AudioPlay.inBookshelf = !wasNotShelf"))
    }

    @Test
    fun `book loading failure is propagated`() {
        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/book/audio/AudioPlayViewModel.kt"
        ).readText()

        assertTrue(viewModel.contains("private suspend fun initBook(book: Book): Boolean"))
        assertTrue(
            viewModel.contains(
                "if (AudioPlay.chapterSize == 0 && book.tocUrl.isEmpty() && !loadBookInfo(book))"
            )
        )
        assertTrue(viewModel.contains("if (AudioPlay.chapterSize == 0 && !loadChapterList(book))"))
        assertTrue(viewModel.contains("if (cList.isEmpty()) return false"))
        assertTrue(viewModel.contains("return false"))
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
    }

    private data class TestBook(val bookUrl: String)
}
