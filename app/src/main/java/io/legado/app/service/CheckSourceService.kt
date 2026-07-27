package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.CheckSource
import io.legado.app.model.CheckSourceResult
import io.legado.app.model.CheckSourceStatus
import io.legado.app.model.Debug
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.mapParallel
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.htmlunit.corejs.javascript.WrappedException
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import kotlin.math.min

internal fun parseCheckSourceEndpoint(domain: String): Pair<String, Int>? {
    val rawUrl = domain.substringBefore('#')
    val uri = kotlin.runCatching { URI(rawUrl) }.getOrNull() ?: return null
    if (uri.rawAuthority.isNullOrBlank()) return null
    val url = rawUrl.toHttpUrlOrNull() ?: return null
    return url.host to url.port
}

/**
 * 校验书源
 */
class CheckSourceService : BaseService() {
    private data class CheckTarget(
        val selected: BookSourcePart,
        val original: BookSource,
        val source: BookSource,
    )

    private data class CheckOutcome(
        val succeeded: Boolean,
        val message: String,
    )

    private var threadCount = AppConfig.threadCount
    private var searchCoroutine =
        Executors.newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    private var notificationMsg = appCtx.getString(R.string.service_starting)
    private var checkJob: Job? = null
    private var checkSessionId: Long? = null
    @Volatile
    private var latestStartId = 0
    @Volatile
    private var checkJobFinished = false
    @Volatile
    private var serviceDestroyed = false
    private var originSize = 0
    private var finishCount = 0

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
            .setSmallIcon(R.drawable.ic_network_check)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.check_book_source))
            .setContentIntent(
                activityPendingIntent<BookSourceActivity>("activity")
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            IntentAction.start -> {
                val sessionId = intent.getLongExtra(CheckSource.EXTRA_SESSION_ID, 0L)
                val selectedSources = IntentData.get<List<BookSourcePart>>(
                    intent.getStringExtra(CheckSource.EXTRA_SELECTED_SOURCES_KEY)
                )
                if (sessionId > 0L && selectedSources != null) {
                    check(selectedSources, sessionId, startId)
                } else {
                    if (sessionId > 0L) {
                        finishCheckSession(sessionId)
                    }
                    stopSelf(startId)
                }
            }

            IntentAction.resume -> {
                if (checkJob?.isActive == true) upNotification() else stopSelf(startId)
            }

            IntentAction.stop -> {
                val sessionId = intent.getLongExtra(CheckSource.EXTRA_SESSION_ID, 0L)
                if (sessionId > 0L && Debug.isChecking(sessionId)) {
                    if (checkSessionId == null) {
                        finishCheckSession(sessionId)
                    } else {
                        checkJob?.cancel()
                    }
                    stopSelf(startId)
                } else if (checkJob?.isActive != true) {
                    stopSelf(startId)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        checkJob?.cancel()
        searchCoroutine.close()
        notificationManager.cancel(NotificationId.CheckSourceService)
        serviceDestroyed = true
        finishCheckSessionIfComplete()
    }

    private fun check(
        selectedSources: List<BookSourcePart>,
        sessionId: Long,
        startId: Int,
    ) {
        if (checkJob?.isActive == true) {
            toastOnUi("已有书源在校验,等完成后再试")
            return
        }
        if (!Debug.markCheckServiceStarted(sessionId)) {
            if (!Debug.isChecking) stopSelf(startId)
            return
        }
        checkSessionId = sessionId
        notificationBuilder.clearActions()
        notificationBuilder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            servicePendingIntent<CheckSourceService>(IntentAction.stop, sessionId.hashCode()) {
                putExtra(CheckSource.EXTRA_SESSION_ID, sessionId)
            }
        )
        val job = lifecycleScope.launch(searchCoroutine) {
            flow {
                for (selected in selectedSources) {
                    val source = appDb.bookSourceDao.getBookSource(selected.bookSourceUrl)
                    when {
                        source == null -> Debug.recordCheckResult(
                            sessionId,
                            selected.bookSourceUrl,
                            CheckSourceResult(
                                CheckSourceStatus.NOT_COMPLETED,
                                "书源已删除",
                            ),
                        )

                        source.lastUpdateTime != selected.lastUpdateTime ->
                            Debug.recordCheckResult(
                                sessionId,
                                selected.bookSourceUrl,
                                CheckSourceResult(
                                    CheckSourceStatus.NOT_COMPLETED,
                                    "书源已变更，校验结果未写回",
                                ),
                            )

                        else -> emit(CheckTarget(selected, source.copy(), source))
                    }
                }
            }.onStart {
                originSize = selectedSources.size
                finishCount = 0
                notificationMsg = getString(R.string.progress_show, "", 0, originSize)
                upNotification()
            }.mapParallel(threadCount) {
                it to checkSource(it.source, sessionId)
            }.onEach { (target, outcome) ->
                val (selected, original, source) = target
                finishCount++
                notificationMsg = getString(
                    R.string.progress_show,
                    source.bookSourceName,
                    finishCount,
                    originSize
                )
                upNotification()
                val updated = appDb.bookSourceDao.updateCheckResult(
                    source.bookSourceUrl,
                    source.bookSourceGroup,
                    source.bookSourceComment,
                    source.respondTime,
                    selected.lastUpdateTime,
                    original.bookSourceGroup,
                    original.bookSourceComment,
                    original.respondTime,
                )
                if (updated == 0) {
                    val detail = "校验结果未写回：书源已变更或删除"
                    Debug.updateCheckMessage(
                        sessionId,
                        source.bookSourceUrl,
                        detail,
                    )
                    Debug.recordCheckResult(
                        sessionId,
                        source.bookSourceUrl,
                        CheckSourceResult(CheckSourceStatus.NOT_COMPLETED, detail),
                    )
                } else {
                    Debug.updateFinalMessage(sessionId, source.bookSourceUrl, outcome.message)
                    val status = if (outcome.succeeded) {
                        CheckSourceStatus.PASSED
                    } else {
                        CheckSourceStatus.FAILED
                    }
                    val detail = if (outcome.succeeded) {
                        ""
                    } else {
                        listOf(
                            source.getInvalidGroupNames(),
                            source.bookSourceComment
                                ?.lineSequence()
                                ?.firstOrNull { it.startsWith("// Error: ") }
                                .orEmpty(),
                            outcome.message,
                        ).filter { it.isNotEmpty() }.distinct().joinToString(" | ")
                    }
                    Debug.recordCheckResult(
                        sessionId,
                        source.bookSourceUrl,
                        CheckSourceResult(status, detail),
                    )
                }
            }.collect()
        }
        checkJob = job
        job.invokeOnCompletion {
            stopSelf(latestStartId)
            checkJobFinished = true
            finishCheckSessionIfComplete()
        }
    }

    private fun finishCheckSessionIfComplete() {
        if (checkJobFinished && serviceDestroyed) {
            checkSessionId?.let(::finishCheckSession)
        }
    }

    private fun finishCheckSession(sessionId: Long) {
        if (Debug.finishChecking(sessionId)) {
            postEvent(EventBus.CHECK_SOURCE_DONE, sessionId)
        }
    }

    private suspend fun checkSource(source: BookSource, sessionId: Long): CheckOutcome {
        var resultMessage = "校验成功"
        var succeeded = true
        kotlin.runCatching {
            withTimeout(CheckSource.timeout) {
                doCheckSource(source, sessionId)
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            succeeded = false
            when (it) {
                is TimeoutCancellationException -> source.addGroup("校验超时")
                is ScriptException, is WrappedException -> source.addGroup("js失效")
                !is NoStackTraceException -> source.addGroup("网站失效")
            }
            if (CheckSource.wSourceComment) {
                source.addErrorComment(it)
            }
            resultMessage = "校验失败:${it.localizedMessage}"
        }
        source.respondTime = Debug.getRespondTime(sessionId, source.bookSourceUrl, succeeded)
        return CheckOutcome(succeeded, resultMessage)
    }

    private suspend fun isDomainReachable(endpoint: Pair<String, Int>): Boolean {
        return kotlin.runCatching {
            withTimeout(2000) {
                val (host, port) = endpoint
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 1600)
                    true
                }
            }
        }.getOrDefault(false)
    }

    private suspend fun doCheckSource(source: BookSource, sessionId: Long) {
        Debug.startChecking(sessionId, source)
        source.removeInvalidGroups()
        if (CheckSource.wSourceComment) {
            source.removeErrorComment()
        }
        //检测源地址可访问性
        if (CheckSource.checkDomain) {
            val domain = source.bookSourceUrl
            val endpoint = parseCheckSourceEndpoint(domain)
            if (endpoint == null) {
                throw NoStackTraceException("源地址不是http链接")
            } else if (isDomainReachable(endpoint)) {
                source.removeGroup("域名失效")
            } else {
                source.addGroup("域名失效")
                throw NoStackTraceException("源地址不可访问")
            }
        }
        //校验搜索书籍
        if (CheckSource.checkSearch) {
            val searchWord = source.getCheckKeyword(CheckSource.keyword)
            if (source.isJsSource() || !source.searchUrl.isNullOrBlank()) {
                source.removeGroup("搜索链接规则为空")
                val searchBooks = WebBook.searchBookAwait(source, searchWord)
                if (searchBooks.isEmpty()) {
                    source.addGroup("搜索失效")
                } else {
                    source.removeGroup("搜索失效")
                    checkBook(searchBooks.first().toBook(), source)
                }
            } else {
                source.addGroup("搜索链接规则为空")
            }
        }
        //校验发现书籍
        if (CheckSource.checkDiscovery && !source.exploreUrl.isNullOrBlank()) {
            val url = source.exploreKinds().firstOrNull {
                !it.url.isNullOrBlank()
            }?.url
            if (url.isNullOrBlank()) {
                source.addGroup("发现规则为空")
            } else {
                source.removeGroup("发现规则为空")
                val exploreBooks = WebBook.exploreBookAwait(source, url)
                if (exploreBooks.isEmpty()) {
                    source.addGroup("发现失效")
                } else {
                    source.removeGroup("发现失效")
                    checkBook(exploreBooks.first().toBook(), source, false)
                }
            }
        }
        val finalCheckMessage = source.getInvalidGroupNames()
        if (finalCheckMessage.isNotBlank()) {
            throw NoStackTraceException(finalCheckMessage)
        }
    }

    /**
     *校验书源的详情目录正文
     */
    private suspend fun checkBook(book: Book, source: BookSource, isSearchBook: Boolean = true) {
        kotlin.runCatching {
            if (!CheckSource.checkInfo) {
                return
            }
            //校验详情
            if (book.tocUrl.isBlank()) {
                WebBook.getBookInfoAwait(source, book)
            }
            if (!CheckSource.checkCategory || source.bookSourceType == BookSourceType.file) {
                return
            }
            //校验目录
            val chapterSelection = selectCheckSourceChapter(
                chapters = WebBook.getChapterListAwait(source, book).getOrThrow(),
                emptyMessage = getString(R.string.chapter_list_empty),
            )
            if (!CheckSource.checkContent) {
                return
            }
            //校验正文
            WebBook.getContentAwait(
                bookSource = source,
                book = book,
                bookChapter = chapterSelection.chapter,
                nextChapterUrl = chapterSelection.nextChapterUrl,
                needSave = false
            )
        }.onFailure {
            val bookType = if (isSearchBook) "搜索" else "发现"
            when (it) {
                is ContentEmptyException -> source.addGroup("${bookType}正文失效")
                is TocEmptyException -> source.addGroup("${bookType}目录失效")
                else -> throw it
            }
        }.onSuccess {
            val bookType = if (isSearchBook) "搜索" else "发现"
            source.removeGroup("${bookType}目录失效")
            source.removeGroup("${bookType}正文失效")
        }
    }

    private fun upNotification() {
        notificationBuilder.setContentText(notificationMsg)
        notificationBuilder.setProgress(originSize, finishCount, false)
        checkSessionId?.let {
            postEvent(EventBus.CHECK_SOURCE, it to notificationMsg)
        }
        notificationManager.notify(NotificationId.CheckSourceService, notificationBuilder.build())
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationMsg)
        notificationBuilder.setProgress(originSize, finishCount, false)
        checkSessionId?.let {
            postEvent(EventBus.CHECK_SOURCE, it to notificationMsg)
        }
        startForeground(NotificationId.CheckSourceService, notificationBuilder.build())
    }

}
