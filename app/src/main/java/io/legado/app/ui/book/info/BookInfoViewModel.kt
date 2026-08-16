package io.legado.app.ui.book.info

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoBooksDirException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.BookInfoOpenResolver
import io.legado.app.help.book.BookInfoShelfFlags
import io.legado.app.help.book.SearchBookShelfHelp
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.addType
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.help.book.savePreservingCustomCoverUrl
import io.legado.app.help.book.update
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.webdav.ObjectNotFoundException
import io.legado.app.model.AudioPlay
import io.legado.app.model.BookCover
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO

private val webFileSuffixPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")

internal fun normalizeWebFileName(
    fileName: String,
    rawSuffix: String?,
    replaceExistingSuffix: Boolean = true,
): String {
    val suffix = rawSuffix
        ?.trim()
        ?.trimStart('.')
        ?.takeIf { webFileSuffixPattern.matches(it) }
        ?: return fileName
    val baseName = fileName.trimEnd('.')
    if (baseName.isEmpty()) return fileName
    val dotIndex = baseName.lastIndexOf('.')
    if (dotIndex <= 0) {
        return "$baseName.$suffix"
    }
    val currentSuffix = baseName.substring(dotIndex + 1)
    if (currentSuffix.equals(suffix, ignoreCase = true)) return baseName
    return if (replaceExistingSuffix) {
        "${baseName.substring(0, dotIndex)}.$suffix"
    } else {
        "$baseName.$suffix"
    }
}

class BookInfoViewModel(application: Application) : BaseViewModel(application) {
    val bookData = MutableLiveData<Book>()
    val chapterListData = MutableLiveData<List<BookChapter>>()
    val webFiles = mutableListOf<WebFile>()
    var inBookshelf = false
    var hasCustomBtn = false
    var bookSource: BookSource? = null
    private var changeSourceCoroutine: Coroutine<*>? = null
    val waitDialogData = MutableLiveData<Boolean>()
    val loadingData = MutableLiveData<Boolean>()
    private val networkLoadingCounter = BookInfoNetworkLoadingCounter(loadingData::postValue)
    val actionLive = MutableLiveData<String>()

    private fun <T> Coroutine<T>.trackNetworkLoading(): Coroutine<T> = apply {
        networkLoadingCounter.begin()
        invokeOnCompletion { networkLoadingCounter.end() }
    }

    fun initData(intent: Intent) {
        execute {
            val name = intent.getStringExtra("name") ?: ""
            val author = intent.getStringExtra("author") ?: ""
            val bookUrl = intent.getStringExtra("bookUrl") ?: ""
            val opened = BookInfoOpenResolver.resolve(
                bookUrl = bookUrl,
                shelfByUrl = bookUrl.takeIf { it.isNotBlank() }?.let { appDb.bookDao.getBook(it) },
                searchByUrl = bookUrl.takeIf { it.isNotBlank() }
                    ?.let { appDb.searchBookDao.getSearchBook(it)?.toBook() },
                shelfByNameAuthor = appDb.bookDao.getBook(name, author),
                searchByNameAuthor = appDb.searchBookDao.getFirstByNameAuthor(name, author)
                    ?.toBook(),
            ) ?: throw NoStackTraceException("未找到书籍")
            inBookshelf = opened.inBookshelf
            upBook(opened.book)
        }.onError {
            AppLog.put(it.localizedMessage, it)
            context.toastOnUi(it.localizedMessage)
        }
    }

    fun upBook(intent: Intent, success: (() -> Unit)? = null) {
        execute {
            val name = intent.getStringExtra("name") ?: ""
            val author = intent.getStringExtra("author") ?: ""
            appDb.bookDao.getBook(name, author)?.let { book ->
                upBook(book)
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    private fun refreshShelfFlags(book: Book) {
        inBookshelf = BookInfoShelfFlags.isOfficialUrl(book.bookUrl)
    }

    private fun upBook(book: Book) {
        execute {
            bookSource = if (book.isLocal) null else
                appDb.bookSourceDao.getBookSource(book.origin)?.also {
                    hasCustomBtn = it.customButton
                }
            bookData.postValue(book)
            upCoverByRule(book)
            if (book.tocUrl.isEmpty() && !book.isLocal) {
                loadBookInfo(book, runPreUpdateJs = inBookshelf)
            } else {
                val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
                if (chapterList.isNotEmpty()) {
                    chapterListData.postValue(chapterList)
                } else {
                    loadChapter(book, isFromBookInfo = true)
                }
            }
        }
    }

    private fun upCoverByRule(book: Book) {
        execute {
            if (book.getDisplayCover().isNullOrBlank()) {
                val coverUrl = BookCover.searchCover(book)
                if (coverUrl.isNullOrBlank()) {
                    return@execute
                }
                book.customCoverUrl = coverUrl
                bookData.postValue(book)
                if (inBookshelf) {
                    saveBook(book, preserveCustomCoverUrl = false)
                }
            }
        }
    }

    fun refreshBook(book: Book) {
        executeLazy(executeContext = IO) {
            if (book.isLocal) {
                book.tocUrl = ""
                book.getRemoteUrl()?.let {
                    val bookWebDav = AppWebDav.defaultBookWebDav
                        ?: throw NoStackTraceException("webDav没有配置")
                    val remoteBook = bookWebDav.getRemoteBook(it)
                    if (remoteBook == null) {
                        book.origin = BookType.localTag
                    } else if (remoteBook.lastModify > book.lastCheckTime &&
                        LocalBook.downloadRemoteBook(book)
                    ) {
                        book.lastCheckTime = remoteBook.lastModify
                    }
                }
            } else {
                val bs = bookSource ?: return@executeLazy
                if (book.originName != bs.bookSourceName) {
                    book.originName = bs.bookSourceName
                }
            }
        }.onError {
            when (it) {
                is ObjectNotFoundException -> {
                    book.origin = BookType.localTag
                }

                else -> {
                    AppLog.put("下载远程书籍<${book.name}>失败", it)
                }
            }
        }.onFinally {
            loadBookInfo(book, false)
        }.start()
    }

    fun loadBookInfo(
        book: Book,
        canReName: Boolean = true,
        runPreUpdateJs: Boolean = true,
        scope: CoroutineScope = viewModelScope
    ) {
        if (book.isLocal) {
            LocalBook.upBookInfo(book)
            bookData.postValue(book)
            loadChapter(book)
        } else {
            val bookSource = bookSource ?: let {
                chapterListData.postValue(chapterListData.value.orEmpty())
                context.toastOnUi(R.string.error_no_source)
                return
            }
            val oldBook = book.copy()
            WebBook.getBookInfo(scope, bookSource, book, canReName = canReName)
                .onSuccess(IO) {
                    refreshShelfFlags(it)
                    if (it.isWebFile) {
                        bookData.postValue(it)
                        if (inBookshelf) {
                            it.savePreservingCustomCoverUrl()
                        }
                        loadWebFile(it)
                    } else {
                        loadChapter(
                            it,
                            runPreUpdateJs,
                            isFromBookInfo = true,
                            oldBook = oldBook,
                        )
                    }
                }.onError {
                    restoreBookAfterLoadFailure(oldBook)
                    AppLog.put("获取书籍信息失败\n${it.localizedMessage}", it)
                    context.toastOnUi(R.string.error_get_book_info)
                }
                .trackNetworkLoading()
        }
    }

    fun loadChapter(
        book: Book,
        runPreUpdateJs: Boolean = true,
        scope: CoroutineScope = viewModelScope,
        isFromBookInfo: Boolean = false,
        oldBook: Book = book.copy(),
    ) {
        if (book.isLocal) {
            execute(scope) {
                LocalBook.getChapterList(book).let {
                    book.update()
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    appDb.bookChapterDao.insert(*it.toTypedArray())
                    ReadBook.onChapterListUpdated(book)
                    bookData.postValue(book)
                    chapterListData.postValue(it)
                }
            }.onError {
                chapterListData.postValue(chapterListData.value.orEmpty())
                context.toastOnUi("LoadTocError:${it.localizedMessage}")
            }
        } else {
            val bookSource = bookSource ?: let {
                chapterListData.postValue(chapterListData.value.orEmpty())
                context.toastOnUi(R.string.error_no_source)
                return
            }
            WebBook.getChapterList(
                scope,
                bookSource,
                book,
                runPreUpdateJs,
                isFromBookInfo = isFromBookInfo,
            )
                .onSuccess(IO) {
                    if (inBookshelf) {
                        book.removeType(BookType.updateError)
                        appDb.bookDao.replace(oldBook, book)
                        /**
                         * runPreUpdateJs 有可能会修改 book 的 bookUrl
                         */
                        if (oldBook.bookUrl != book.bookUrl) {
                            BookHelp.updateCacheFolder(oldBook, book)
                        }
                        appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                        appDb.bookChapterDao.insert(*it.toTypedArray())
                        ReadBook.onChapterListUpdated(book)
                    }
                    bookData.postValue(book)
                    chapterListData.postValue(it)
                }.onError {
                    restoreBookAfterLoadFailure(oldBook)
                    AppLog.put("获取目录失败\n${it.localizedMessage}", it)
                    context.toastOnUi(R.string.error_get_chapter_list)
                }
                .trackNetworkLoading()
        }
    }

    private fun restoreBookAfterLoadFailure(oldBook: Book) {
        refreshShelfFlags(oldBook)
        bookData.postValue(oldBook)
        chapterListData.postValue(chapterListData.value.orEmpty())
    }

    fun loadGroup(groupId: Long, success: ((groupNames: String?) -> Unit)) {
        execute {
            appDb.bookGroupDao.getGroupNames(groupId).joinToString(",")
        }.onSuccess {
            success.invoke(it)
        }
    }

    private fun loadWebFile(book: Book) {
        execute {
            webFiles.clear()
            val fileNameNoExtension = if (book.author.isBlank()) book.name
            else "${book.name} 作者：${book.author}"
            book.downloadUrls!!.map {
                val analyzeUrl = AnalyzeUrl(
                    it, source = bookSource,
                    coroutineContext = coroutineContext
                )
                val urlFileName = UrlUtil.getFileName(analyzeUrl)
                val fileName = urlFileName ?: fileNameNoExtension
                WebFile(
                    it,
                    normalizeWebFileName(
                        fileName,
                        analyzeUrl.type,
                        replaceExistingSuffix = urlFileName != null,
                    )
                )
            }
        }.onError {
            chapterListData.postValue(emptyList())
            context.toastOnUi("LoadWebFileError\n${it.localizedMessage}")
        }.onSuccess {
            webFiles.addAll(it)
            book.latestChapterTitle = "已下载"
            bookData.postValue(book)
            chapterListData.postValue(emptyList())
        }
    }

    /* 导入或者下载在线文件 */
    fun <T> importOrDownloadWebFile(webFile: WebFile, success: ((T) -> Unit)?) {
        bookSource ?: return
        execute {
            waitDialogData.postValue(true)
            if (webFile.isSupported) {
                val book = LocalBook.importFileOnLine(
                    webFile.url,
                    bookData.value!!.getExportFileName(webFile.suffix),
                    bookSource
                )
                changeToLocalBook(book)
            } else {
                LocalBook.saveBookFile(
                    webFile.url,
                    bookData.value!!.getExportFileName(webFile.suffix),
                    bookSource
                )
            }
        }.onSuccess {
            @Suppress("unchecked_cast")
            success?.invoke(it as T)
        }.onError {
            when (it) {
                is NoBooksDirException -> actionLive.postValue("selectBooksDir")
                else -> {
                    AppLog.put("ImportWebFileError\n${it.localizedMessage}", it)
                    context.toastOnUi("ImportWebFileError\n${it.localizedMessage}")
                    webFiles.remove(webFile)
                }
            }
        }.onFinally {
            waitDialogData.postValue(false)
        }
    }

    fun getArchiveFilesName(archiveFileUri: Uri, onSuccess: (List<String>) -> Unit) {
        execute {
            ArchiveUtils.getArchiveFilesName(archiveFileUri) {
                AppPattern.bookFileRegex.matches(it)
            }
        }.onError {
            AppLog.put("getArchiveEntriesName Error:\n${it.localizedMessage}", it)
            context.toastOnUi("getArchiveEntriesName Error:\n${it.localizedMessage}")
        }.onSuccess {
            onSuccess.invoke(it)
        }
    }

    fun importArchiveBook(
        archiveFileUri: Uri,
        archiveEntryName: String,
        success: ((Book) -> Unit)? = null
    ) {
        execute {
            val suffix = archiveEntryName.substringAfterLast(".")
            LocalBook.importArchiveFile(
                archiveFileUri,
                bookData.value!!.getExportFileName(suffix)
            ) {
                it.contains(archiveEntryName)
            }.first()
        }.onSuccess {
            val book = changeToLocalBook(it)
            success?.invoke(book)
        }.onError {
            AppLog.put("importArchiveBook Error:\n${it.localizedMessage}", it)
            context.toastOnUi("importArchiveBook Error:\n${it.localizedMessage}")
        }
    }

    fun changeTo(
        source: BookSource,
        book: Book,
        toc: List<BookChapter>,
        onSuccess: () -> Unit = {},
    ) {
        changeSourceCoroutine?.cancel()
        changeSourceCoroutine = execute {
            bookSource = source.also {
                hasCustomBtn = it.customButton
            }
            bookData.value?.migrateTo(book, toc)
            if (book.isWebFile) {
                loadWebFile(book)
            }
            if (inBookshelf) {
                book.removeType(BookType.updateError)
                bookData.value?.delete()
                appDb.bookDao.insert(book)
                appDb.bookChapterDao.insert(*toc.toTypedArray())
            }
            bookData.postValue(book)
            chapterListData.postValue(toc)
            refreshShelfFlags(book)
        }.onSuccess {
            onSuccess()
        }.onFinally {
            postEvent(EventBus.SOURCE_CHANGED, book.bookUrl)
        }
    }

    fun topBook() {
        execute {
            bookData.value?.let { book ->
                val minOrder = appDb.bookDao.minOrder
                book.order = minOrder - 1
                book.durChapterTime = System.currentTimeMillis()
                book.update()
            }
        }
    }

    fun saveBook(
        book: Book?,
        preserveCustomCoverUrl: Boolean = true,
        success: (() -> Unit)? = null,
    ) {
        book ?: return
        execute {
            if (book.order == 0) {
                book.order = appDb.bookDao.minOrder - 1
            }
            val byUrl = appDb.bookDao.getBook(book.bookUrl)
            if (byUrl != null) {
                book.durChapterIndex = byUrl.durChapterIndex
                book.durChapterPos = byUrl.durChapterPos
                book.durChapterTitle = byUrl.durChapterTitle
                BookInfoShelfFlags.keepExistingNotShelf(book, byUrl)
                if (preserveCustomCoverUrl) {
                    book.savePreservingCustomCoverUrl()
                } else {
                    book.save()
                }
            } else {
                val persisted = SearchBookShelfHelp.persistIncomingBook(book)
                if (persisted == null || persisted.bookUrl != book.bookUrl) {
                    refreshShelfFlags(book)
                    context.toastOnUi(
                        context.getString(
                            R.string.local_book_identity_conflict,
                            book.name,
                            book.author,
                        )
                    )
                    return@execute false
                }
            }
            refreshShelfFlags(book)
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.book = book
            } else if (AudioPlay.book?.bookUrl == book.bookUrl) {
                AudioPlay.book = book
            }
            true
        }.onSuccess { saved ->
            if (saved == true) {
                success?.invoke()
            }
        }
    }

    fun saveChapterList(success: (() -> Unit)?) {
        execute {
            chapterListData.value?.let {
                appDb.bookChapterDao.insert(*it.toTypedArray())
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    fun addToBookshelf(success: (() -> Unit)?) { //点击书架按钮或在加分组时触发
        execute {
            bookData.value?.let { book ->
                val incoming = book.copy()
                incoming.removeType(BookType.notShelf)
                if (incoming.order == 0) {
                    incoming.order = appDb.bookDao.minOrder - 1
                }
                appDb.bookDao.getBook(incoming.name, incoming.author)?.let { existing ->
                    if (existing.bookUrl == incoming.bookUrl) {
                        incoming.durChapterIndex = existing.durChapterIndex
                        incoming.durChapterPos = existing.durChapterPos
                        incoming.durChapterTitle = existing.durChapterTitle
                    }
                }
                if (SearchBookShelfHelp.shouldSkipWeakInsert(
                        incoming.name,
                        incoming.author,
                        incoming.bookUrl,
                    )
                ) {
                    refreshShelfFlags(incoming)
                    context.toastOnUi(
                        context.getString(
                            R.string.local_book_identity_conflict,
                            incoming.name,
                            incoming.author,
                        )
                    )
                    return@execute false
                }
                val persisted = SearchBookShelfHelp.persistIncomingBook(incoming)
                val savedThisBook = persisted != null && persisted.bookUrl == incoming.bookUrl
                if (!savedThisBook) {
                    refreshShelfFlags(incoming)
                    context.toastOnUi(
                        context.getString(
                            R.string.local_book_identity_conflict,
                            incoming.name,
                            incoming.author,
                        )
                    )
                    return@execute false
                }
                book.removeType(BookType.notShelf)
                book.order = incoming.order
                book.author = incoming.author
                book.durChapterIndex = incoming.durChapterIndex
                book.durChapterPos = incoming.durChapterPos
                book.durChapterTitle = incoming.durChapterTitle
                if (ReadBook.book?.isSameNameAuthor(book) == true) {
                    ReadBook.book = book
                } else if (AudioPlay.book?.isSameNameAuthor(book) == true) {
                    AudioPlay.book = book
                }
                SourceCallBack.callBackBook(SourceCallBack.ADD_BOOK_SHELF, bookSource, book)
                chapterListData.value?.let {
                    appDb.bookChapterDao.insert(*it.toTypedArray())
                }
                inBookshelf = true
                true
            } ?: run {
                inBookshelf = false
                false
            }
        }.onSuccess { saved ->
            if (saved == true) {
                success?.invoke()
            }
        }
    }

    fun getBook(toastNull: Boolean = true): Book? {
        val book = bookData.value
        if (toastNull && book == null) {
            context.toastOnUi("book is null")
        }
        return book
    }

    fun delBook(
        deleteOriginal: Boolean = false,
        onlyNotShelf: Boolean = false,
        success: (() -> Unit)? = null,
    ) {
        execute {
            val book = bookData.value ?: return@execute
            if (onlyNotShelf) {
                val deleted = appDb.bookDao.deleteNotShelfByUrl(book.bookUrl)
                if (deleted > 0 && ReadBook.book?.bookUrl == book.bookUrl) {
                    ReadBook.book = null
                }
                if (deleted > 0 && book.isLocal) {
                    LocalBook.deleteBook(book, deleteOriginal)
                }
            } else if (BookInfoShelfFlags.canDeleteBookUrl(
                    book.bookUrl,
                    appDb.bookDao.getBook(book.bookUrl)?.bookUrl,
                )
            ) {
                book.delete()
                if (book.isLocal) {
                    LocalBook.deleteBook(book, deleteOriginal)
                }
            }
            refreshShelfFlags(book)
        }.onSuccess {
            success?.invoke()
        }
    }

    fun clearCache(book: Book) {
        execute {
            BookHelp.clearCache(book)
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.clearTextChapter()
            }
            if (ReadManga.book?.bookUrl == book.bookUrl) {
                ReadManga.clearMangaChapter()
            }
        }.onSuccess {
            context.toastOnUi(R.string.clear_cache_success)
        }.onError {
            context.toastOnUi("清理缓存出错\n${it.localizedMessage}")
        }
    }

    fun upEditBook() {
        bookData.value?.let {
            appDb.bookDao.getBook(it.bookUrl)?.let { book ->
                bookData.postValue(book)
            }
        }
    }

    private fun changeToLocalBook(localBook: Book): Book {
        return LocalBook.mergeBook(localBook, bookData.value).let {
            bookData.postValue(it)
            loadChapter(it)
            inBookshelf = true
            it
        }
    }

    fun onButtonClick(activity: AppCompatActivity, name: String, click: String) {
        val source = bookSource ?: return
        val book = bookData.value ?: return
        execute {
            val java = SourceLoginJsExtensions(activity, source)
            runScriptWithContext {
                source.evalJS(click) {
                    put("result", null)
                    put("java", java)
                    put("book", book)
                }
            }
        }.onError {
            AppLog.put("${source.bookSourceName}: ${it.localizedMessage}", it)
            context.toastOnUi("$name click error\n${it.localizedMessage}")
        }
    }

    data class WebFile(
        val url: String,
        val name: String,
    ) {

        override fun toString(): String {
            return name
        }

        // 后缀
        val suffix: String = UrlUtil.getSuffix(name)

        // txt epub umd pdf等文件
        val isSupported: Boolean = AppPattern.bookFileRegex.matches(name)

        // 压缩包形式的txt epub umd pdf文件
        val isSupportDecompress: Boolean = AppPattern.archiveFileRegex.matches(name)

    }

}
