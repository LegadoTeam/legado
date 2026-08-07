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
    fun `existing audio screen is reused for notification and task entry`() {
        assertTrue(shouldReuseCurrentAudioPlay(null, "book-a"))
        assertTrue(shouldReuseCurrentAudioPlay("book-a", "book-a"))
        assertFalse(shouldReuseCurrentAudioPlay("book-b", "book-a"))
        assertFalse(shouldReuseCurrentAudioPlay("book-a", null))
    }

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
        val beforeInit = onNewIntent.substringBefore("viewModel.initData(")

        assertTrue(onNewIntent.contains("setIntent(intent)"))
        assertTrue(beforeInit.contains("shouldReuseCurrentAudioPlay("))
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
        assertTrue(viewModel.contains("val resolvedBook = resolveAudioPlayBook("))
        assertFalse(viewModel.contains("cachedChapterIndex"))
        assertFalse(viewModel.contains("cachedChapterPos"))
        assertTrue(viewModel.contains("val temporaryBook = targetBook.copy().apply"))
        assertTrue(viewModel.contains("appDb.bookDao.insertIgnore(temporaryBook)"))
        assertTrue(viewModel.contains("val concurrentBook = appDb.bookDao.getBook(requestedBookUrl)"))
        assertTrue(viewModel.contains("databaseBook = concurrentBook"))
        assertTrue(viewModel.contains("else -> !(databaseBook ?: targetBook).isNotShelf"))

        val audioPlay = projectFile(
            "src/main/java/io/legado/app/model/AudioPlay.kt"
        ).readText()
        val upData = audioPlay.substringAfter("fun upData(book: Book, preserveProgress: Boolean)")
            .substringBefore("fun resetData(book: Book)")
        assertTrue(upData.contains("val playbackChanged = synchronized(this)"))
        assertTrue(upData.contains("if (preserveProgress &&"))
        assertTrue(upData.contains("book.durChapterIndex = durChapterIndex"))
        assertTrue(upData.contains("book.durChapterPos = durChapterPos"))
        assertTrue(upData.contains("AudioPlay.book = book"))
        assertTrue(viewModel.contains("AudioPlay.upData(book, preserveProgress = true)"))
        assertTrue(viewModel.contains("AudioPlay.upData(book, preserveProgress = false)"))
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
