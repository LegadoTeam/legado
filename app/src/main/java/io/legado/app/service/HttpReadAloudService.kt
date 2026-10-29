package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeSharedPreferences
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.htmlunit.corejs.javascript.WrappedException
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal const val MAX_HTTP_TTS_PAUSE_MS = 10_000
internal const val MAX_HTTP_TTS_PLAYER_QUEUE_SIZE = 12
private const val HTTP_TTS_MEDIA_ID_PREFIX = "http-tts:"
private const val HTTP_TTS_PAUSE_MEDIA_ID_PREFIX = "http-tts-pause:"

internal fun normalizeHttpTtsPauseDuration(durationMs: Int): Int {
    return durationMs.coerceIn(0, MAX_HTTP_TTS_PAUSE_MS)
}

internal fun shouldInsertHttpTtsPause(index: Int, lastIndex: Int, durationMs: Int): Boolean {
    return index < lastIndex && normalizeHttpTtsPauseDuration(durationMs) > 0
}

internal fun shouldInsertParagraphPause(itemId: String, nextItemId: String?, durationMs: Int): Boolean =
    nextItemId != null && nextItemId != itemId && normalizeHttpTtsPauseDuration(durationMs) > 0

internal fun hasHttpTtsQueueCapacity(mediaItemCount: Int, newItemCount: Int): Boolean {
    return newItemCount in 1..MAX_HTTP_TTS_PLAYER_QUEUE_SIZE &&
        mediaItemCount in 0..(MAX_HTTP_TTS_PLAYER_QUEUE_SIZE - newItemCount)
}

internal fun httpTtsMediaSessionId(mediaId: String): Long? {
    val sessionAndIndex = when {
        mediaId.startsWith(HTTP_TTS_PAUSE_MEDIA_ID_PREFIX) ->
            mediaId.removePrefix(HTTP_TTS_PAUSE_MEDIA_ID_PREFIX)

        mediaId.startsWith(HTTP_TTS_MEDIA_ID_PREFIX) ->
            mediaId.removePrefix(HTTP_TTS_MEDIA_ID_PREFIX)

        else -> return null
    }
    if (':' !in sessionAndIndex) return null
    return sessionAndIndex.substringBefore(':').toLongOrNull()
}

internal fun generateSilentWavBytes(durationMs: Int): ByteArray {
    val normalizedDuration = normalizeHttpTtsPauseDuration(durationMs)
    val sampleRate = 24_000
    val channelCount = 1
    val bitsPerSample = 16
    val bytesPerSample = bitsPerSample / 8
    val dataSize = sampleRate * channelCount * bytesPerSample * normalizedDuration / 1000
    return ByteBuffer.allocate(44 + dataSize)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(channelCount.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channelCount * bytesPerSample)
            putShort((channelCount * bytesPerSample).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }
        .array()
}

internal fun buildHttpTtsCacheFileName(
    chapterTitle: String,
    sourceUrl: String?,
    speechRate: Int,
    sourceVariable: String,
    loginHeader: String,
    content: String,
): String {
    val chapterKey = MD5Utils.md5Encode16(chapterTitle)
    val cacheIdentity = buildString {
        arrayOf(
            sourceUrl.orEmpty(),
            speechRate.toString(),
            sourceVariable,
            loginHeader,
            content,
        ).forEach { value ->
            append(value.length).append(':').append(value)
        }
    }
    val audioKey = MD5Utils.md5Encode16(cacheIdentity)
    return "${chapterKey}_$audioKey"
}

/**
 * 在线朗读
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build()
    }
    private val ttsFolderPath: String by lazy {
        cacheDir.absolutePath + File.separator + "httpTTS" + File.separator
    }
    private val cache by lazy {
        SimpleCache(
            File(cacheDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
            StandaloneDatabaseProvider(appCtx)
        )
    }
    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory()
            .setCache(cache)
    }
    private val loadErrorHandlingPolicy by lazy {
        CustomLoadErrorHandlingPolicy()
    }
    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private var playIndexJob: Job? = null
    private var downloadErrorNo: Int = 0
    private var playErrorNo = 0
    private val downloadTaskActiveLock = Mutex()
    private val playbackSessionId = AtomicLong()
    private val activeDownloader = AtomicReference<Downloader?>()
    private val prefetchDownloader = AtomicReference<Downloader?>()
    private val paragraphPrefetchState = ParagraphPrefetchState<ParagraphAudioRequest>()
    private var pendingParagraphPrefetch: ParagraphAudioRequest? = null
    private var paragraphPrefetchJob: Job? = null
    private var paragraphLookaheadJob: Job? = null

    private data class ParagraphAudioRequest(
        val chapter: TextChapter,
        val httpTts: HttpTTS,
        val rate: Int,
        val sessionId: Long,
        val paragraphSession: Long,
        val stream: Boolean,
        val variable: String,
        val loginHeader: String,
    )

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
        observeEvent<TextChapter>(EventBus.TEXT_CHAPTER_READY) { chapter ->
            pendingParagraphPrefetch?.let { tryStartParagraphPrefetch(it, chapter) }
        }
        observeEvent<Boolean>(EventBus.READ_ALOUD_FOLLOW) { following ->
            if (!following) pendingParagraphPrefetch?.let(::loadParagraphLookahead)
        }
        observeSharedPreferences { _, key ->
            if (key == PreferKey.streamReadAloudAudio && paragraphMode) {
                val wasPaused = pause
                playStop()
                restartParagraphSession()
                if (!paragraphWaitingForChapter) {
                    if (wasPaused) pageChanged = true else play()
                }
            }
        }
    }

    override fun onDestroy() {
        invalidatePlaybackSession()
        cancelDownloadTask()
        super.onDestroy()
        exoPlayer.release()
        cache.release()
        Coroutine.async {
            removeCacheFile()
        }
    }

    override fun play() {
        if (paragraphMode && paragraphWaitingForChapter) return
        pageChanged = false
        playStop()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            super.play()
            if (paragraphMode) {
                downloadAndPlayParagraphAudios(AppConfig.streamReadAloudAudio)
            } else if (AppConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    override fun playStop() {
        invalidatePlaybackSession()
        cancelDownloadTask()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        playIndexJob?.cancel()
    }

    private fun updateNextPos() {
        if (paragraphMode) {
            paragraphFinished(nowSpeak)
            return
        }
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
        } else {
            nextChapter(auto = true)
        }
    }

    private fun isParagraphRequestActive(request: ParagraphAudioRequest): Boolean =
        isSessionActive(request.sessionId) && isRun && paragraphMode &&
                !paragraphWaitingForChapter && paragraphSession == request.paragraphSession &&
                ReadBook.book?.bookUrl == request.chapter.chapter.bookUrl &&
                textChapter?.chapter?.url == request.chapter.chapter.url &&
                textChapter?.chapter?.index == request.chapter.chapter.index &&
                speechRate == request.rate && AppConfig.streamReadAloudAudio == request.stream &&
                ReadAloud.httpTTS?.let { it.id == request.httpTts.id && it.equal(request.httpTts) } == true

    private fun checkParagraphRequest(request: ParagraphAudioRequest) {
        if (!isParagraphRequestActive(request)) {
            throw CancellationException("Paragraph audio request is no longer current")
        }
    }

    private fun paragraphCacheName(
        content: String,
        chapter: TextChapter,
        request: ParagraphAudioRequest,
    ): String = buildHttpTtsCacheFileName(
        chapter.title, request.httpTts.url, request.rate,
        request.variable, request.loginHeader, content,
    )

    private fun downloadAndPlayParagraphAudios(stream: Boolean) {
        val chapter = textChapter ?: return
        val httpTts = ReadAloud.httpTTS?.copy() ?: return
        val request = ParagraphAudioRequest(
            chapter, httpTts, speechRate, startPlaybackSession(), paragraphSession, stream,
            httpTts.getVariable(), httpTts.getLoginHeader().orEmpty(),
        )
        val speechItems = paragraphSpeechItems
        val firstIndex = nowSpeak
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                checkParagraphRequest(request)
                val downloaderChannel = Channel<Downloader>()
                val worker = if (stream) launch {
                    for (downloader in downloaderChannel) {
                        checkParagraphRequest(request)
                        activeDownloader.set(downloader)
                        try {
                            downloadCancellable({ downloader.download(null) }, downloader::cancel)
                        } finally {
                            activeDownloader.compareAndSet(downloader, null)
                        }
                    }
                } else null
                try {
                    for (index in firstIndex until speechItems.size) {
                        ensureActive()
                        checkParagraphRequest(request)
                        val speech = speechItems[index]
                        val fileName = paragraphCacheName(speech.text, chapter, request)
                        val duration = normalizeHttpTtsPauseDuration(httpTts.pauseDuration)
                        val insertPause = shouldInsertParagraphPause(
                            speech.item.id, speechItems.getOrNull(index + 1)?.item?.id, duration,
                        )
                        if (stream) {
                            val factory = createParagraphDataSourceFactory(request, speech.text)
                            downloaderChannel.send(createDownloader(factory, fileName))
                            val source = createMediaSource(factory, fileName, index, request.sessionId)
                            val silence = if (insertPause) {
                                createPauseMediaSource(duration, request.sessionId)
                            } else null
                            enqueueMediaSources(request.sessionId, listOfNotNull(source, silence))
                        } else {
                            if (!hasSpeakFile(fileName)) {
                                val input = getSpeakStream(
                                    httpTts, speech.text, request.sessionId, requestRate = request.rate,
                                ) ?: throw NoStackTraceException("TTS returned no audio")
                                writeParagraphAudio(fileName, input, request)
                            }
                            val item = createQueueMediaItem(
                                Uri.fromFile(getSpeakFileAsMd5(fileName)), index, request.sessionId,
                            )
                            val silence = if (insertPause) {
                                createPauseMediaItem(
                                    Uri.fromFile(getOrCreatePauseFile(duration)), duration, request.sessionId,
                                )
                            } else null
                            enqueueMediaItems(request.sessionId, listOfNotNull(item, silence))
                        }
                    }
                    withContext(Main) { armParagraphPrefetch(request) }
                } finally {
                    downloaderChannel.close()
                }
                worker?.join()
            }
        }.onError {
            if (it !is CancellationException && isParagraphRequestActive(request)) {
                AppLog.put("段落朗读下载出错\n${it.localizedMessage}", it, true)
                lifecycleScope.launch(Main) {
                    if (isParagraphRequestActive(request)) pauseReadAloud()
                }
            }
        }
    }

    private suspend fun writeParagraphAudio(
        name: String,
        input: InputStream,
        request: ParagraphAudioRequest,
    ) {
        val context = currentCoroutineContext()
        input.use { stream ->
            downloadCancellable(download = {
                HttpTtsFileCache.write(getSpeakFileAsMd5(name), stream) {
                    context.ensureActive()
                    checkParagraphRequest(request)
                }
            }, cancel = stream::close)
        }
    }

    private fun createParagraphDataSourceFactory(
        request: ParagraphAudioRequest,
        text: String,
        prefetch: Boolean = false,
    ): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        .setUpstreamDataSourceFactory(DataSource.Factory {
            InputStreamDataSource {
                runBlocking {
                    checkParagraphRequest(request)
                    getSpeakStream(
                        request.httpTts, text, request.sessionId, prefetch, request.rate,
                    ) ?: throw NoStackTraceException("TTS returned no audio")
                }
            }
        })

    /** Called on Main after the current chapter queue has been prepared. */
    private fun armParagraphPrefetch(request: ParagraphAudioRequest) {
        if (!isParagraphRequestActive(request)) return
        if (request.chapter.chapter.index + 1 >= ReadBook.simulatedChapterSize) return
        pendingParagraphPrefetch = request
        paragraphPrefetchState.arm(request)
        val target = listOfNotNull(
            ReadBook.prevTextChapter, ReadBook.curTextChapter, ReadBook.nextTextChapter,
        ).firstOrNull { isParagraphPrefetchTarget(request, it) }
        if (target?.isCompleted == true) {
            tryStartParagraphPrefetch(request, target)
        } else if (target == null || !ReadAloud.followReadAloudPosition) {
            loadParagraphLookahead(request)
        }
        // A shared chapter still being laid out will notify TEXT_CHAPTER_READY once.
    }

    private fun isParagraphPrefetchTarget(request: ParagraphAudioRequest, chapter: TextChapter): Boolean =
        chapter.chapter.bookUrl == request.chapter.chapter.bookUrl &&
                chapter.chapter.index == request.chapter.chapter.index + 1

    private fun loadParagraphLookahead(request: ParagraphAudioRequest) {
        if (!isParagraphRequestActive(request) || !paragraphPrefetchState.claimLoad(request)) return
        val book = ReadBook.book?.takeIf { it.bookUrl == request.chapter.chapter.bookUrl } ?: return
        paragraphLookaheadJob = lifecycleScope.launch(IO) {
            try {
                val target = loadParagraphSpeechChapter(book, request.chapter.chapter.index + 1)
                withContext(Main) {
                    if (target != null) tryStartParagraphPrefetch(request, target)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.put("下一章段落预加载失败\n${error.localizedMessage}", error)
            }
        }
    }

    private fun tryStartParagraphPrefetch(request: ParagraphAudioRequest, target: TextChapter) {
        if (!isParagraphRequestActive(request) || !target.isCompleted || !target.hasBodyContent ||
            !isParagraphPrefetchTarget(request, target) || !paragraphPrefetchState.claimAttempt(request)
        ) return
        paragraphPrefetchJob = lifecycleScope.launch(IO) {
            val items = ParagraphSpeechQueue.buildPrefetch(
                target.paragraphItems, paragraphSpeechLimit, NEXT_CHAPTER_TTS_PREFETCH_ITEM_LIMIT,
            )
            for (speech in items) {
                ensureActive()
                checkParagraphRequest(request)
                try {
                    val name = paragraphCacheName(speech.text, target, request)
                    if (request.stream) {
                        val downloader = createDownloader(
                            createParagraphDataSourceFactory(request, speech.text, prefetch = true), name,
                        )
                        prefetchDownloader.set(downloader)
                        try {
                            checkParagraphRequest(request)
                            downloadCancellable({ downloader.download(null) }, downloader::cancel)
                        } finally {
                            prefetchDownloader.compareAndSet(downloader, null)
                        }
                    } else if (!hasSpeakFile(name)) {
                        val input = getSpeakStream(
                            request.httpTts, speech.text, request.sessionId,
                            prefetch = true, requestRate = request.rate,
                        ) ?: continue
                        writeParagraphAudio(name, input, request)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Speculative failure never changes playback errors or inserts silence.
                    AppLog.put("下一章段落语音预取失败\n${error.localizedMessage}", error)
                }
            }
        }
    }

    private fun cancelParagraphPrefetch() {
        pendingParagraphPrefetch = null
        paragraphPrefetchState.clear()
        paragraphLookaheadJob?.cancel()
        paragraphLookaheadJob = null
        paragraphPrefetchJob?.cancel()
        paragraphPrefetchJob = null
        prefetchDownloader.getAndSet(null)?.cancel()
    }

    private fun downloadAndPlayAudios() {
        val sessionId = startPlaybackSession()
        exoPlayer.clearMediaItems()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                ensureSessionActive(sessionId)
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    ensureSessionActive(sessionId)
                    if (index < nowSpeak) return@forEachIndexed
                    var text = content
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    val fileName = md5SpeakFileName(text)
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isEmpty()) {
                        AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$text")
                        createSilentSound(fileName)
                    } else if (!hasSpeakFile(fileName)) {
                        runCatching {
                            val inputStream = getSpeakStream(httpTts, speakText, sessionId)
                            if (inputStream != null) {
                                createSpeakFile(fileName, inputStream)
                            } else {
                                createSilentSound(fileName)
                            }
                        }.onFailure {
                            if (it !is CancellationException && isSessionActive(sessionId)) {
                                pauseReadAloud()
                            }
                            return@execute
                        }
                    }
                    val file = getSpeakFileAsMd5(fileName)
                    val mediaItem = createQueueMediaItem(Uri.fromFile(file), index, sessionId)
                    val pauseDuration = normalizeHttpTtsPauseDuration(httpTts.pauseDuration)
                    val pauseItem = if (shouldInsertHttpTtsPause(
                            index,
                            contentList.lastIndex,
                            pauseDuration
                        )
                    ) {
                        createPauseMediaItem(
                            Uri.fromFile(getOrCreatePauseFile(pauseDuration)),
                            pauseDuration,
                            sessionId
                        )
                    } else {
                        null
                    }
                    enqueueMediaItems(sessionId, listOfNotNull(mediaItem, pauseItem))
                }
                ensureSessionActive(sessionId)
                preDownloadAudios(httpTts, sessionId)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private suspend fun preDownloadAudios(httpTts: HttpTTS, sessionId: Long) {
        val textChapter = ReadBook.nextTextChapter ?: return
        val contentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0, 1)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .take(NEXT_CHAPTER_TTS_PREFETCH_ITEM_LIMIT)
            .toList()
        contentList.forEach { content ->
            currentCoroutineContext().ensureActive()
            val fileName = md5SpeakFileName(content, textChapter)
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty()) {
                createSilentSound(fileName)
            } else if (!hasSpeakFile(fileName)) {
                runCatching {
                    val inputStream = getSpeakStream(httpTts, speakText, sessionId)
                    if (inputStream != null) {
                        createSpeakFile(fileName, inputStream)
                    } else {
                        createSilentSound(fileName)
                    }
                }
            }
        }
    }

    private fun downloadAndPlayAudiosStream() {
        val sessionId = startPlaybackSession()
        exoPlayer.clearMediaItems()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                ensureSessionActive(sessionId)
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val downloaderChannel = Channel<Downloader>()
                launch {
                    for (downloader in downloaderChannel) {
                        activeDownloader.set(downloader)
                        try {
                            runInterruptible { downloader.download(null) }
                        } finally {
                            activeDownloader.compareAndSet(downloader, null)
                        }
                    }
                }
                try {
                    contentList.forEachIndexed { index, content ->
                        ensureActive()
                        ensureSessionActive(sessionId)
                        if (index < nowSpeak) return@forEachIndexed
                        var text = content
                        if (paragraphStartPos > 0 && index == nowSpeak) {
                            text = text.substring(paragraphStartPos)
                        }
                        val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                        if (speakText.isEmpty()) {
                            AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$speakText")
                        }
                        val fileName = md5SpeakFileName(text)
                        val dataSourceFactory = createDataSourceFactory(
                            httpTts,
                            speakText,
                            sessionId
                        )
                        val downloader = createDownloader(dataSourceFactory, fileName)
                        downloaderChannel.send(downloader)
                        val mediaSource = createMediaSource(
                            dataSourceFactory,
                            fileName,
                            index,
                            sessionId
                        )
                        val pauseDuration = normalizeHttpTtsPauseDuration(httpTts.pauseDuration)
                        val pauseMediaSource = if (shouldInsertHttpTtsPause(
                                index,
                                contentList.lastIndex,
                                pauseDuration
                            )
                        ) {
                            createPauseMediaSource(pauseDuration, sessionId)
                        } else {
                            null
                        }
                        enqueueMediaSources(
                            sessionId,
                            listOfNotNull(mediaSource, pauseMediaSource)
                        )
                    }
                    ensureSessionActive(sessionId)
                    preDownloadAudiosStream(httpTts, downloaderChannel, sessionId)
                } finally {
                    downloaderChannel.close()
                }
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private suspend fun preDownloadAudiosStream(
        httpTts: HttpTTS,
        downloaderChannel: Channel<Downloader>,
        sessionId: Long
    ) {
        val textChapter = ReadBook.nextTextChapter ?: return
        val contentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0, 1)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .take(NEXT_CHAPTER_TTS_PREFETCH_ITEM_LIMIT)
            .toList()
        contentList.forEach { content ->
            currentCoroutineContext().ensureActive()
            val fileName = md5SpeakFileName(content, textChapter)
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            val dataSourceFactory = createDataSourceFactory(httpTts, speakText, sessionId)
            val downloader = createDownloader(dataSourceFactory, fileName)
            downloaderChannel.send(downloader)
        }
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String,
        sessionId: Long
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) {
                    null
                } else {
                    kotlin.runCatching {
                        runBlocking {
                            getSpeakStream(httpTts, speakText, sessionId)
                        }
                    }.onFailure {
                        when (it) {
                            is InterruptedException,
                            is CancellationException -> Unit

                            else -> if (isSessionActive(sessionId)) pauseReadAloud()
                        }
                    }.getOrThrow()
                } ?: resources.openRawResource(R.raw.silent_sound)
            }
        }
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        return factory
    }

    private fun createDownloader(factory: CacheDataSource.Factory, fileName: String): Downloader {
        val uri = fileName.toUri()
        val request = DownloadRequest.Builder(fileName, uri).build()
        return DefaultDownloaderFactory(factory, okHttpClient.dispatcher.executorService)
            .createDownloader(request)
    }

    private fun createMediaSource(
        factory: DataSource.Factory,
        fileName: String,
        index: Int,
        sessionId: Long
    ): MediaSource {
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(createQueueMediaItem(fileName.toUri(), index, sessionId))
    }

    private fun createPauseMediaSource(durationMs: Int, sessionId: Long): MediaSource {
        val factory = DataSource.Factory {
            InputStreamDataSource {
                generateSilentWavBytes(durationMs).inputStream()
            }
        }
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(
                createPauseMediaItem("pause:$durationMs".toUri(), durationMs, sessionId)
            )
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String,
        sessionId: Long,
        prefetch: Boolean = false,
        requestRate: Int = speechRate,
    ): InputStream? {
        while (true) {
            ensureSessionActive(sessionId)
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = speakText,
                    speakSpeed = requestRate,
                    source = httpTts,
                    readTimeout = 300 * 1000L,
                    coroutineContext = currentCoroutineContext()
                )
                val checkJs = httpTts.loginCheckJs
                val response = kotlin.runCatching {
                    analyzeUrl.getResponseAwait().let {
                        currentCoroutineContext().ensureActive()
                        ensureSessionActive(sessionId)
                        if (!checkJs.isNullOrBlank()) {
                            analyzeUrl.evalJS(checkJs, it) as Response
                        } else {
                            it
                        }
                    }
                }.getOrElse { throwable ->
                    currentCoroutineContext().ensureActive()
                    ensureSessionActive(sessionId)
                    if (!checkJs.isNullOrBlank()) {
                        val errResponse = analyzeUrl.getErrResponse(throwable)
                        try {
                            (analyzeUrl.evalJS(checkJs, errResponse) as Response).also {
                                if (it.code == 500) {
                                    throw throwable
                                }
                            }
                        } catch (_: Throwable) {
                            throw throwable
                        }
                    } else {
                        throw throwable
                    }
                }
                ensureSessionActive(sessionId)
                response.headers["Content-Type"]?.let { contentType ->
                    val contentType = contentType.substringBefore(";")
                    val ct = httpTts.contentType
                    if (contentType == "application/json" || contentType.startsWith("text/")) {
                        throw NoStackTraceException(response.body.string())
                    } else if (ct?.isNotBlank() == true) {
                        if (!contentType.matches(ct.toRegex())) {
                            throw NoStackTraceException(
                                "TTS服务器返回错误：" + response.body.string()
                            )
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                ensureSessionActive(sessionId)
                response.body.byteStream().let { stream ->
                    val checked = checkedAudioStream(stream)
                    if (!prefetch) downloadErrorNo = 0
                    return checked
                }
            } catch (e: Exception) {
                ensureSessionActive(sessionId)
                if (prefetch) throw e
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.put("js错误\n${e.localizedMessage}", e, true)
                        e.printOnDebug()
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        downloadErrorNo++
                        if (downloadErrorNo > 5) {
                            val msg = "tts超时或连接错误超过5次\n${e.localizedMessage}"
                            AppLog.put(msg, e, true)
                            throw e
                        }
                    }

                    else -> {
                        downloadErrorNo++
                        val msg = "tts下载错误\n${e.localizedMessage}"
                        AppLog.put(msg, e)
                        e.printOnDebug()
                        if (downloadErrorNo > 5) {
                            val msg1 = "TTS服务器连续5次错误，已暂停阅读。"
                            AppLog.put(msg1, e, true)
                            throw e
                        } else {
                            AppLog.put("TTS下载音频出错，使用无声音频代替。\n朗读文本：$speakText")
                            break
                        }
                    }
                }
            }
        }
        return null
    }

    private fun md5SpeakFileName(content: String, textChapter: TextChapter? = this.textChapter): String {
        val httpTts = ReadAloud.httpTTS
        return buildHttpTtsCacheFileName(
            textChapter?.title.orEmpty(),
            httpTts?.url,
            speechRate,
            httpTts?.getVariable().orEmpty(),
            httpTts?.getLoginHeader().orEmpty(),
            content,
        )
    }

    private fun createSilentSound(fileName: String) {
        resources.openRawResource(R.raw.silent_sound).use { input ->
            HttpTtsFileCache.write(getSpeakFileAsMd5(fileName), input) { }
        }
    }

    private fun hasSpeakFile(name: String): Boolean {
        return HttpTtsFileCache.isReusable(getSpeakFileAsMd5(name))
    }

    private fun getSpeakFileAsMd5(name: String): File {
        return File("${ttsFolderPath}$name.mp3")
    }

    private suspend fun createSpeakFile(name: String, inputStream: InputStream) {
        val context = currentCoroutineContext()
        inputStream.use { input ->
            downloadCancellable(download = {
                HttpTtsFileCache.write(getSpeakFileAsMd5(name), input) {
                    context.ensureActive()
                }
            }, cancel = input::close)
        }
    }

    private fun getOrCreatePauseFile(durationMs: Int): File {
        val file = FileUtils.createFileIfNotExist("${ttsFolderPath}pause_$durationMs.wav")
        if (file.length() == 0L) {
            file.writeBytes(generateSilentWavBytes(durationMs))
        }
        return file
    }

    private fun createQueueMediaItem(uri: Uri, index: Int, sessionId: Long): MediaItem {
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId("$HTTP_TTS_MEDIA_ID_PREFIX$sessionId:$index")
            .build()
    }

    private fun createPauseMediaItem(uri: Uri, durationMs: Int, sessionId: Long): MediaItem {
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId("$HTTP_TTS_PAUSE_MEDIA_ID_PREFIX$sessionId:$durationMs")
            .build()
    }

    private fun isPauseMediaItem(mediaItem: MediaItem?): Boolean {
        return mediaItem?.mediaId?.startsWith(HTTP_TTS_PAUSE_MEDIA_ID_PREFIX) == true
    }

    /**
     * 移除缓存文件
     */
    private fun removeCacheFile() {
        val titleMd5 = MD5Utils.md5Encode16(textChapter?.title ?: "")
        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
            val isSilentSound = it.length() == 2160L
            if ((!it.name.startsWith(titleMd5)
                        && System.currentTimeMillis() - it.lastModified() > 600000)
                || isSilentSound
            ) {
                FileUtils.delete(it.absolutePath)
            }
        }
    }


    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                upPlayPos()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        if (paragraphMode) {
            val index = exoPlayer.currentMediaItem?.mediaId?.substringAfterLast(':')?.toIntOrNull()
            if (!pause && !isPauseMediaItem(exoPlayer.currentMediaItem) &&
                isCurrentSessionMediaItem(exoPlayer.currentMediaItem) && index != null
            ) paragraphStarted(index)
            return
        }
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber)
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            val speakTextLength = contentList[nowSpeak].length
            if (speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            for (i in start..contentList[nowSpeak].length) {
                if (pageIndex + 1 < textChapter.pageSize
                    && readAloudNumber + i > textChapter.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage(syncReadAloudFollow = true)
                    upTtsProgress(readAloudNumber + i.toInt())
                }
                delay(sleep)
            }
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        playStop()
        speechRate = AppConfig.speechRatePlay + 5
        if (paragraphMode && paragraphWaitingForChapter) return
        if (paragraphMode) {
            restartParagraphSession()
            downloadAndPlayParagraphAudios(AppConfig.streamReadAloudAudio)
        } else if (AppConfig.streamReadAloudAudio) {
            downloadAndPlayAudiosStream()
        } else {
            downloadAndPlayAudios()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
            }

            Player.STATE_BUFFERING -> {
                // 缓冲中
            }

            Player.STATE_READY -> {
                // 准备好
                if (pause) return
                if (!isCurrentSessionMediaItem(exoPlayer.currentMediaItem)) return
                exoPlayer.play()
                if (!isPauseMediaItem(exoPlayer.currentMediaItem)) {
                    upPlayPos()
                }
            }

            Player.STATE_ENDED -> {
                // 结束
                if (!isCurrentSessionMediaItem(exoPlayer.currentMediaItem)) return
                playErrorNo = 0
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                updateNextPos()
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }

            else -> {}
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (!isCurrentSessionMediaItem(mediaItem)) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        trimPlayedMediaItems()
        if (isPauseMediaItem(mediaItem)) return
        if (!paragraphMode) updateNextPos()
        upPlayPos()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        val mediaItem = exoPlayer.currentMediaItem ?: return
        if (!isCurrentSessionMediaItem(mediaItem)) return
        if (isPauseMediaItem(mediaItem)) {
            trimPlayedMediaItems()
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            }
            return
        }
        contentList.getOrNull(nowSpeak)?.let {
            AppLog.put("朗读错误\n$it", error)
        } ?: AppLog.put("朗读错误", error)
        deleteCurrentSpeakFile()
        trimPlayedMediaItems()
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})")
            AppLog.put("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})", error)
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos()
            }
        }
    }

    private fun deleteCurrentSpeakFile() {
        if (AppConfig.streamReadAloudAudio) {
            return
        }
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration!!.uri.path!!
        File(filePath).delete()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    private fun trimPlayedMediaItems() {
        val currentIndex = exoPlayer.currentMediaItemIndex
        if (currentIndex > 0) {
            exoPlayer.removeMediaItems(0, currentIndex)
        }
    }

    private suspend fun enqueueMediaItems(sessionId: Long, mediaItems: List<MediaItem>) {
        awaitQueueSlots(sessionId, mediaItems.size)
        withContext(Main) {
            if (!isSessionActive(sessionId)) return@withContext
            mediaItems.forEach(exoPlayer::addMediaItem)
        }
        ensureSessionActive(sessionId)
    }

    private suspend fun enqueueMediaSources(sessionId: Long, mediaSources: List<MediaSource>) {
        awaitQueueSlots(sessionId, mediaSources.size)
        withContext(Main) {
            if (!isSessionActive(sessionId)) return@withContext
            mediaSources.forEach(exoPlayer::addMediaSource)
        }
        ensureSessionActive(sessionId)
    }

    private suspend fun awaitQueueSlots(sessionId: Long, requiredSlots: Int) {
        while (true) {
            currentCoroutineContext().ensureActive()
            ensureSessionActive(sessionId)
            if (pause) {
                delay(1_000L)
                continue
            }
            val hasCapacity = withContext(Main) {
                if (!isSessionActive(sessionId)) return@withContext false
                trimPlayedMediaItems()
                hasHttpTtsQueueCapacity(exoPlayer.mediaItemCount, requiredSlots)
            }
            if (hasCapacity) return
            delay(80L)
        }
    }

    private fun startPlaybackSession(): Long = playbackSessionId.incrementAndGet()

    private fun cancelDownloadTask() {
        activeDownloader.getAndSet(null)?.cancel()
        downloadTask?.cancel()
        downloadTask = null
    }

    private fun invalidatePlaybackSession() {
        playbackSessionId.incrementAndGet()
        cancelParagraphPrefetch()
    }

    private fun isSessionActive(sessionId: Long): Boolean {
        return playbackSessionId.get() == sessionId
    }

    private fun ensureSessionActive(sessionId: Long) {
        if (!isSessionActive(sessionId)) {
            throw CancellationException("playback session changed")
        }
    }

    private fun isCurrentSessionMediaItem(mediaItem: MediaItem?): Boolean {
        mediaItem ?: return false
        return httpTtsMediaSessionId(mediaItem.mediaId)?.let(::isSessionActive) == true
    }

    class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            return C.TIME_UNSET
        }
    }

}
