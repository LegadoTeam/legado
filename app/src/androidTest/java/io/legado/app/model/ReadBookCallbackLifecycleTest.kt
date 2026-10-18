package io.legado.app.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadBookCallbackLifecycleTest {

    @Test
    fun destroyingPreviousReaderDoesNotCancelCurrentReaderWork() = runBlocking {
        withContext(Dispatchers.Main) {
            val savedCallback = ReadBook.callBack
            val previousReader = NoOpCallback()
            val currentReader = NoOpCallback()
            val readyToRender = CompletableDeferred<Unit>()
            var rendered = false
            ReadBook.callBack = null
            ReadBook.register(previousReader)
            ReadBook.register(currentReader)
            val chapterJob = ReadBook.launch(start = CoroutineStart.UNDISPATCHED) {
                readyToRender.await()
                rendered = true
            }
            val downloadJob = ReadBook.downloadScope.launch(start = CoroutineStart.UNDISPATCHED) {
                awaitCancellation()
            }
            try {
                // A new activity has registered before the previous one's onDestroy arrives.
                assertFalse(ReadBook.unregister(previousReader))
                assertSame(currentReader, ReadBook.callBack)
                assertTrue(chapterJob.isActive)
                assertTrue(downloadJob.isActive)
                readyToRender.complete(Unit)
                chapterJob.join()
                assertFalse(chapterJob.isCancelled)
                assertTrue(rendered)

                // The current owner's destruction must still release its work.
                val pendingChapter = ReadBook.launch(start = CoroutineStart.UNDISPATCHED) {
                    awaitCancellation()
                }
                assertTrue(ReadBook.unregister(currentReader))
                assertNull(ReadBook.callBack)
                assertTrue(pendingChapter.isCancelled)
                assertTrue(downloadJob.isCancelled)
                pendingChapter.join()
                downloadJob.join()
                assertFalse(ReadBook.unregister(currentReader))
            } finally {
                chapterJob.cancel()
                downloadJob.cancel()
                ReadBook.callBack = savedCallback
            }
        }
    }

    private class NoOpCallback : ReadBook.CallBack {
        override fun upMenuView() = Unit
        override fun loadChapterList(book: Book) = Unit
        override fun upContent(
            relativePosition: Int,
            resetPageOffset: Boolean,
            readPositionVersion: Long?,
            success: (() -> Unit)?,
        ) = Unit
        override suspend fun upContentAwait(
            relativePosition: Int,
            resetPageOffset: Boolean,
            readPositionVersion: Long?,
            success: (() -> Unit)?,
        ) = Unit
        override fun pageChanged() = Unit
        override fun contentLoadFinish() = Unit
        override fun upPageAnim(upRecorder: Boolean) = Unit
        override fun notifyBookChanged() = Unit
        override fun sureNewProgress(progress: BookProgress) = Unit
        override fun cancelSelect() = Unit
    }
}
