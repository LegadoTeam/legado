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
import kotlinx.coroutines.CoroutineStart
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
    private val rawHits = SearchHitAccumulator()
    private val pageOwner = SearchPageOwner()
    private var workingState = MutableStateFlow(true)
    private val activeProgress = AtomicReference<SearchProgressReporter?>()


    private fun initSearchPool() {
        searchPool?.close()
        searchPool = Executors
            .newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    }

    fun search(searchId: Long, key: String) {
        synchronized(pageOwner) {
            if (searchId == mSearchId && pageOwner.isRunning()) return
            if (searchId != mSearchId) {
                if (key.isEmpty()) {
                    return
                }
                searchKey = key
                if (mSearchId != 0L) {
                    close()
                }
                rawHits.begin(searchId)
                bookSourceParts = callBack.getSearchScope().getBookSourceParts()
                if (bookSourceParts.isEmpty()) {
                    rawHits.reset()
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
    }

    private fun startSearch() {
        val searchId = mSearchId
        val precision = appCtx.getPrefBoolean(PreferKey.precisionSearch)
        var hasMore = false
        val sourceParts = bookSourceParts
        val key = searchKey
        val page = searchPage
        val progress = SearchProgressReporter(sourceParts.size, callBack::onSearchProgress)
        activeProgress.getAndSet(progress)?.cancel()
        val job = scope.launch(searchPool!!, start = CoroutineStart.LAZY) {
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
                if (searchId != mSearchId) return@onEach
                for (book in items) {
                    book.releaseHtmlData()
                }
                hasMore = hasMore || items.isNotEmpty()
                appDb.searchBookDao.insert(*items.toTypedArray())
                val published = mergeItems(searchId, items, precision, key) ?: return@onEach
                currentCoroutineContext().ensureActive()
                callBack.onSearchSuccess(published)
            }.onCompletion { error ->
                val context = currentCoroutineContext()
                pageOwner.complete(context[Job]) {
                    val published = runCatching {
                        rebuildDisplay(searchId, precision, key)
                    }.getOrNull()
                    when {
                        error == null -> progress.finish {
                            callBack.onSearchFinish(published.isNullOrEmpty(), hasMore)
                        }
                        context.isActive -> progress.finish {
                            callBack.onSearchCancel()
                        }
                        else -> progress.cancel()
                    }
                    activeProgress.compareAndSet(progress, null)
                }
            }.catch {
                AppLog.put("书源搜索出错\n${it.localizedMessage}", it)
            }.collect()
        }
        check(pageOwner.register(job))
        job.start()
    }

    private suspend fun mergeItems(
        searchId: Long,
        newDataS: List<SearchBook>,
        precision: Boolean,
        key: String,
    ): List<SearchBook>? {
        val copies = if (newDataS.isEmpty()) {
            emptyList()
        } else {
            val out = ArrayList<SearchBook>(newDataS.size)
            for (book in newDataS) {
                currentCoroutineContext().ensureActive()
                out.add(book.copy())
            }
            out
        }
        val snapshot = rawHits.append(searchId, copies) ?: return null
        return applyDisplay(searchId, snapshot, precision, key)
    }

    /**
     * Rebuild the visible list from raw hits.
     * Same title + empty/佚名 author merges only when there is exactly one real author.
     */
    private suspend fun rebuildDisplay(searchId: Long, precision: Boolean, key: String): List<SearchBook>? {
        val snapshot = rawHits.snapshot(searchId) ?: return null
        return applyDisplay(searchId, snapshot, precision, key)
    }

    private suspend fun applyDisplay(
        searchId: Long,
        snapshot: List<SearchBook>,
        precision: Boolean,
        key: String,
    ): List<SearchBook>? {
        val merged = SearchBookMerge.rebuildFromRawHits(snapshot)
        val equalData = arrayListOf<SearchBook>()
        val containsData = arrayListOf<SearchBook>()
        val tagsData = arrayListOf<SearchBook>()
        val otherData = arrayListOf<SearchBook>()
        for (book in merged) {
            currentCoroutineContext().ensureActive()
            when {
                book.name == key || book.author == key -> equalData.add(book)
                book.kind?.contains(key) == true -> tagsData.add(book)
                book.name.contains(key) || book.author.contains(key) -> containsData.add(book)
                !precision -> otherData.add(book)
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
        return rawHits.publish(searchId, equalData)
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
        synchronized(pageOwner) {
            activeProgress.getAndSet(null)?.cancel()
            pageOwner.cancel()?.cancel()
            searchPool?.close()
            searchPool = null
            rawHits.reset()
            mSearchId = 0L
        }
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

internal class SearchPageOwner {
    private var owner: Job? = null

    @Synchronized
    fun isRunning(): Boolean = owner != null

    @Synchronized
    fun register(job: Job): Boolean {
        if (owner != null) return false
        owner = job
        return true
    }

    @Synchronized
    fun complete(job: Job?, onComplete: () -> Unit): Boolean {
        if (job == null || owner !== job) return false
        owner = null
        onComplete()
        return true
    }

    @Synchronized
    fun cancel(): Job? {
        val job = owner
        owner = null
        return job
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
