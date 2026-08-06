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
        cacheBookMap.clear()
        successDownloadSet.clear()
        errorDownloadMap.clear()
    }

    fun setWorkingState(value: Boolean) {
        workingState.value = value
    }

    suspend fun startProcessJob(context: CoroutineContext) = mutex.withLock {
        setWorkingState(true)
        flow {
            while (currentCoroutineContext().isActive && cacheBookMap.isNotEmpty()) {
                var emitted = false

                cacheBookMap.forEach { (_, model) ->
                    if (!model.isLoading()) {
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

    val successDownloadSet = linkedSetOf<String>()
    val errorDownloadMap = hashMapOf<String, Int>()

    class CacheBookModel(var bookSource: BookSource, var book: Book) {

        private val waitDownloadSet = linkedSetOf<Int>()
        private val onDownloadSet = linkedSetOf<Int>()
        /** 批量下载没能拿到正文的章节,退回单章流程,不再进入后续批次 */
        private val batchFallbackSet = linkedSetOf<Int>()
        private val tasks = CompositeCoroutine()
        private var isStopped = false
        private var waitingRetry = false
        private var isLoading = false

        val waitCount get() = waitDownloadSet.size
        val onDownloadCount get() = onDownloadSet.size

        init {
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        }

        @Synchronized
        fun isRun(): Boolean {
            return waitDownloadSet.isNotEmpty() || onDownloadSet.isNotEmpty() || isLoading
        }

        @Synchronized
        fun isStop(): Boolean {
            return isStopped || (!isRun() && !waitingRetry)
        }

        @Synchronized
        fun isLoading(): Boolean {
            return isLoading
        }

        @Synchronized
        fun setLoading() {
            isLoading = true
        }

        @Synchronized
        fun stop() {
            waitDownloadSet.clear()
            batchFallbackSet.clear()
            tasks.clear()
            isStopped = true
            isLoading = false
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        }

        @Synchronized
        fun addDownload(start: Int, end: Int) {
            isStopped = false
            for (i in start..end) {
                if (!onDownloadSet.contains(i)) {
                    waitDownloadSet.add(i)
                }
            }
            cacheBookMap[book.bookUrl] = this
            isLoading = false
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        }

        @Synchronized
        private fun onSuccess(chapter: BookChapter) {
            onDownloadSet.remove(chapter.index)
            successDownloadSet.add(chapter.primaryStr())
            errorDownloadMap.remove(chapter.primaryStr())
        }

        @Synchronized
        private fun onPreError(chapter: BookChapter, error: Throwable) {
            waitingRetry = true
            if (error !is ConcurrentException) {
                errorDownloadMap[chapter.primaryStr()] =
                    (errorDownloadMap[chapter.primaryStr()] ?: 0) + 1
            }
            onDownloadSet.remove(chapter.index)
        }

        @Synchronized
        private fun onPostError(chapter: BookChapter, error: Throwable) {
            //重试3次
            if ((errorDownloadMap[chapter.primaryStr()] ?: 0) < 3 && !isStopped) {
                waitDownloadSet.add(chapter.index)
            } else {
                AppLog.put(
                    "下载${book.name}-${chapter.title}失败\n${error.localizedMessage}",
                    error
                )
            }
            waitingRetry = false
        }

        @Synchronized
        private fun onReadError(chapter: BookChapter, error: Throwable) {
            if (error !is ConcurrentException) {
                errorDownloadMap[chapter.primaryStr()] =
                    (errorDownloadMap[chapter.primaryStr()] ?: 0) + 1
            }
            onDownloadSet.remove(chapter.index)
        }

        @Synchronized
        private fun onCancel(index: Int) {
            onDownloadSet.remove(index)
            if (!isStopped) waitDownloadSet.add(index)
        }

        @Synchronized
        private fun onReadCancel(index: Int) {
            onDownloadSet.remove(index)
        }

        @Synchronized
        private fun onFinally() {
            if (waitDownloadSet.isEmpty() && onDownloadSet.isEmpty()) {
                cacheBookMap.remove(book.bookUrl)
            }
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        }

        /**
         * 从待下载列表内取第一条下载
         */
        @Synchronized
        fun download(scope: CoroutineScope, context: CoroutineContext) {
            if (bookSource.supportContentBatch() && downloadBatch(scope, context)) {
                return
            }
            val chapterIndex = waitDownloadSet.firstOrNull()
            if (chapterIndex == null) {
                if (!isLoading && onDownloadSet.isEmpty()) {
                    cacheBookMap.remove(book.bookUrl)
                }
                return
            }
            if (onDownloadSet.contains(chapterIndex)) {
                waitDownloadSet.remove(chapterIndex)
                return
            }
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: let {
                waitDownloadSet.remove(chapterIndex)
                return
            }
            if (chapter.isVolume) {
                /** 修正下载计数 */
                postEvent(EventBus.SAVE_CONTENT, Pair(book, chapter))
                waitDownloadSet.remove(chapterIndex)
                return
            }
            if (BookHelp.hasImageContent(book, chapter)) {
                waitDownloadSet.remove(chapterIndex)
                return
            }
            waitDownloadSet.remove(chapterIndex)
            onDownloadSet.add(chapterIndex)
            if (BookHelp.hasContent(book, chapter)) {
                Coroutine.async(scope, context, executeContext = context) {
                    BookHelp.getContent(book, chapter)?.let {
                        BookHelp.saveImages(bookSource, book, chapter, it, 1)
                    }
                }.onSuccess {
                    onSuccess(chapter)
                }.onError {
                    onPreError(chapter, it)
                    //出现错误等待一秒后重新加入待下载列表
                    delay(1000)
                    onPostError(chapter, it)
                }.onCancel {
                    onCancel(chapterIndex)
                }.onFinally {
                    onFinally()
                }.let {
                    tasks.add(it)
                }
                return
            }
            WebBook.getContent(
                scope,
                bookSource,
                book,
                chapter,
                context = context,
                start = CoroutineStart.LAZY,
                executeContext = context
            ).onSuccess { content ->
                val imageContent = BookHelp.getContent(book, chapter) ?: content
                BookHelp.saveImages(bookSource, book, chapter, imageContent, 1)
                val currentContent = BookHelp.getContent(book, chapter) ?: imageContent
                onSuccess(chapter)
                downloadFinish(chapter, currentContent)
            }.onError {
                onPreError(chapter, it)
                //出现错误等待一秒后重新加入待下载列表
                delay(1000)
                onPostError(chapter, it)
                downloadFinish(chapter, "获取正文失败\n${it.localizedMessage}")
            }.onCancel {
                onCancel(chapterIndex)
            }.onFinally {
                onFinally()
            }.apply {
                tasks.add(this)
            }.start()
        }

        /**
         * 批量下载:一次把多章交给书源的 contentBatch 规则处理。
         * 成功启动批次返回 true;可批量的章节不足两章时返回 false,由单章流程接手。
         */
        @Synchronized
        private fun downloadBatch(scope: CoroutineScope, context: CoroutineContext): Boolean {
            val batchSize = bookSource.contentBatchSize()
            if (batchSize <= 1) return false
            val batchChapters = arrayListOf<BookChapter>()
            for (chapterIndex in waitDownloadSet.toList()) {
                if (batchChapters.size >= batchSize) break
                if (batchFallbackSet.contains(chapterIndex)) continue
                if (onDownloadSet.contains(chapterIndex)) {
                    waitDownloadSet.remove(chapterIndex)
                    continue
                }
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                if (chapter == null) {
                    waitDownloadSet.remove(chapterIndex)
                    continue
                }
                //卷名和已缓存章节交给单章流程,批量只处理需要联网的正文
                if (chapter.isVolume || BookHelp.hasContent(book, chapter)) continue
                batchChapters.add(chapter)
            }
            if (batchChapters.size < 2) return false
            //候选已排除进行中的章节,且与领取同处一把锁内,这里必然全部领取成功
            val claimed = BatchChapterClaim.claim(batchChapters, onDownloadSet, waitDownloadSet)
            Coroutine.async(scope, context, executeContext = context) {
                WebBook.getContentBatchAwait(bookSource, book, claimed)
            }.onSuccess { missingChapters ->
                val missingIndexes = missingChapters.mapTo(hashSetOf()) { it.index }
                claimed.forEach { chapter ->
                    if (missingIndexes.contains(chapter.index)) return@forEach
                    val content = BookHelp.getContent(book, chapter)
                    if (content.isNullOrEmpty()) {
                        //书源声称已回存但读不到内容,同样退回单章流程
                        missingIndexes.add(chapter.index)
                        return@forEach
                    }
                    BookHelp.saveImages(bookSource, book, chapter, content, 1)
                    onSuccess(chapter)
                    downloadFinish(chapter, content)
                }
                onBatchMissing(claimed.filter { missingIndexes.contains(it.index) })
            }.onError { error ->
                AppLog.put("《${book.name}》批量下载失败,退回单章下载\n${error.localizedMessage}", error)
                onBatchMissing(claimed)
            }.onCancel {
                onBatchCancel(claimed)
            }.onFinally {
                onFinally()
            }.apply {
                tasks.add(this)
            }.start()
            return true
        }

        /**
         * 书源没有回存的章节退回单章流程,并标记不再参与后续批次,避免反复空跑
         */
        @Synchronized
        private fun onBatchMissing(chapters: List<BookChapter>) {
            chapters.forEach { chapter ->
                onDownloadSet.remove(chapter.index)
                if (!isStopped) {
                    batchFallbackSet.add(chapter.index)
                    waitDownloadSet.add(chapter.index)
                }
            }
        }

        @Synchronized
        private fun onBatchCancel(chapters: List<BookChapter>) {
            chapters.forEach { chapter ->
                onDownloadSet.remove(chapter.index)
                if (!isStopped) waitDownloadSet.add(chapter.index)
            }
        }

        /**
         * 阅读预下载用的批量下载。
         * 返回书源未回存的章节,调用方按单章流程兜底。
         */
        suspend fun downloadBatchAwait(chapters: List<BookChapter>): List<BookChapter> {
            if (chapters.isEmpty()) return emptyList()
            //只领取没被手动缓存等任务占用的章节,别人在下的不碰
            val claimed = synchronized(this) {
                BatchChapterClaim.claim(chapters, onDownloadSet, waitDownloadSet)
            }
            if (claimed.size < 2) {
                synchronized(this) { BatchChapterClaim.release(claimed, onDownloadSet) }
                return chapters
            }
            //已走完成回调的章节标记已被释放,finally 不能重复归还
            val completed = hashSetOf<Int>()
            val unclaimed = chapters.filterNot { chapter ->
                claimed.any { it.index == chapter.index }
            }
            try {
                val missingIndexes = WebBook.getContentBatchAwait(bookSource, book, claimed)
                    .mapTo(hashSetOf()) { it.index }
                claimed.forEach { chapter ->
                    if (missingIndexes.contains(chapter.index)) return@forEach
                    val content = BookHelp.getContent(book, chapter)
                    if (content.isNullOrEmpty()) {
                        missingIndexes.add(chapter.index)
                        return@forEach
                    }
                    BookHelp.saveImages(bookSource, book, chapter, content, 1)
                    onSuccess(chapter)
                    completed.add(chapter.index)
                    ReadBook.downloadedChapters.add(chapter.index)
                    ReadBook.downloadFailChapters.remove(chapter.index)
                    downloadFinish(chapter, content)
                }
                return unclaimed + claimed.filter { missingIndexes.contains(it.index) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("《${book.name}》批量预下载失败,退回单章下载\n${e.localizedMessage}", e)
                return chapters
            } finally {
                synchronized(this) {
                    BatchChapterClaim.release(
                        claimed.filterNot { completed.contains(it.index) },
                        onDownloadSet
                    )
                }
                postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
            }
        }

        suspend fun downloadAwait(chapter: BookChapter): String {
            synchronized(this) {
                onDownloadSet.add(chapter.index)
                waitDownloadSet.remove(chapter.index)
            }
            try {
                val content = WebBook.getContentAwait(bookSource, book, chapter)
                val currentContent = BookHelp.getContent(book, chapter) ?: content
                onSuccess(chapter)
                ReadBook.downloadedChapters.add(chapter.index)
                ReadBook.downloadFailChapters.remove(chapter.index)
                return currentContent
            } catch (e: CancellationException) {
                onReadCancel(chapter.index)
                throw e
            } catch (e: Exception) {
                onReadError(chapter, e)
                ReadBook.downloadFailChapters[chapter.index] =
                    (ReadBook.downloadFailChapters[chapter.index] ?: 0) + 1
                return "获取正文失败\n${e.localizedMessage}"
            } finally {
                postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
            }
        }

        @Synchronized
        fun download(
            scope: CoroutineScope,
            chapter: BookChapter,
            semaphore: Semaphore?,
            resetPageOffset: Boolean = false
        ) {
            if (onDownloadSet.contains(chapter.index)) {
                return
            }
            onDownloadSet.add(chapter.index)
            waitDownloadSet.remove(chapter.index)
            WebBook.getContent(
                scope,
                bookSource,
                book,
                chapter,
                start = CoroutineStart.LAZY,
                executeContext = IO,
                semaphore = semaphore
            ).onSuccess { content ->
                val currentContent = BookHelp.getContent(book, chapter) ?: content
                onSuccess(chapter)
                ReadBook.downloadedChapters.add(chapter.index)
                ReadBook.downloadFailChapters.remove(chapter.index)
                downloadFinish(chapter, currentContent, resetPageOffset)
            }.onError {
                onReadError(chapter, it)
                ReadBook.downloadFailChapters[chapter.index] =
                    (ReadBook.downloadFailChapters[chapter.index] ?: 0) + 1
                downloadFinish(chapter, "获取正文失败\n${it.localizedMessage}", resetPageOffset)
            }.onCancel {
                onReadCancel(chapter.index)
                downloadFinish(chapter, "download canceled", resetPageOffset, true)
            }.onFinally {
                postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
            }.start()
        }

        private fun downloadFinish(
            chapter: BookChapter,
            content: String,
            resetPageOffset: Boolean = false,
            canceled: Boolean = false
        ) {
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.contentLoadFinish(
                    book, chapter, content,
                    resetPageOffset = resetPageOffset,
                    canceled = canceled
                )
            }
        }

    }

}
