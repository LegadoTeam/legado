package io.legado.app.ui.book.audio

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.addType
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.removeType
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.AudioPlay
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.AudioPlayService
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.sync.Semaphore

class AudioPlayViewModel(application: Application) : BaseViewModel(application) {
    val titleData = MutableLiveData<String>()
    val coverData = MutableLiveData<String>()
    val customBtnListData = MutableLiveData<Boolean>()
    private val initSemaphore = Semaphore(1)
    private var initTask: Coroutine<Boolean?>? = null

    fun initData(intent: Intent, success: () -> Unit, error: () -> Unit) {
        val requestedBookUrl = intent.getStringExtra("bookUrl")
        val cachedBook = AudioPlay.book
        val cachedInBookshelf = AudioPlay.inBookshelf
        initTask?.cancel()
        initTask = execute(semaphore = initSemaphore) {
            var databaseBook = requestedBookUrl
                ?.takeIf { it.isNotBlank() }
                ?.let(appDb.bookDao::getBook)
            val resolvedBook = resolveAudioPlayBook(
                requestedBookUrl = requestedBookUrl,
                cachedBook = cachedBook,
                bookUrlOf = Book::bookUrl,
                findBook = { databaseBook },
            ) ?: return@execute null
            var targetBook = databaseBook
                ?.takeIf { resolvedBook === cachedBook }
                ?: resolvedBook
            if (!requestedBookUrl.isNullOrBlank()
                && databaseBook == null
                && targetBook === cachedBook
            ) {
                val temporaryBook = targetBook.copy().apply {
                    addType(BookType.notShelf)
                }
                if (appDb.bookDao.insertIgnore(temporaryBook) == -1L) {
                    val concurrentBook = appDb.bookDao.getBook(requestedBookUrl)
                        ?: return@execute null
                    databaseBook = concurrentBook
                    targetBook = concurrentBook
                } else {
                    targetBook = temporaryBook
                }
            }
            AudioPlay.inBookshelf = when {
                requestedBookUrl.isNullOrBlank() -> cachedInBookshelf
                else -> !(databaseBook ?: targetBook).isNotShelf
            }
            initBook(targetBook)
        }.onSuccess { initialized ->
            when (initialized) {
                true -> {
                    success()
                    AudioPlay.saveRead(true)
                }

                false -> {
                    context.toastOnUi(R.string.error_load_toc)
                    error()
                }

                null -> {
                    context.toastOnUi(R.string.no_book)
                    AppLog.put("未找到音频书籍\nbookUrl:$requestedBookUrl")
                    error()
                }
            }
        }.onError {
            error()
            AppLog.put("音频播放初始化失败\n${it.localizedMessage}", it, true)
        }
    }

    private suspend fun initBook(book: Book): Boolean {
        val isSameBook = AudioPlay.book?.bookUrl == book.bookUrl
        if (isSameBook) {
            AudioPlay.upData(book, preserveProgress = true)
        } else {
            AudioPlay.resetData(book)
        }
        customBtnListData.postValue(AudioPlay.bookSource?.customButton == true)
        titleData.postValue(book.name)
        coverData.postValue(book.getDisplayCover())
        if (AudioPlay.chapterSize == 0 && book.tocUrl.isEmpty() && !loadBookInfo(book)) {
            return false
        }
        if (AudioPlay.chapterSize == 0 && !loadChapterList(book)) {
            return false
        }
        return AudioPlay.chapterSize > 0
    }

    private suspend fun loadBookInfo(book: Book): Boolean {
        val bookSource = AudioPlay.bookSource ?: return false
        try {
            WebBook.getBookInfoAwait(bookSource, book)
            return true
        } catch (e: Exception) {
            AppLog.put("详情页出错: ${e.localizedMessage}", e, true)
            return false
        }
    }

    private suspend fun loadChapterList(book: Book): Boolean {
        val bookSource = AudioPlay.bookSource ?: return false
        try {
            val oldBook = book.copy()
            val cList = WebBook.getChapterListAwait(bookSource, book).getOrThrow()
            if (cList.isEmpty()) return false
            if (oldBook.bookUrl == book.bookUrl) {
                book.update()
            } else {
                appDb.bookDao.replace(oldBook, book)
            }
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*cList.toTypedArray())
            AudioPlay.chapterSize = cList.size
            AudioPlay.simulatedChapterSize = book.simulatedTotalChapterNum()
            AudioPlay.upDurChapter()
            return true
        } catch (_: Exception) {
            return false
        }
    }

    fun upSource() {
        execute {
            val book = AudioPlay.book ?: return@execute
            val source = book.getBookSource()
            AudioPlay.setBookSource(source)
            customBtnListData.postValue(source?.customButton == true)
        }
    }

    fun changeTo(
        source: BookSource,
        book: Book,
        toc: List<BookChapter>,
        onSuccess: () -> Unit = {},
    ) {
        execute {
            val oldBook = AudioPlay.book
            val wasNotShelf = oldBook?.let {
                appDb.bookDao.getBook(it.bookUrl)?.isNotShelf ?: true
            } ?: !AudioPlay.inBookshelf
            oldBook?.migrateTo(book, toc)
            book.removeType(BookType.updateError)
            if (wasNotShelf) book.addType(BookType.notShelf)
            oldBook?.delete()
            appDb.bookDao.insert(book)
            AudioPlay.replaceBook(book)
            AudioPlay.inBookshelf = !wasNotShelf
            AudioPlay.setBookSource(source)
            appDb.bookChapterDao.insert(*toc.toTypedArray())
            AudioPlay.upData(book, preserveProgress = false)
            AudioPlayService.updateNotification(context)
        }.onSuccess {
            onSuccess()
        }.onFinally {
            postEvent(EventBus.SOURCE_CHANGED, book.bookUrl)
        }
    }

    fun removeFromBookshelf(success: (() -> Unit)?) {
        execute {
            AudioPlay.book?.let {
                appDb.bookDao.delete(it)
            }
        }.onSuccess {
            success?.invoke()
        }
    }

}
