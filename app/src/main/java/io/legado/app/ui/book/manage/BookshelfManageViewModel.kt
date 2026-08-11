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
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
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
    internal var batchPersistCoverCoroutine: Coroutine<PersistentCoverResult>? = null

    fun upCanUpdate(books: List<Book>, canUpdate: Boolean) {
        execute {
            val array = Array(books.size) {
                books[it].copy(canUpdate = canUpdate).apply {
                    if (!canUpdate) {
                        removeType(BookType.updateError)
                    }
                }
            }
            appDb.bookDao.update(*array)
        }
    }

    fun updateBook(vararg book: Book) {
        execute {
            appDb.bookDao.update(*book)
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
        batchPersistCoverCoroutine?.cancel()
        batchPersistCoverCoroutine = execute {
            var saved = 0
            var skipped = 0
            var failed = 0
            val coversDir = File(context.externalFiles, "covers")
            books.forEachIndexed { index, book ->
                batchPersistCoverProcess.postValue(
                    context.getString(R.string.persist_cover_progress, index + 1, books.size)
                )
                val coverUrl = book.networkCoverForPersistence()
                if (coverUrl == null) {
                    skipped++
                    return@forEachIndexed
                }
                try {
                    var options = RequestOptions().set(
                        OkHttpModelLoader.loadOnlyWifiOption,
                        AppConfig.loadCoverOnlyWifi
                    )
                    book.getCoverSourceOrigin()?.let {
                        options = options.set(OkHttpModelLoader.sourceOriginOption, it)
                    }
                    val target = ImageLoader.loadFile(context, coverUrl)
                        .apply(options)
                        .submit()
                    try {
                        val downloaded = runInterruptible { target.get() }
                        currentCoroutineContext().ensureActive()
                        val persistent = installPersistentCover(downloaded, coversDir)
                        currentCoroutineContext().ensureActive()
                        check(
                            appDb.bookDao.updateCustomCoverUrl(
                                book.bookUrl,
                                persistent.absolutePath
                            ) == 1
                        ) { "Book no longer exists" }
                        saved++
                    } finally {
                        Glide.with(context).clear(target)
                    }
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    failed++
                    AppLog.put("保存封面失败: ${book.name}\n${e.localizedMessage}", e)
                }
            }
            PersistentCoverResult(saved, skipped, failed)
        }.onStart {
            batchPersistCoverState.postValue(true)
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
            batchPersistCoverState.postValue(false)
        }
    }

    fun restoreSourceCovers(books: List<Book>) {
        execute {
            books.filter { !it.customCoverUrl.isNullOrEmpty() }
                .sumOf { appDb.bookDao.updateCustomCoverUrl(it.bookUrl, null) }
        }.onSuccess {
            context.toastOnUi(context.getString(R.string.restore_source_cover_result, it))
        }
    }

}
