package io.legado.app.service

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.audio.AudioCacheManager
import io.legado.app.help.audio.AudioCachePolicy
import io.legado.app.help.book.isAudio
import io.legado.app.help.config.AppConfig
import io.legado.app.model.AudioCacheKey
import io.legado.app.model.AudioCacheStateChanged
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startForegroundServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import splitties.systemservices.notificationManager
import java.util.ArrayDeque

class AudioCacheService : BaseService() {

    companion object {
        private const val ACTION_CACHE_RANGE = "audioCacheRange"
        private const val EXTRA_BOOK_URL = "bookUrl"
        private const val EXTRA_START_INDEX = "start"
        private const val EXTRA_END_INDEX = "end"

        fun start(
            context: Context,
            bookUrl: String,
            startIndex: Int,
            endIndex: Int,
        ): Boolean {
            if (bookUrl.isBlank() || startIndex < 0 || endIndex < startIndex) return false
            context.startForegroundServiceCompat(
                Intent(context, AudioCacheService::class.java).apply {
                    action = ACTION_CACHE_RANGE
                    putExtra(EXTRA_BOOK_URL, bookUrl)
                    putExtra(EXTRA_START_INDEX, startIndex)
                    putExtra(EXTRA_END_INDEX, endIndex)
                }
            )
            return true
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioCacheService::class.java))
        }
    }

    private data class CacheTask(
        val bookUrl: String,
        val startIndex: Int,
        val endIndex: Int,
    )

    private val queueLock = Any()
    private val taskQueue = ArrayDeque<CacheTask>()
    private var workerJob: Job? = null
    private var latestStartId = 0
    private var bookName = ""
    private var doneCount = 0
    private var totalCount = 0
    private var failCount = 0

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.audio_cache_notification_title))
            .setContentIntent(activityPendingIntent<AudioPlayActivity>("audioPlay"))
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.stop),
                servicePendingIntent<AudioCacheService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if (result == START_NOT_STICKY) return result

        synchronized(queueLock) {
            latestStartId = startId
        }
        when (intent?.action) {
            ACTION_CACHE_RANGE -> enqueue(intent, startId)
            IntentAction.stop -> stopAndClear(startId)
            else -> stopIfIdle(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val job = synchronized(queueLock) {
            taskQueue.clear()
            workerJob.also { workerJob = null }
        }
        job?.cancel()
        notificationManager.cancel(NotificationId.AudioCacheService)
        super.onDestroy()
    }

    override fun startForegroundNotification() {
        startForeground(NotificationId.AudioCacheService, buildNotification().build())
    }

    private fun enqueue(intent: Intent, startId: Int) {
        val task = CacheTask(
            bookUrl = intent.getStringExtra(EXTRA_BOOK_URL).orEmpty(),
            startIndex = intent.getIntExtra(EXTRA_START_INDEX, -1),
            endIndex = intent.getIntExtra(EXTRA_END_INDEX, -1),
        )
        if (task.bookUrl.isBlank() || task.startIndex < 0 || task.endIndex < task.startIndex) {
            stopIfIdle(startId)
            return
        }
        synchronized(queueLock) {
            taskQueue.addLast(task)
            startWorkerLocked()
        }
    }

    private fun startWorkerLocked() {
        if (workerJob?.isActive == true) return
        val job = lifecycleScope.launch(IO, start = CoroutineStart.LAZY) {
            drainQueue(currentCoroutineContext()[Job] ?: return@launch)
        }
        workerJob = job
        job.start()
    }

    private suspend fun drainQueue(currentJob: Job) {
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val task = synchronized(queueLock) {
                    if (workerJob !== currentJob || taskQueue.isEmpty()) {
                        if (workerJob === currentJob) workerJob = null
                        null
                    } else {
                        taskQueue.removeFirst()
                    }
                } ?: break
                try {
                    processTask(task)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    AppLog.put("音频缓存任务失败 ${task.bookUrl}\n${error.localizedMessage}", error)
                }
            }
        } finally {
            val stopId = synchronized(queueLock) {
                if (workerJob === currentJob) workerJob = null
                audioCacheIdleStopId(
                    latestStartId = latestStartId,
                    hasWorker = workerJob != null,
                    hasQueuedTask = taskQueue.isNotEmpty(),
                )
            }
            if (stopId != null) stopSelfResult(stopId)
        }
    }

    private suspend fun processTask(task: CacheTask) {
        val treeUri = AppConfig.audioCacheTreeUri
        if (!AudioCacheManager.isCacheDirAvailable(treeUri)) return
        val book = appDb.bookDao.getBook(task.bookUrl) ?: return
        if (!book.isAudio) return
        val source = appDb.bookSourceDao.getBookSource(book.origin) ?: return
        if (!ensureChapterList(source, book)) return
        val chapterCount = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        val range = AudioCachePolicy.normalizeRange(
            task.startIndex,
            task.endIndex,
            chapterCount
        ) ?: return

        bookName = book.name
        doneCount = 0
        failCount = 0
        totalCount = range.count()
        val cachedKeys = AudioCacheManager.listCachedChapterKeys(treeUri, book.bookUrl).toMutableSet()
        updateNotification()

        for (index in range) {
            currentCoroutineContext().ensureActive()
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index)
            if (chapter != null && !chapter.isVolume) {
                val key = AudioCacheKey.from(chapter)
                if (key !in cachedKeys) {
                    val result = AudioCacheManager.cacheChapter(treeUri, source, book, chapter)
                    val error = result.exceptionOrNull()
                    if (error is CancellationException) throw error
                    if (error == null) {
                        cachedKeys.add(key)
                        postEvent(
                            EventBus.AUDIO_CACHE_CHANGED,
                            AudioCacheStateChanged(book.bookUrl, key, true, treeUri)
                        )
                    } else {
                        failCount++
                    }
                }
            }
            doneCount++
            updateNotification()
        }
    }

    private suspend fun ensureChapterList(source: BookSource, book: Book): Boolean {
        if (appDb.bookChapterDao.getChapterCount(book.bookUrl) > 0) return true
        return try {
            if (book.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, book)
                appDb.bookDao.update(book)
            }
            val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.put(
                "音频缓存前加载目录失败 ${book.name}\n${error.localizedMessage}",
                error,
                true
            )
            false
        }
    }

    private fun stopAndClear(startId: Int) {
        val job = synchronized(queueLock) {
            taskQueue.clear()
            workerJob.also { workerJob = null }
        }
        job?.cancel()
        stopSelfResult(startId)
    }

    private fun stopIfIdle(startId: Int) {
        val idle = synchronized(queueLock) {
            workerJob?.isActive != true && taskQueue.isEmpty()
        }
        if (idle) stopSelfResult(startId)
    }

    private fun updateNotification() {
        notificationManager.notify(NotificationId.AudioCacheService, buildNotification().build())
    }

    private fun buildNotification(): NotificationCompat.Builder {
        if (totalCount <= 0) {
            return notificationBuilder
                .setContentText(getString(R.string.service_starting))
                .setProgress(0, 0, true)
        }
        return notificationBuilder
            .setContentText(
                getString(
                    R.string.audio_cache_notification_text,
                    bookName,
                    doneCount,
                    totalCount,
                    failCount
                )
            )
            .setProgress(totalCount, doneCount, false)
    }
}

internal fun audioCacheIdleStopId(
    latestStartId: Int,
    hasWorker: Boolean,
    hasQueuedTask: Boolean,
): Int? = latestStartId.takeIf { !hasWorker && !hasQueuedTask }
