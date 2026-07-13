package io.legado.app.model.webBook

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.mapParallelSafe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

class SearchModel(private val scope: CoroutineScope, private val callBack: CallBack) {
    val threadCount = AppConfig.threadCount
    private var searchPool: ExecutorCoroutineDispatcher? = null
    private var mSearchId = 0L
    private var searchPage = 1
    private var searchKey: String = ""
    private var bookSourceParts = emptyList<BookSourcePart>()
    private var searchBooks = arrayListOf<SearchBook>()
    private var searchJob: Job? = null
    private var workingState = MutableStateFlow(true)
    private val activeProgress = AtomicReference<SearchProgressReporter?>()


    private fun initSearchPool() {
        searchPool?.close()
        searchPool = Executors
            .newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    }

    fun search(searchId: Long, key: String) {
        if (searchId != mSearchId) {
            if (key.isEmpty()) {
                return
            }
            searchKey = key
            if (mSearchId != 0L) {
                close()
            }
            searchBooks.clear()
            bookSourceParts = callBack.getSearchScope().getBookSourceParts()
            if (bookSourceParts.isEmpty()) {
                callBack.onSearchCancel(NoStackTraceException("启用书源为空"))
                return
            }
            mSearchId = searchId
            searchPage = 1
            initSearchPool()
        } else {
            searchPage++
        }
        startSearch()
    }

    private fun startSearch() {
        val precision = appCtx.getPrefBoolean(PreferKey.precisionSearch)
        var hasMore = false
        val sourceParts = bookSourceParts
        val key = searchKey
        val page = searchPage
        val progress = SearchProgressReporter(sourceParts.size, callBack::onSearchProgress)
        activeProgress.getAndSet(progress)?.cancel()
        searchJob = scope.launch(searchPool!!) {
            flow {
                for (bs in sourceParts) {
                    val source = bs.getBookSource()
                    if (source == null) {
                        if (currentCoroutineContext().isActive) {
                            progress.completeOne()
                        }
                    } else {
                        emit(source)
                    }
                    workingState.first { it }
                }
            }.onStart {
                progress.start(callBack::onSearchStart)
            }.mapParallelSafe(threadCount) {
                try {
                    withTimeout(30000L) {
                        WebBook.searchBookAwait(
                            it, key, page,
                            filter = { name, author, kind ->
                                !precision || name.contains(key) ||
                                        author.contains(key) ||
                                        kind?.contains(key) == true
                            })
                    }
                } finally {
                    if (currentCoroutineContext().isActive) {
                        progress.completeOne()
                    }
                }
            }.onEach { items ->
                for (book in items) {
                    book.releaseHtmlData()
                }
                hasMore = hasMore || items.isNotEmpty()
                appDb.searchBookDao.insert(*items.toTypedArray())
                mergeItems(items, precision, key)
                currentCoroutineContext().ensureActive()
                callBack.onSearchSuccess(searchBooks)
            }.onCompletion { error ->
                when {
                    error == null -> progress.finish {
                        callBack.onSearchFinish(searchBooks.isEmpty(), hasMore)
                    }
                    currentCoroutineContext().isActive -> progress.finish {
                        callBack.onSearchCancel()
                    }
                    else -> progress.cancel()
                }
                activeProgress.compareAndSet(progress, null)
            }.catch {
                AppLog.put("书源搜索出错\n${it.localizedMessage}", it)
            }.collect()
        }
    }

    private suspend fun mergeItems(newDataS: List<SearchBook>, precision: Boolean, key: String) {
        if (newDataS.isNotEmpty()) {
            val copyData = ArrayList(searchBooks)
            val equalData = arrayListOf<SearchBook>()
            val containsData = arrayListOf<SearchBook>()
            val tagsData = arrayListOf<SearchBook>()
            val otherData = arrayListOf<SearchBook>()
            copyData.forEach {
                currentCoroutineContext().ensureActive()
                if (it.name == key || it.author == key) {
                    equalData.add(it)
                } else if (it.kind?.contains(key) == true) {
                    tagsData.add(it)
                } else if (it.name.contains(key) || it.author.contains(key)) {
                    containsData.add(it)
                } else {
                    otherData.add(it)
                }
            }
            newDataS.forEach { nBook ->
                currentCoroutineContext().ensureActive()
                if (nBook.name == key || nBook.author == key) {
                    var hasSame = false
                    equalData.forEach { pBook ->
                        currentCoroutineContext().ensureActive()
                        if (pBook.name == nBook.name && pBook.author == nBook.author) {
                            pBook.addOrigin(nBook.origin)
                            hasSame = true
                        }
                    }
                    if (!hasSame) {
                        equalData.add(nBook)
                    }
                } else if (nBook.kind?.contains(key) == true) {
                    var hasSame = false
                    tagsData.forEach { pBook ->
                        currentCoroutineContext().ensureActive()
                        if (pBook.name == nBook.name && pBook.author == nBook.author) {
                            pBook.addOrigin(nBook.origin)
                            hasSame = true
                        }
                    }
                    if (!hasSame) {
                        tagsData.add(nBook)
                    }
                } else if (nBook.name.contains(key) || nBook.author.contains(key)) {
                    var hasSame = false
                    containsData.forEach { pBook ->
                        currentCoroutineContext().ensureActive()
                        if (pBook.name == nBook.name && pBook.author == nBook.author) {
                            pBook.addOrigin(nBook.origin)
                            hasSame = true
                        }
                    }
                    if (!hasSame) {
                        containsData.add(nBook)
                    }
                } else if (!precision) {
                    var hasSame = false
                    otherData.forEach { pBook ->
                        currentCoroutineContext().ensureActive()
                        if (pBook.name == nBook.name && pBook.author == nBook.author) {
                            pBook.addOrigin(nBook.origin)
                            hasSame = true
                        }
                    }
                    if (!hasSame) {
                        otherData.add(nBook)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            equalData.sortByDescending { it.origins.size }
            equalData.addAll(tagsData.sortedByDescending { it.origins.size })
            equalData.addAll(containsData.sortedByDescending { it.origins.size })
            if (!precision) {
                equalData.addAll(otherData)
            }
            currentCoroutineContext().ensureActive()
            searchBooks = equalData
        }
    }

    fun pause() {
        workingState.value = false
    }

    fun resume() {
        workingState.value = true
    }

    fun cancelSearch() {
        close()
        callBack.onSearchCancel()
    }

    fun close() {
        activeProgress.getAndSet(null)?.cancel()
        searchJob?.cancel()
        searchPool?.close()
        searchPool = null
        mSearchId = 0L
    }

    interface CallBack {
        fun getSearchScope(): SearchScope
        fun onSearchStart()
        fun onSearchProgress(searched: Int, total: Int)
        fun onSearchSuccess(searchBooks: List<SearchBook>)
        fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean)
        fun onSearchCancel(exception: Throwable? = null)
    }

}

internal class SearchProgressReporter(
    total: Int,
    private val onProgress: (searched: Int, total: Int) -> Unit,
) {
    private val total = total.coerceAtLeast(0)
    private var completed = 0
    private var active = true
    private var started = false

    @Synchronized
    fun start(onStart: () -> Unit = {}) {
        if (!active || started) return
        started = true
        onStart()
        onProgress(0, total)
    }

    @Synchronized
    fun completeOne() {
        if (!active || !started || completed >= total) return
        completed++
        onProgress(completed, total)
    }

    @Synchronized
    fun finish(onFinish: () -> Unit) {
        if (!active || !started) return
        active = false
        onFinish()
    }

    @Synchronized
    fun cancel() {
        active = false
    }
}
