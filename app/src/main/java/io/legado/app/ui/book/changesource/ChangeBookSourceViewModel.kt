package io.legado.app.ui.book.changesource

import android.app.Application
import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.primaryStr
import io.legado.app.help.book.releaseHtmlData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.internString
import io.legado.app.utils.mapParallel
import io.legado.app.utils.mapParallelSafe
import io.legado.app.utils.onEachIndexed
import io.legado.app.utils.runCatchingCancellable
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

internal class PendingEvent<out T>(private val value: T) {
    private val handled = AtomicBoolean(false)

    fun peek(): T? = value.takeIf { !handled.get() }

    fun take(): T? = value.takeIf { handled.compareAndSet(false, true) }
}

internal class SourceChangeCompletion(
    private val deleteAfterChange: SearchBook?,
    private val delete: (SearchBook) -> Unit,
) {
    private val completed = AtomicBoolean(false)

    fun success() {
        val source = deleteAfterChange ?: return
        if (completed.compareAndSet(false, true)) delete(source)
    }
}

internal sealed interface SourceChangeResult {
    data class Success(
        val book: Book,
        val toc: List<BookChapter>,
        val source: BookSource,
        val dismissDialog: Boolean,
        val deleteAfterChange: SearchBook? = null,
    ) : SourceChangeResult

    data class Error(val throwable: Throwable) : SourceChangeResult
}

@Suppress("MemberVisibilityCanBePrivate")
open class ChangeBookSourceViewModel(application: Application) : BaseViewModel(application) {
    protected open val pinCurrentSource = false
    private val threadCount = AppConfig.threadCount
    private var searchPool: ExecutorCoroutineDispatcher? = null
    val searchStateData = MutableLiveData<Boolean>()
    internal val searchFinishData = MutableLiveData<PendingEvent<Boolean>>()
    val changeSourceLoading = MutableLiveData(false)
    val changeSourceCancelable = MutableLiveData(true)
    internal val changeSourceResult = MutableLiveData<PendingEvent<SourceChangeResult>>()
    var name: String = ""
    var author: String = ""
    private var fromReadBookActivity = false
    private var oldBook: Book? = null
    private var referenceWordCount: Int? = null
    private var relativeFilterWarningShown = false
    private var screenKey: String = ""
    private var bookSourceParts = arrayListOf<BookSourcePart>()
    val totalSourceCount: Int
        get() = bookSourceParts.size
    private val searchBooks = Collections.synchronizedList(arrayListOf<SearchBook>())
    private val tocMap = ConcurrentHashMap<String, List<BookChapter>>()
    private val _changeSourceProgress = MutableStateFlow(0 to "")
    val changeSourceProgress = _changeSourceProgress.asStateFlow()
    private var tocMapChapterCount = 0
    private val contentProcessor by lazy {
        ContentProcessor.get(oldBook!!)
    }
    private var searchCallback: SourceCallback? = null
    private var changeSourceTask: Coroutine<Triple<Book, List<BookChapter>, BookSource>>? = null
    private val chapterNumRegex = "^\\[(\\d+)]".toRegex()
    private val comparatorBase by lazy {
        compareByDescending<SearchBook> { getBookScore(it) }
            .thenByDescending { SourceConfig.getSourceScore(it.origin) }
    }
    private val defaultComparator by lazy {
        comparatorBase.thenBy { it.originOrder }
    }
    private val wordCountComparator by lazy {
        comparatorBase.thenByDescending { it.chapterWordCount > 1000 }
            .thenByDescending { getChapterNum(it.chapterWordCountText) }
            .thenByDescending { it.chapterWordCount }
            .thenBy { it.originOrder }
    }

    private fun currentResults(): List<SearchBook> {
        val books = synchronized(searchBooks) { searchBooks.toList() }
        val filterMode = if (AppConfig.changeSourceLoadWordCount) {
            AppConfig.changeSourceWordCountFilterMode
        } else {
            ChangeSourceResultOptions.FILTER_OFF
        }
        val comparator = when {
            AppConfig.changeSourceSortRespondTime ->
                ChangeSourceResultOptions.responseTimeComparator(defaultComparator)

            filterMode != ChangeSourceResultOptions.FILTER_OFF ->
                ChangeSourceResultOptions.measuredFirstComparator(wordCountComparator)

            AppConfig.changeSourceLoadWordCount -> wordCountComparator
            else -> defaultComparator
        }
        return ChangeSourceResultOptions.apply(
            books = books,
            filterMode = filterMode,
            minimum = AppConfig.changeSourceWordCountFilterMin,
            maximum = AppConfig.changeSourceWordCountFilterMax,
            referenceWordCount = getReferenceWordCount(books),
            comparator = comparator,
            pinnedBookUrl = if (pinCurrentSource) oldBook?.bookUrl else null,
        )
    }

    private var task: Job? = null
    private val operationState = ChangeSourceOperationState()
    private val operationPreparation = Mutex()
    val bookMap = ConcurrentHashMap<String, Book>()
    val searchDataFlow = callbackFlow {

        searchCallback = object : SourceCallback {

            override fun searchSuccess(searchBook: SearchBook) {
                searchBook.releaseHtmlData()
                appDb.searchBookDao.insert(searchBook)
                val visible = screenKey.isEmpty() || searchBook.name.contains(screenKey)
                synchronized(searchBooks) {
                    searchBooks.remove(searchBook)
                    if (visible) searchBooks.add(searchBook)
                }
                trySend(arrayOf(searchBooks))
            }

            override fun upAdapter() {
                trySend(arrayOf(searchBooks))
            }

        }

        getDbSearchBooks().let { cachedBooks ->
            searchBooks.clear()
            searchBooks.addAll(cachedBooks)
            trySend(arrayOf(searchBooks))
        }

        when {
            searchBooks.isEmpty() -> startSearch()
            AppConfig.changeSourceLoadWordCount -> startRefreshList(true)
        }

        awaitClose {
            searchCallback = null
        }
    }.map {
        kotlin.runCatching {
            currentResults()
        }.onFailure {
            AppLog.put("换源排序出错\n${it.localizedMessage}", it)
        }.getOrDefault(searchBooks)
    }.flowOn(IO).shareIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        replay = 1,
    )

    override fun onCleared() {
        super.onCleared()
        searchPool?.close()
    }

    @CallSuper
    open fun initData(arguments: Bundle?, book: Book?, fromReadBookActivity: Boolean) {
        arguments?.let { bundle ->
            bundle.getString("name")?.let {
                name = it
            }
            bundle.getString("author")?.let {
                author = it.replace(AppPattern.authorRegex, "")
            }
            this.fromReadBookActivity = fromReadBookActivity
            oldBook = book
        }
    }

    private fun initSearchPool() {
        searchPool = Executors
            .newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    }

    fun refresh(): Boolean {
        getDbSearchBooks().let {
            searchBooks.clear()
            searchBooks.addAll(it)
            searchCallback?.upAdapter()
        }
        return searchBooks.isEmpty().also { isEmpty ->
            if (!isEmpty && AppConfig.changeSourceLoadWordCount) {
                refreshResultMeasurements()
            }
        }
    }

    /**
     * 搜索书籍
     */
    fun startSearch() {
        val operation = operationState.reserveOperation()
        execute {
            operationPreparation.withLock {
                if (!operationState.runIfCurrent(operation, ::stopCurrentTask)) {
                    return@withLock
                }
                referenceWordCount = getCachedReferenceWordCount()
                relativeFilterWarningShown = false
                if (searchBooks.isNotEmpty()) {
                    appDb.searchBookDao.delete(*searchBooks.toTypedArray())
                    searchBooks.clear()
                }
                searchCallback?.upAdapter()
                bookSourceParts.clear()
                tocMap.clear()
                bookMap.clear()
                tocMapChapterCount = 0
                _changeSourceProgress.value = 0 to ""
                val searchGroup = AppConfig.searchGroup
                if (searchGroup.isBlank()) {
                    bookSourceParts.addAll(appDb.bookSourceDao.allEnabledPart)
                } else {
                    val sources = appDb.bookSourceDao.getEnabledPartByGroup(searchGroup)
                    if (sources.isEmpty()) {
                        AppConfig.searchGroup = ""
                        bookSourceParts.addAll(appDb.bookSourceDao.allEnabledPart)
                    } else {
                        bookSourceParts.addAll(sources)
                    }
                }
                operationState.startTaskIfCurrent(operation) {
                    initSearchPool()
                    search(operation)
                }
            }
        }.invokeOnCompletion { finishPreparingOperation(operation) }
    }

    fun startSearch(origin: String) {
        val operation = operationState.reserveOperation()
        execute {
            operationPreparation.withLock {
                if (!operationState.runIfCurrent(operation, ::stopCurrentTask)) {
                    return@withLock
                }
                bookSourceParts.clear()
                tocMap.clear()
                bookMap.clear()
                tocMapChapterCount = 0
                bookSourceParts.add(appDb.bookSourceDao.getBookSourcePart(origin)!!)
                searchBooks.removeIf { it.origin == origin }
                operationState.startTaskIfCurrent(operation) {
                    initSearchPool()
                    search(operation)
                }
            }
        }.invokeOnCompletion { finishPreparingOperation(operation) }
    }

    private fun search(operation: Long) {
        task = viewModelScope.launch(searchPool!!) {
            flow {
                for (bs in bookSourceParts) {
                    bs.getBookSource()?.let {
                        emit(it)
                    }
                }
            }.onStart {
                searchStateData.postValue(true)
            }.mapParallel(threadCount) {
                try {
                    withTimeout(60000L) {
                        search(it)
                    }
                } catch (_: Throwable) {
                    currentCoroutineContext().ensureActive()
                }
                it
            }.onEachIndexed { index, value ->
                _changeSourceProgress.update { _ ->
                    index + 1 to value.bookSourceName
                }
            }.onCompletion {
                ensureActive()
                searchStateData.postValue(false)
                warnIfRelativeReferenceUnavailable()
                searchFinishData.postValue(PendingEvent(searchBooks.isEmpty()))
            }.catch {
                AppLog.put("换源搜索出错\n${it.localizedMessage}", it)
            }.collect()
        }.also { task ->
            task.invokeOnCompletion { refreshPendingMeasurements(operation) }
        }
    }

    private suspend fun search(source: BookSource) {
        val checkAuthor = AppConfig.changeSourceCheckAuthor
        val loadInfo = AppConfig.changeSourceLoadInfo
        val loadToc = AppConfig.changeSourceLoadToc
        val loadWordCount = AppConfig.changeSourceLoadWordCount
        val resultBooks = WebBook.searchBookAwait(
            source, name,
            filter = { fName, fAuthor, _ ->
                fName == name && (!checkAuthor || fAuthor.contains(author))
            })
        resultBooks.forEach { searchBook ->
            when {
                loadInfo || loadToc || loadWordCount -> {
                    loadBookInfo(source, searchBook.toBook())
                }

                else -> {
                    searchCallback?.searchSuccess(searchBook)
                }
            }
        }
    }

    private suspend fun loadBookInfo(source: BookSource, book: Book) {
        if (book.tocUrl.isEmpty()) {
            WebBook.getBookInfoAwait(source, book)
        }
        if (AppConfig.changeSourceLoadToc || AppConfig.changeSourceLoadWordCount) {
            loadBookToc(source, book)
        } else {
            //从详情页里获取最新章节
            val searchBook = book.toSearchBook()
            searchCallback?.searchSuccess(searchBook)
        }
    }

    private suspend fun loadBookToc(source: BookSource, book: Book) {
        val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
        for (chapter in chapters) {
            chapter.internString()
        }
        if (tocMapChapterCount < 30000) {
            tocMapChapterCount += chapters.size
            tocMap[book.primaryStr()] = chapters
        }
        bookMap[book.primaryStr()] = book
        book.releaseHtmlData()
        if (AppConfig.changeSourceLoadWordCount) {
            loadBookWordCount(source, book, chapters)
        } else {
            val searchBook = book.toSearchBook()
            searchCallback?.searchSuccess(searchBook)
        }
    }

    private suspend fun loadBookWordCount(
        source: BookSource,
        book: Book,
        chapters: List<BookChapter>
    ) = coroutineScope {
        val chapterIndex = if (fromReadBookActivity) {
            BookHelp.getDurChapter(oldBook!!, chapters)
        } else {
            chapters.lastIndex
        }
        val bookChapter = chapters[chapterIndex]
        var title = bookChapter.title.trim()
        if (title.length > 20) {
            title = title.substring(0, 20) + "…"
        }
        val startTime = System.currentTimeMillis()
        val pair = try {
            val nextChapterUrl = chapters.getOrNull(chapterIndex + 1)?.url
            var content = WebBook.getContentAwait(source, book, bookChapter, nextChapterUrl, false)
            content = contentProcessor.getContent(oldBook!!, bookChapter, content, false).toString()
            val len = content.length
            len to "[${chapterIndex + 1}] ${title}\n字数：${len}"
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            -1 to "[${chapterIndex + 1}] ${title}\n获取字数失败：${t.localizedMessage}"
        }
        val endTime = System.currentTimeMillis()
        val searchBook = book.toSearchBook().apply {
            chapterWordCountText = pair.second
            chapterWordCount = pair.first
            respondTime = (endTime - startTime).toInt()
        }
        searchCallback?.searchSuccess(searchBook)
    }

    private fun getReferenceWordCount(
        books: List<SearchBook> = synchronized(searchBooks) { searchBooks.toList() },
    ): Int? {
        referenceWordCount?.let { return it }
        val book = oldBook ?: return null
        val measured = books.firstOrNull {
            it.origin == book.origin && it.bookUrl == book.bookUrl
        }?.chapterWordCount?.takeIf { it > 0 }
        if (measured != null) referenceWordCount = measured
        return measured
    }

    private fun getCachedReferenceWordCount(): Int? {
        if (AppConfig.changeSourceWordCountFilterMode !=
            ChangeSourceResultOptions.FILTER_RELATIVE
        ) {
            return null
        }
        val book = oldBook ?: return null
        val chapterIndex = if (fromReadBookActivity) {
            book.durChapterIndex
        } else {
            book.totalChapterNum - 1
        }
        if (chapterIndex < 0) return null
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return null
        val content = BookHelp.getContent(book, chapter) ?: return null
        return kotlin.runCatching {
            contentProcessor.getContent(book, chapter, content, false).toString().length
        }.getOrNull()?.takeIf { it > 0 }
    }

    private fun warnIfRelativeReferenceUnavailable() {
        if (
            AppConfig.changeSourceWordCountFilterMode ==
            ChangeSourceResultOptions.FILTER_RELATIVE &&
            getReferenceWordCount() == null &&
            !operationState.hasPendingMeasurementRefresh() &&
            !relativeFilterWarningShown
        ) {
            relativeFilterWarningShown = true
            context.toastOnUi(R.string.change_source_relative_word_count_unavailable)
        }
    }

    fun onLoadWordCountChecked() {
        if (AppConfig.changeSourceLoadWordCount) {
            refreshResultMeasurements()
        } else {
            searchCallback?.upAdapter()
        }
    }

    fun onResultOptionsChanged(reloadMeasurements: Boolean) {
        if (reloadMeasurements) {
            refreshResultMeasurements()
        } else {
            searchCallback?.upAdapter()
        }
    }

    private fun refreshResultMeasurements() {
        val operation = operationState.reserveMeasurementRefresh(
            enabled = AppConfig.changeSourceLoadWordCount,
            hasResults = searchBooks.isNotEmpty(),
        )
        if (operation == null) {
            searchCallback?.upAdapter()
        } else {
            startRefreshList(true, operation)
        }
    }

    private fun refreshPendingMeasurements(operation: Long) {
        operationState.finishTask(operation)?.let {
            startRefreshList(true, it)
        }
    }

    private fun finishPreparingOperation(operation: Long) {
        operationState.finishPreparation(operation)?.let {
            startRefreshList(true, it)
        }
    }

    /**
     * 刷新列表
     */
    fun startRefreshList(onlyRefreshNoWordCountBook: Boolean = false) {
        startRefreshList(onlyRefreshNoWordCountBook, operationState.reserveOperation())
    }

    private fun startRefreshList(
        onlyRefreshNoWordCountBook: Boolean,
        operation: Long,
    ) {
        execute {
            operationPreparation.withLock {
                if (!operationState.runIfCurrent(operation, ::stopCurrentTask)) {
                    return@withLock
                }
                if (onlyRefreshNoWordCountBook && !AppConfig.changeSourceLoadWordCount) {
                    return@withLock
                }
                referenceWordCount = getCachedReferenceWordCount()
                relativeFilterWarningShown = false
                val books = synchronized(searchBooks) {
                    if (onlyRefreshNoWordCountBook) {
                        searchBooks.filter { it.chapterWordCountText == null }
                    } else {
                        searchBooks.toList()
                    }
                }
                searchCallback?.upAdapter()
                if (books.isEmpty()) {
                    operationState.runIfCurrent(operation) {
                        warnIfRelativeReferenceUnavailable()
                    }
                    return@withLock
                }
                operationState.startTaskIfCurrent(operation) {
                    initSearchPool()
                    refreshList(books, operation)
                }
            }
        }.invokeOnCompletion { finishPreparingOperation(operation) }
    }

    private fun refreshList(books: List<SearchBook>, operation: Long) {
        task = viewModelScope.launch(searchPool!!) {
            flow {
                for (searchBook in books) {
                    emit(searchBook)
                }
            }.onStart {
                searchStateData.postValue(true)
            }.mapParallelSafe(threadCount) {
                val source = appDb.bookSourceDao.getBookSource(it.origin)!!
                withTimeout(60000L) {
                    loadBookInfo(source, it.toBook())
                }
            }.onCompletion {
                currentCoroutineContext().ensureActive()
                searchStateData.postValue(false)
                warnIfRelativeReferenceUnavailable()
            }.catch {
                AppLog.put("换源刷新列表出错\n${it.localizedMessage}", it)
            }.collect()
        }.also { task ->
            task.invokeOnCompletion { refreshPendingMeasurements(operation) }
        }
    }

    private fun getDbSearchBooks(): List<SearchBook> {
        return if (screenKey.isEmpty()) {
            if (AppConfig.changeSourceCheckAuthor) {
                appDb.searchBookDao.changeSourceByGroup(
                    name, author, AppConfig.searchGroup
                )
            } else {
                appDb.searchBookDao.changeSourceByGroup(
                    name, "", AppConfig.searchGroup
                )
            }
        } else {
            if (AppConfig.changeSourceCheckAuthor) {
                appDb.searchBookDao.changeSourceSearch(
                    name, author, screenKey, AppConfig.searchGroup
                )
            } else {
                appDb.searchBookDao.changeSourceSearch(
                    name, "", screenKey, AppConfig.searchGroup
                )
            }
        }
    }

    /**
     * 筛选
     */
    fun screen(key: String?) {
        screenKey = key?.trim() ?: ""
        execute {
            getDbSearchBooks().let {
                searchBooks.clear()
                searchBooks.addAll(it)
                searchCallback?.upAdapter()
            }
        }
    }

    fun startOrStopSearch() {
        if (operationState.isRunning()) {
            stopSearch()
        } else {
            startSearch()
        }
    }

    fun stopSearch() {
        operationState.cancel(::stopCurrentTask)
    }

    private fun stopCurrentTask() {
        task?.cancel()
        searchPool?.close()
        searchStateData.postValue(false)
    }

    fun getToc(
        book: Book,
        onSuccess: (toc: List<BookChapter>, source: BookSource) -> Unit,
        onError: (e: Throwable) -> Unit
    ): Coroutine<Pair<List<BookChapter>, BookSource>> {
        return execute {
            val toc = tocMap[book.primaryStr()]
            if (toc != null) {
                val source = appDb.bookSourceDao.getBookSource(book.origin)
                return@execute Pair(toc, source!!)
            }
            val result = getToc(book).getOrThrow()
            tocMap[book.primaryStr()] = result.first
            return@execute result
        }.onSuccess {
            onSuccess.invoke(it.first, it.second)
        }.onError {
            onError.invoke(it)
        }
    }

    fun changeSource(book: Book) {
        changeSourceTask?.cancel()
        changeSourceCancelable.value = true
        changeSourceLoading.value = true
        changeSourceTask = execute {
            if (book.isWebFile) {
                val source = appDb.bookSourceDao.getBookSource(book.origin)
                    ?: throw NoStackTraceException("书源不存在")
                Triple(book, emptyList(), source)
            } else {
                val (toc, source) = tocMap[book.primaryStr()]?.let { toc ->
                    val source = appDb.bookSourceDao.getBookSource(book.origin)
                        ?: throw NoStackTraceException("书源不存在")
                    toc to source
                } ?: getToc(book).getOrThrow().also { result ->
                    tocMap[book.primaryStr()] = result.first
                }
                Triple(book, toc, source)
            }
        }.onSuccess { (resultBook, toc, source) ->
            changeSourceTask = null
            changeSourceLoading.value = false
            changeSourceCancelable.value = true
            changeSourceResult.value = PendingEvent(
                SourceChangeResult.Success(resultBook, toc, source, dismissDialog = true)
            )
        }.onError { throwable ->
            changeSourceTask = null
            changeSourceLoading.value = false
            changeSourceCancelable.value = true
            changeSourceResult.value = PendingEvent(SourceChangeResult.Error(throwable))
        }
    }

    fun cancelChangeSource() {
        if (changeSourceCancelable.value == false) return
        changeSourceTask?.cancel()
        changeSourceTask = null
        changeSourceLoading.value = false
        changeSourceCancelable.value = true
    }

    suspend fun getToc(book: Book): Result<Pair<List<BookChapter>, BookSource>> {
        return runCatchingCancellable {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
                ?: throw NoStackTraceException("书源不存在")
            if (book.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, book)
            }
            val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
            Pair(toc, source)
        }
    }

    fun disableSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                source.enabled = false
                appDb.bookSourceDao.update(source)
            }
            searchBooks.remove(searchBook)
            searchCallback?.upAdapter()
        }
    }

    fun topSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                val minOrder = appDb.bookSourceDao.minOrder - 1
                source.customOrder = minOrder
                searchBook.originOrder = source.customOrder
                appDb.bookSourceDao.update(source)
                updateSource(searchBook)
            }
            searchCallback?.upAdapter()
        }
    }

    fun bottomSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                val maxOrder = appDb.bookSourceDao.maxOrder + 1
                source.customOrder = maxOrder
                searchBook.originOrder = source.customOrder
                appDb.bookSourceDao.update(source)
                updateSource(searchBook)
            }
            searchCallback?.upAdapter()
        }
    }

    fun updateSource(searchBook: SearchBook) {
        appDb.searchBookDao.update(searchBook)
    }

    fun del(searchBook: SearchBook) {
        Coroutine.async {
            SourceHelp.deleteBookSource(searchBook.origin)
            appDb.searchBookDao.delete(searchBook)
        }
        searchBooks.remove(searchBook)
        searchCallback?.upAdapter()
    }

    fun autoChangeSource(bookType: Int?, deleteAfterChange: SearchBook) {
        changeSourceTask?.cancel()
        changeSourceCancelable.value = false
        changeSourceLoading.value = true
        changeSourceTask = execute {
            currentResults().forEach {
                if (it.origin != deleteAfterChange.origin && it.type == bookType) {
                    val book = it.toBook()
                    val result = getToc(book).getOrNull()
                    if (result != null) {
                        return@execute Triple(book, result.first, result.second)
                    }
                }
            }
            throw NoStackTraceException("没有有效源")
        }.onSuccess { (book, toc, source) ->
            changeSourceTask = null
            changeSourceLoading.value = false
            changeSourceCancelable.value = true
            changeSourceResult.value = PendingEvent(
                SourceChangeResult.Success(
                    book,
                    toc,
                    source,
                    dismissDialog = false,
                    deleteAfterChange = deleteAfterChange,
                )
            )
        }.onError { throwable ->
            changeSourceTask = null
            changeSourceLoading.value = false
            changeSourceCancelable.value = true
            changeSourceResult.value = PendingEvent(SourceChangeResult.Error(throwable))
        }
    }

    fun setBookScore(searchBook: SearchBook, score: Int) {
        execute {
            SourceConfig.setBookScore(searchBook.origin, searchBook.name, searchBook.author, score)
            searchCallback?.upAdapter()
        }
    }

    fun getBookScore(searchBook: SearchBook): Int {
        return SourceConfig.getBookScore(searchBook.origin, searchBook.name, searchBook.author)
    }

    private fun getChapterNum(wordCountText: String?): Int {
        wordCountText ?: return -1
        return chapterNumRegex.find(wordCountText)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    interface SourceCallback {

        fun searchSuccess(searchBook: SearchBook)

        fun upAdapter()

    }

}
