package io.legado.app.model

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.ConcurrentException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentSaveToken
import io.legado.app.help.book.refreshBookResources
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.CompositeCoroutine
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.CacheBookService
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import io.legado.app.utils.startService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

object CacheBook {

    val cacheBookMap = ConcurrentHashMap<String, CacheBookModel>()

    private val workingState = MutableStateFlow(true)
    private val mutex = Mutex()

    @Synchronized
    fun invalidateChapters(bookUrl: String, indexes: Iterable<Int>) {
        cacheBookMap[bookUrl]?.downloads?.invalidate(indexes)
    }

    @Synchronized
    fun getOrCreate(bookUrl: String): CacheBookModel? {
        val book = appDb.bookDao.getBook(bookUrl) ?: return null
        val bookSource = appDb.bookSourceDao.getBookSource(book.origin) ?: return null
        updateBookSource(bookSource)
        var cacheBook = cacheBookMap[bookUrl]
        if (cacheBook != null) {
            //存在时更新,书源可能会变化,必须更新
            cacheBook.bookSource = bookSource
            cacheBook.book = book
            return cacheBook
        }
        cacheBook = CacheBookModel(bookSource, book)
        cacheBookMap[bookUrl] = cacheBook
        return cacheBook
    }

    @Synchronized
    fun getOrCreate(bookSource: BookSource, book: Book): CacheBookModel {
        updateBookSource(bookSource)
        var cacheBook = cacheBookMap[book.bookUrl]
        if (cacheBook != null) {
            //存在时更新,书源可能会变化,必须更新
            cacheBook.bookSource = bookSource
            cacheBook.book = book
            return cacheBook
        }
        cacheBook = CacheBookModel(bookSource, book)
        cacheBookMap[book.bookUrl] = cacheBook
        return cacheBook
    }

    private fun updateBookSource(newBookSource: BookSource) {
        cacheBookMap.forEach {
            val model = it.value
            if (model.bookSource.bookSourceUrl == newBookSource.bookSourceUrl) {
                model.bookSource = newBookSource
            }
        }
    }

    fun start(context: Context, book: Book, start: Int, end: Int) {
        if (!book.isLocal) {
            context.startService<CacheBookService> {
                action = IntentAction.start
                putExtra("bookUrl", book.bookUrl)
                putExtra("start", start)
                putExtra("end", end)
            }
        }
    }

    fun remove(context: Context, bookUrl: String) {
        context.startService<CacheBookService> {
            action = IntentAction.remove
            putExtra("bookUrl", bookUrl)
        }
    }

    fun stop(context: Context) {
        if (CacheBookService.isRun) {
            context.startService<CacheBookService> {
                action = IntentAction.stop
            }
        }
    }

    fun close() {
        cacheBookMap.forEach { it.value.stop() }
        successDownloadSet.clear()
        errorDownloadMap.clear()
    }

    fun setWorkingState(value: Boolean) {
        workingState.value = value
    }

    suspend fun startProcessJob(context: CoroutineContext) = mutex.withLock {
        setWorkingState(true)
        flow {
            while (currentCoroutineContext().isActive && cacheBookMap.values.any { it.hasManualWork() }) {
                var emitted = false

                cacheBookMap.forEach { (_, model) ->
                    if (!model.isLoading() && model.waitCount > 0) {
                        emit(model)
                        emitted = true
                    }
                    workingState.first { it }
                }

                if (!emitted) {
                    delay(1000)
                }
            }
        }.onStart {
            postEvent(EventBus.UP_DOWNLOAD_STATE, "")
        }.onEachParallel(AppConfig.threadCount) {
            coroutineScope {
                it.download(this, context)
            }
        }.onCompletion {
            postEvent(EventBus.UP_DOWNLOAD_STATE, "")
        }.collect()
    }


    val downloadSummary: String
        get() {
            return "正在下载:${onDownloadCount}|等待中:${waitCount}|失败:${errorDownloadMap.count()}|成功:${successDownloadSet.size}"
        }

    val isRun: Boolean
        get() {
            cacheBookMap.forEach {
                if (it.value.isRun()) {
                    return true
                }
            }
            return false
        }

    private val waitCount: Int
        get() {
            var count = 0
            cacheBookMap.forEach {
                count += it.value.waitCount
            }
            return count
        }

    val onDownloadCount: Int
        get() {
            var count = 0
            cacheBookMap.forEach {
                count += it.value.onDownloadCount
            }
            return count
        }

    val successDownloadSet: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val errorDownloadMap = ConcurrentHashMap<String, Int>()

    class CacheBookModel(var bookSource: BookSource, var book: Book) {

        internal val downloads = ChapterDownloadState()
        private val resourceRefreshMutex = Mutex()
        private val tasks = CompositeCoroutine()
        @Volatile private var isStopped = false
        @Volatile private var isLoading = false

        val waitCount get() = downloads.waitCount
        val onDownloadCount get() = downloads.runningCount
        internal fun hasManualWork() = isLoading || downloads.hasManualWork

        init { postEvent(EventBus.UP_DOWNLOAD, book.bookUrl) }

        fun isRun(): Boolean = !downloads.isIdle || isLoading
        fun isStop(): Boolean = isStopped || !isRun()
        fun isLoading(): Boolean = isLoading
        fun setLoading() { isLoading = true }

        @Synchronized
        fun stop() {
            isStopped = true
            isLoading = false
            downloads.stopManual()
            tasks.clear()
            onFinally()
        }

        fun addDownload(start: Int, end: Int, refreshResources: Boolean = false) {
            synchronized(CacheBook) {
                val model = cacheBookMap.getOrPut(book.bookUrl) { this }
                model.isStopped = false
                model.downloads.enqueue(start..end, refreshResources)
                model.isLoading = false
            }
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        }

        private fun onFinally() {
            synchronized(CacheBook) {
                if (!isLoading && downloads.isIdle) {
                    cacheBookMap.remove(book.bookUrl, this)
                }
            }
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        }

        private fun needsResourceRefresh(requestBook: Book, chapter: BookChapter): Boolean =
            downloads.requestsResourceRefresh(chapter.index) && BookHelp.resourcesOutdated(requestBook, chapter)

        private fun onSuccess(
            ticket: ChapterDownloadState.Ticket,
            requestBook: Book,
            chapter: BookChapter,
            content: String,
            notifyReader: Boolean,
            contentToken: ContentSaveToken,
        ): Boolean {
            if (!BookHelp.isContentSaveCurrent(contentToken)) return false
            if (!downloads.finish(ticket, Result.success(content),
                    manualComplete = { notifyReader && !needsResourceRefresh(requestBook, chapter) }) {
                successDownloadSet.add(chapter.primaryStr())
                errorDownloadMap.remove(chapter.primaryStr())
            }) return false
            synchronized(ReadBook) {
                if (ReadBook.book?.bookUrl == requestBook.bookUrl && BookHelp.isContentSaveCurrent(contentToken)) {
                    ReadBook.downloadedChapters.add(chapter.index)
                    ReadBook.downloadFailChapters.remove(chapter.index)
                    if (notifyReader) downloadFinish(requestBook, chapter, content,
                        contentToken = contentToken)
                }
            }
            return true
        }

        private fun onError(
            ticket: ChapterDownloadState.Ticket,
            requestBook: Book,
            chapter: BookChapter,
            error: Throwable,
            contentToken: ContentSaveToken,
        ): Boolean {
            if (!BookHelp.isContentSaveCurrent(contentToken)) return false
            val errorCount = (errorDownloadMap[chapter.primaryStr()] ?: 0) +
                if (error is ConcurrentException) 0 else 1
            if (!downloads.finish(ticket, Result.failure(error), retryManual = errorCount < 3) {
                errorDownloadMap[chapter.primaryStr()] = errorCount
            }) return false
            synchronized(ReadBook) {
                if (ReadBook.book?.bookUrl == requestBook.bookUrl && BookHelp.isContentSaveCurrent(contentToken)) {
                    ReadBook.downloadFailChapters[chapter.index] =
                        (ReadBook.downloadFailChapters[chapter.index] ?: 0) + 1
                }
            }
            if (errorCount >= 3) AppLog.put("下载${requestBook.name}-${chapter.title}失败\n${error.localizedMessage}", error)
            return true
        }

        /** Start one explicit cache operation. All four download entry points share downloads. */
        @Synchronized
        fun download(scope: CoroutineScope, context: CoroutineContext) {
            val requestBook = book
            val source = bookSource
            if (source.supportContentBatch()) {
                val chapters = downloads.waitingIndexes().asSequence()
                    .filter { downloads.canBatch(it) }
                    .mapNotNull { appDb.bookChapterDao.getChapter(requestBook.bookUrl, it) }
                    .filter { !it.isVolume && !BookHelp.hasContent(requestBook, it) }
                    .filterNot { needsResourceRefresh(requestBook, it) }
                    .take(source.contentBatchSize()).toList()
                if (chapters.size >= 2) {
                    val tickets = downloads.claimBatch(chapters.map { it.index }, manual = true)
                    if (tickets.size >= 2) {
                        startManual(scope, context, tickets) {
                            runBatch(source, requestBook, chapters, tickets)
                        }
                        return
                    }
                    tickets.forEach { downloads.finish(it) }
                }
            }
            val index = downloads.waitingIndexes().firstOrNull() ?: return onFinally()
            val chapter = appDb.bookChapterDao.getChapter(requestBook.bookUrl, index)
            val skip = synchronized(downloads) {
                (chapter == null || chapter.isVolume ||
                    (!needsResourceRefresh(requestBook, chapter) && BookHelp.hasImageContent(requestBook, chapter)))
                    .also { if (it) downloads.discardWaiting(index) }
            }
            if (skip) {
                if (chapter?.isVolume == true) postEvent(EventBus.SAVE_CONTENT, Pair(requestBook, chapter))
                return onFinally()
            }
            if (chapter == null) return onFinally()
            val ticket = downloads.claimManual(index) ?: return
            var contentToken = BookHelp.contentSaveToken(requestBook, chapter)
            val refreshingResources = needsResourceRefresh(requestBook, chapter)
            startManual(scope, context, listOf(ticket)) {
                try {
                    val content = if (refreshingResources) {
                        resourceRefreshMutex.withLock {
                            refreshBookResources(source, requestBook, listOf(chapter), onPublished = { accepted ->
                                val refreshed = accepted.single()
                                contentToken = refreshed.token
                                onSuccess(ticket, requestBook, refreshed.chapter, refreshed.content,
                                    notifyReader = true, contentToken = refreshed.token)
                            }) {
                                downloads.isCurrent(ticket)
                            }.single().content
                        }
                    } else {
                        val content = BookHelp.getContent(requestBook, chapter)
                            ?: WebBook.getContentAwait(source, requestBook, chapter)
                        BookHelp.saveImages(source, requestBook, chapter, content, 1)
                        content
                    }
                    val currentContent = BookHelp.getContent(requestBook, chapter) ?: content
                    if (!refreshingResources) onSuccess(ticket, requestBook, chapter, currentContent,
                        notifyReader = true, contentToken = contentToken)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    delay(1000)
                    if (onError(ticket, requestBook, chapter, e, contentToken) && !refreshingResources) {
                        downloadFinish(requestBook, chapter, "获取正文失败\n${e.localizedMessage}",
                            contentToken = contentToken)
                    }
                }
            }
        }

        private fun startManual(
            scope: CoroutineScope,
            context: CoroutineContext,
            tickets: List<ChapterDownloadState.Ticket>,
            block: suspend () -> Unit,
        ) {
            Coroutine.async(scope, context, start = CoroutineStart.LAZY, executeContext = context) {
                block()
            }.apply {
                tasks.add(this)
                // Runs even when cancellation precedes the lazy block or the dispatcher closes.
                invokeOnCompletion {
                    tickets.forEach { downloads.finish(it) }
                    tasks.delete(this)
                    onFinally()
                }
            }.start()
        }

        private suspend fun runBatch(
            source: BookSource,
            requestBook: Book,
            chapters: List<BookChapter>,
            tickets: List<ChapterDownloadState.Ticket>,
        ) {
            val byIndex = chapters.associateBy { it.index }
            val claimed = tickets.map { byIndex.getValue(it.index) }
            val contentTokens = claimed.associate { it.index to BookHelp.contentSaveToken(requestBook, it) }
            try {
                try {
                    WebBook.getContentBatchAwait(source, requestBook, claimed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A script may have saved some chapters before failing. Keep that progress.
                    AppLog.put("《${requestBook.name}》批量下载失败,未完成章节退回单章下载\n${e.localizedMessage}", e)
                }
                for (ticket in tickets) {
                    currentCoroutineContext().ensureActive()
                    val chapter = byIndex.getValue(ticket.index)
                    val contentToken = contentTokens.getValue(ticket.index)
                    if (!BookHelp.isContentSaveCurrent(contentToken)) continue
                    val content = BookHelp.getContent(requestBook, chapter)
                    if (content.isNullOrBlank()) {
                        downloads.finish(ticket, fallback = true)
                        continue
                    }
                    try {
                        BookHelp.saveImages(source, requestBook, chapter, content, 1)
                        val currentContent = BookHelp.getContent(requestBook, chapter) ?: content
                        onSuccess(ticket, requestBook, chapter, currentContent, notifyReader = true,
                            contentToken = contentToken)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        downloads.finish(ticket, fallback = true)
                    }
                }
            } finally {
                // Identity checks make this safe after partial success and after cancellation.
                tickets.forEach { downloads.finish(it) }
                onFinally()
            }
        }

        /** Reading prefetch may borrow queued manual work, but must restore it on cancellation. */
        suspend fun downloadBatchAwait(chapters: List<BookChapter>): List<BookChapter> {
            // A previous chunk may have released this model. Register and claim atomically,
            // so a foreground reader cannot acquire a different model for the same book.
            val (model, tickets) = synchronized(CacheBook) {
                val current = cacheBookMap.getOrPut(book.bookUrl) { this }
                current to current.downloads.claimBatch(chapters
                    .filterNot { current.needsResourceRefresh(current.book, it) }.map { it.index }, manual = false)
            }
            if (tickets.size < 2) {
                tickets.forEach { model.downloads.finish(it) }
                model.onFinally()
                return chapters
            }
            val requestBook = model.book
            model.runBatch(model.bookSource, requestBook, chapters, tickets)
            return chapters.filter { BookHelp.getContent(requestBook, it).isNullOrBlank() }
        }

        suspend fun downloadAwait(chapter: BookChapter): String {
            while (true) {
                currentCoroutineContext().ensureActive()
                var contentToken = BookHelp.contentSaveToken(book, chapter)
                BookHelp.getContent(book, chapter)?.takeUnless { needsResourceRefresh(book, chapter) }?.let {
                    if (!BookHelp.isContentSaveCurrent(contentToken)) return@let
                    onFinally()
                    return it
                }
                val (model, claim) = synchronized(CacheBook) {
                    val current = cacheBookMap.getOrPut(book.bookUrl) { this }
                    current to current.downloads.claimRead(chapter.index)
                }
                val (ticket, owner) = claim
                val requestBook = model.book
                if (!owner) {
                    // Cancelling a waiter does not cancel the independent producer/result.
                    val result = ticket.result.await() ?: continue
                    if (!BookHelp.isContentSaveCurrent(contentToken)) continue
                    if (model.needsResourceRefresh(requestBook, chapter)) continue
                    return result.fold(
                        { BookHelp.getContent(requestBook, chapter) ?: it },
                        { BookHelp.getContent(requestBook, chapter) ?: "获取正文失败\n${it.localizedMessage}" },
                    )
                }
                val refreshingResources = model.needsResourceRefresh(requestBook, chapter)
                var published = false
                try {
                    // The previous owner may have saved content between our first read and claim.
                    val content = if (refreshingResources) {
                        model.resourceRefreshMutex.withLock {
                            refreshBookResources(model.bookSource, requestBook, listOf(chapter), onPublished = { accepted ->
                                val refreshed = accepted.single()
                                contentToken = refreshed.token
                                published = model.onSuccess(ticket, requestBook, refreshed.chapter, refreshed.content,
                                    notifyReader = true, contentToken = refreshed.token)
                            }) {
                                model.downloads.isCurrent(ticket)
                            }.single().content
                        }
                    } else BookHelp.getContent(requestBook, chapter)
                        ?: WebBook.getContentAwait(model.bookSource, requestBook, chapter)
                    val currentContent = BookHelp.getContent(requestBook, chapter) ?: content
                    if (refreshingResources) {
                        if (!published) continue
                    } else if (!model.onSuccess(ticket, requestBook, chapter, currentContent,
                            notifyReader = false, contentToken = contentToken)) continue
                    return currentContent
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!model.onError(ticket, requestBook, chapter, e, contentToken)) continue
                    if (refreshingResources) BookHelp.getContent(requestBook, chapter)?.let { return it }
                    return "获取正文失败\n${e.localizedMessage}"
                } finally {
                    model.downloads.finish(ticket)
                    model.onFinally()
                }
            }
        }

        fun download(
            scope: CoroutineScope,
            chapter: BookChapter,
            semaphore: Semaphore?,
            resetPageOffset: Boolean = false,
            readPositionVersion: Long? = null,
        ) {
            val requestBook = book
            val contentToken = BookHelp.contentSaveToken(requestBook, chapter)
            Coroutine.async(scope, IO, start = CoroutineStart.LAZY, executeContext = IO, semaphore = semaphore) {
                downloadAwait(chapter)
            }.onSuccess { content ->
                downloadFinish(requestBook, chapter, content, resetPageOffset,
                    readPositionVersion = readPositionVersion, contentToken = contentToken)
            }.onCancel {
                downloadFinish(requestBook, chapter, "download canceled", resetPageOffset,
                    canceled = true, readPositionVersion = readPositionVersion, contentToken = contentToken)
            }.onError {
                downloadFinish(requestBook, chapter, "获取正文失败\n${it.localizedMessage}",
                    resetPageOffset, readPositionVersion = readPositionVersion, contentToken = contentToken)
            }.start()
        }

        private fun downloadFinish(
            requestBook: Book,
            chapter: BookChapter,
            content: String,
            resetPageOffset: Boolean = false,
            canceled: Boolean = false,
            readPositionVersion: Long? = null,
            contentToken: ContentSaveToken,
        ) {
            if (ReadBook.book?.bookUrl == requestBook.bookUrl && BookHelp.isContentSaveCurrent(contentToken)) {
                ReadBook.contentLoadFinish(
                    requestBook, chapter, content,
                    resetPageOffset = resetPageOffset,
                    canceled = canceled,
                    readPositionVersion = readPositionVersion,
                    contentToken = contentToken,
                )
            }
        }
    }
}
