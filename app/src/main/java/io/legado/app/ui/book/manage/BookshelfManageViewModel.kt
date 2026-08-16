package io.legado.app.ui.book.manage

import android.app.Application
import androidx.lifecycle.MutableLiveData
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.installPersistentCover
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.networkCoverForPersistence
import io.legado.app.help.book.networkCoverSourceOrigin
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.SourceCallBack
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.mergeFilteredOrder
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import java.io.File

internal data class PersistentCoverResult(
    val saved: Int,
    val skipped: Int,
    val failed: Int,
)

class BookshelfManageViewModel(application: Application) : BaseViewModel(application) {
    var groupId: Long = -1L
    var groupName: String? = null
    val batchChangeSourceState = MutableLiveData<Boolean>()
    val batchChangeSourceProcessLiveData = MutableLiveData<String>()
    var batchChangeSourceCoroutine: Coroutine<Unit>? = null
    val batchPersistCoverState = MutableLiveData<Boolean>()
    val batchPersistCoverProcess = MutableLiveData<String>()
    internal var batchPersistCoverCoroutine: Coroutine<*>? = null
    private val coverOperationSemaphore = Semaphore(1)
    private var coverOperationId = 0

    fun upCanUpdate(books: List<Book>, canUpdate: Boolean) {
        execute {
            val array = Array(books.size) {
                books[it].copy(canUpdate = canUpdate).apply {
                    if (!canUpdate) {
                        removeType(BookType.updateError)
                    }
                }
            }
            appDb.bookDao.updatePreservingCustomCoverUrl(*array)
        }
    }

    fun updateBook(vararg book: Book) {
        execute {
            appDb.bookDao.updatePreservingCustomCoverUrl(*book)
        }
    }

    fun updateBookOrder(books: List<Book>, resetAll: Boolean) {
        execute {
            if (resetAll) {
                appDb.runInTransaction {
                    val reordered = mergeFilteredOrder(
                        appDb.bookDao.allShelfByOrder,
                        books,
                    ) { it.bookUrl }
                    reordered.forEachIndexed { index, book -> book.order = index + 1 }
                    appDb.bookDao.updateOrder(reordered)
                }
            } else {
                appDb.bookDao.updateOrder(books)
            }
        }
    }

    fun deleteBook(books: List<Book>, deleteOriginal: Boolean = false) {
        execute {
            appDb.bookDao.delete(*books.toTypedArray())
            books.forEach {
                if (it.isLocal) {
                    LocalBook.deleteBook(it, deleteOriginal)
                } else {
                    val source = appDb.bookSourceDao.getBookSource(it.origin)
                    SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, source, it)
                }
            }
        }
    }

    fun saveAllUseBookSourceToFile(success: (file: File) -> Unit) {
        execute {
            val path = "${context.filesDir}/shareBookSource.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            val sources = appDb.bookDao.getAllUseBookSource()
            file.outputStream().buffered().use {
                GSON.writeToOutputStream(it, sources)
            }
            file
        }.onSuccess {
            success.invoke(it)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }

    fun changeSource(books: List<Book>, source: BookSource) {
        batchChangeSourceCoroutine?.cancel()
        batchChangeSourceCoroutine = execute {
            val changeSourceDelay = AppConfig.batchChangeSourceDelay * 1000L
            books.forEachIndexed { index, book ->
                batchChangeSourceProcessLiveData.postValue("${index + 1} / ${books.size}")
                if (book.isLocal) return@forEachIndexed
                if (book.origin == source.bookSourceUrl) return@forEachIndexed
                val newBook = WebBook.preciseSearchAwait(source, book.name, book.author)
                    .onFailure {
                        AppLog.put("搜索书籍出错\n${it.localizedMessage}", it, true)
                    }.getOrNull() ?: return@forEachIndexed
                kotlin.runCatching {
                    if (newBook.tocUrl.isEmpty()) {
                        WebBook.getBookInfoAwait(source, newBook)
                    }
                }.onFailure {
                    AppLog.put("获取书籍详情出错\n${it.localizedMessage}", it, true)
                    return@forEachIndexed
                }
                WebBook.getChapterListAwait(source, newBook)
                    .onFailure {
                        AppLog.put("获取目录出错\n${it.localizedMessage}", it, true)
                    }.getOrNull()?.let { toc ->
                        book.migrateTo(newBook, toc)
                        book.removeType(BookType.updateError)
                        appDb.bookDao.insert(newBook)
                        appDb.bookChapterDao.insert(*toc.toTypedArray())
                    }
                delay(changeSourceDelay)
            }
        }.onStart {
            batchChangeSourceState.postValue(true)
        }.onFinally {
            batchChangeSourceState.postValue(false)
        }
    }

    fun clearCache(books: List<Book>) {
        execute {
            books.forEach {
                BookHelp.clearCache(it)
            }
        }.onSuccess {
            context.toastOnUi(R.string.clear_cache_success)
        }
    }

    fun persistNetworkCovers(books: List<Book>) {
        val operationId = beginCoverOperation()
        batchPersistCoverCoroutine = execute(semaphore = coverOperationSemaphore) {
            var saved = 0
            var skipped = 0
            var failed = 0
            val coversDir = File(context.externalFiles, "covers")
            books.forEachIndexed { index, book ->
                currentCoroutineContext().ensureActive()
                val currentBook = appDb.bookDao.getBook(book.bookUrl)
                if (currentBook == null) {
                    skipped++
                    return@forEachIndexed
                }
                if (operationId == coverOperationId) {
                    batchPersistCoverProcess.postValue(
                        context.getString(R.string.persist_cover_progress, index + 1, books.size)
                    )
                }
                val coverUrl = currentBook.networkCoverForPersistence()
                if (coverUrl == null) {
                    skipped++
                    return@forEachIndexed
                }
                val expectedOrigin = currentBook.origin
                val expectedCoverUrl = currentBook.coverUrl
                val expectedCustomCoverUrl = currentBook.customCoverUrl
                val expectedPersistedCoverUrl = currentBook.persistedCoverUrl
                try {
                    var options = RequestOptions().set(
                        OkHttpModelLoader.loadOnlyWifiOption,
                        AppConfig.loadCoverOnlyWifi
                    )
                    currentBook.networkCoverSourceOrigin()?.let {
                        options = options.set(OkHttpModelLoader.sourceOriginOption, it)
                    }
                    val target = ImageLoader.loadFile(context, coverUrl)
                        .apply(options)
                        .submit()
                    try {
                        val downloaded = runInterruptible { target.get() }
                        currentCoroutineContext().ensureActive()
                        val validationTarget = Glide.with(context)
                            .load(downloaded)
                            .submit(1, 1)
                        try {
                            runInterruptible { validationTarget.get() }
                        } finally {
                            Glide.with(context).clear(validationTarget)
                        }
                        val persistent = installPersistentCover(downloaded, coversDir)
                        currentCoroutineContext().ensureActive()
                        if (
                            appDb.bookDao.updatePersistedCoverUrlIfUnchanged(
                                book.bookUrl,
                                expectedOrigin,
                                expectedCoverUrl,
                                expectedCustomCoverUrl,
                                expectedPersistedCoverUrl,
                                persistent.absolutePath
                            ) == 1
                        ) {
                            saved++
                        } else {
                            skipped++
                        }
                    } finally {
                        Glide.with(context).clear(target)
                    }
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    failed++
                    AppLog.put("保存封面失败: ${currentBook.name}\n${e.localizedMessage}", e)
                }
            }
            PersistentCoverResult(saved, skipped, failed)
        }.onStart {
            if (operationId == coverOperationId) {
                batchPersistCoverState.postValue(true)
            }
        }.onSuccess {
            context.toastOnUi(
                context.getString(
                    R.string.persist_cover_result,
                    it.saved,
                    it.skipped,
                    it.failed
                )
            )
        }.onFinally {
            if (operationId == coverOperationId) {
                batchPersistCoverState.postValue(false)
            }
        }
    }

    fun restoreNetworkCovers(books: List<Book>) {
        val operationId = beginCoverOperation()
        batchPersistCoverCoroutine = execute(semaphore = coverOperationSemaphore) {
            books.sumOf { book ->
                currentCoroutineContext().ensureActive()
                val currentBook = appDb.bookDao.getBook(book.bookUrl) ?: return@sumOf 0
                val expectedCoverUrl = currentBook.persistedCoverUrl ?: return@sumOf 0
                appDb.bookDao.clearPersistedCoverUrlIfUnchanged(
                    currentBook.bookUrl,
                    expectedCoverUrl,
                )
            }
        }.onSuccess {
            if (operationId == coverOperationId) {
                context.toastOnUi(context.getString(R.string.restore_network_cover_result, it))
            }
        }
    }

    fun restoreSourceCovers(books: List<Book>) {
        val operationId = beginCoverOperation()
        batchPersistCoverCoroutine = execute(semaphore = coverOperationSemaphore) {
            books.sumOf { book ->
                currentCoroutineContext().ensureActive()
                val currentBook = appDb.bookDao.getBook(book.bookUrl) ?: return@sumOf 0
                if (
                    currentBook.customCoverUrl.isNullOrEmpty() &&
                    currentBook.persistedCoverUrl.isNullOrEmpty()
                ) {
                    return@sumOf 0
                }
                appDb.bookDao.clearCoverOverridesIfUnchanged(
                    currentBook.bookUrl,
                    currentBook.customCoverUrl,
                    currentBook.persistedCoverUrl,
                )
            }
        }.onSuccess {
            if (operationId == coverOperationId) {
                context.toastOnUi(context.getString(R.string.restore_source_cover_result, it))
            }
        }
    }

    private fun beginCoverOperation(): Int {
        val operationId = ++coverOperationId
        batchPersistCoverCoroutine?.cancel()
        batchPersistCoverState.postValue(false)
        return operationId
    }

}
