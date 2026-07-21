package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.audio.AudioCacheManager
import io.legado.app.help.globalExecutor
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.AudioPlayService
import io.legado.app.model.SourceCallBack
import io.legado.app.utils.postEvent
import io.legado.app.utils.startService
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import splitties.init.appCtx
import java.util.concurrent.Future
import kotlin.text.trim

internal data class AudioPlayUrlKey(
    val bookUrl: String,
    val sourceUrl: String,
    val chapterIndex: Int,
)

internal data class PreloadedAudioPlayUrl(
    val url: String,
    val lyric: String?,
)

internal class AudioPlayUrlPreloadStore {

    private data class Entry(val key: AudioPlayUrlKey, val value: PreloadedAudioPlayUrl)

    private var generation = 0L
    private var entry: Entry? = null
    private val loadingKeys = hashSetOf<AudioPlayUrlKey>()

    @Synchronized
    fun reset() {
        generation++
        entry = null
        loadingKeys.clear()
    }

    @Synchronized
    fun begin(key: AudioPlayUrlKey): Long? {
        if (entry?.key == key || !loadingKeys.add(key)) return null
        return generation
    }

    @Synchronized
    fun complete(
        key: AudioPlayUrlKey,
        requestGeneration: Long,
        url: String,
        lyric: String? = null,
    ): Boolean {
        loadingKeys.remove(key)
        if (requestGeneration == generation && url.isNotBlank()) {
            entry = Entry(key, PreloadedAudioPlayUrl(url, lyric))
            return true
        }
        return false
    }

    @Synchronized
    fun finish(key: AudioPlayUrlKey) {
        loadingKeys.remove(key)
    }

    @Synchronized
    fun invalidate(key: AudioPlayUrlKey) {
        generation++
        if (entry?.key == key) {
            entry = null
        }
        loadingKeys.clear()
    }

    @Synchronized
    fun consume(key: AudioPlayUrlKey): PreloadedAudioPlayUrl? {
        val current = entry ?: return null
        if (current.key != key) return null
        entry = null
        return current.value
    }
}

internal class AudioReadTimeTracker {

    private var record = ReadRecord()
    private var activeRecord: ReadRecord? = null
    private var startedAt: Long? = null

    @Synchronized
    fun setRecord(record: ReadRecord) {
        this.record = record
    }

    @Synchronized
    fun start(now: Long) {
        if (startedAt == null) {
            activeRecord = record
            startedAt = now
        }
    }

    @Synchronized
    fun stop(now: Long, lastRead: Long): ReadRecord? {
        val start = startedAt ?: return null
        val record = activeRecord ?: return null
        activeRecord = null
        startedAt = null
        val elapsed = (now - start).coerceAtLeast(0)
        if (elapsed == 0L) return null
        record.readTime += elapsed
        record.lastRead = lastRead
        return record.copy()
    }
}

@SuppressLint("StaticFieldLeak")
@Suppress("unused")
object AudioPlay : CoroutineScope by MainScope() {
    /**
     * 播放模式枚举
     */
    enum class PlayMode(val iconRes: Int) {
        LIST_END_STOP(R.drawable.ic_play_mode_list_end_stop),
        SINGLE_LOOP(R.drawable.ic_play_mode_single_loop),
        RANDOM(R.drawable.ic_play_mode_random),
        LIST_LOOP(R.drawable.ic_play_mode_list_loop);

        fun next(): PlayMode {
            return when (this) {
                LIST_END_STOP -> SINGLE_LOOP
                SINGLE_LOOP -> RANDOM
                RANDOM -> LIST_LOOP
                LIST_LOOP -> LIST_END_STOP
            }
        }
    }

    var playMode = PlayMode.LIST_END_STOP
    var status = Status.STOP
    private var activityContext: Context? = null
    private var serviceContext: Context? = null
    private val context: Context get() = activityContext ?: serviceContext ?: appCtx
    var callback: CallBack? = null
    var book: Book? = null
    var chapterSize = 0
    var simulatedChapterSize = 0
    var durChapterIndex = 0
    var durChapterPos = 0
    var durChapter: BookChapter? = null
    var durPlayUrl = ""
    var durMediaUrl = ""
    var durLyric: String? = null
    var durAudioSize = 0
    var inBookshelf = false
    var bookSource: BookSource? = null
        private set
    val loadingChapters = arrayListOf<Int>()
    private val playUrlPreloadStore = AudioPlayUrlPreloadStore()
    private val skipCacheOnceKeys = hashSetOf<AudioCacheKey>()
    @Volatile
    private var playingCacheKey: AudioCacheKey? = null
    private var playingCacheBookUrl: String? = null
    private var playingCacheTreeUri: String? = null
    private val readTimeTracker = AudioReadTimeTracker()
    @Volatile
    private var readTimeWrite: Future<*>? = null
    val executor = globalExecutor

    fun changePlayMode() {
        playMode = playMode.next()
        book?.setPlayMode(playMode.ordinal)
        postEvent(EventBus.PLAY_MODE_CHANGED, playMode)
    }

    fun upData(book: Book) {
        AudioPlay.book = book
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        if (durChapterIndex != book.durChapterIndex) {
            stopPlay()
            durChapterIndex = book.durChapterIndex
            durChapterPos = book.durChapterPos
            durPlayUrl = ""
            durMediaUrl = ""
            durLyric = null
            durAudioSize = 0
            clearPlayingCache()
        }
        upDurChapter()
    }

    fun resetData(book: Book) {
        stop()
        playUrlPreloadStore.reset()
        AudioPlay.book = book
        resetReadRecord(book)
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        setBookSource(book.getBookSource())
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        PlayMode.entries.getOrNull(book.getPlayMode())?.let{
            playMode = it
            postEvent(EventBus.PLAY_MODE_CHANGED, it)
        }
        val playSpeed = book.getPlaySpeed()
        AudioPlayService.playSpeed = playSpeed
        postEvent(EventBus.AUDIO_SPEED, playSpeed)
        durPlayUrl = ""
        durMediaUrl = ""
        durLyric = null
        durAudioSize = 0
        synchronized(this) {
            skipCacheOnceKeys.clear()
            clearPlayingCache()
        }
        upDurChapter()
        SourceCallBack.callBackBook(SourceCallBack.START_READ, bookSource, book, durChapter)
        postEvent(EventBus.AUDIO_BUFFER_PROGRESS, 0)
    }

    @Synchronized
    fun replaceBook(book: Book) {
        AudioPlay.book = book
        resetReadRecord(book, resumeIfPlaying = true)
    }

    @Synchronized
    private fun resetReadRecord(book: Book, resumeIfPlaying: Boolean = false) {
        upReadTime()
        kotlin.runCatching { readTimeWrite?.get() }.onFailure {
            AppLog.put("保存听书时长失败\n${it.localizedMessage}", it)
        }
        readTimeTracker.setRecord(
            ReadRecord(
                bookName = book.name,
                readTime = appDb.readRecordDao.getReadTime(book.name) ?: 0,
            )
        )
        if (resumeIfPlaying && AudioPlayService.isPlaying) {
            markReadTimeStart()
        }
    }

    fun setBookSource(source: BookSource?) {
        bookSource = source
        playUrlPreloadStore.reset()
        synchronized(this) {
            skipCacheOnceKeys.clear()
        }
    }

    @Synchronized
    fun markReadTimeStart() {
        if (AppConfig.enableReadRecord) {
            readTimeTracker.start(SystemClock.elapsedRealtime())
        }
    }

    @Synchronized
    fun upReadTime() {
        val record = readTimeTracker.stop(
            now = SystemClock.elapsedRealtime(),
            lastRead = System.currentTimeMillis(),
        ) ?: return
        readTimeWrite = executor.submit { appDb.readRecordDao.insert(record) }
    }

    private fun addLoading(index: Int): Boolean {
        synchronized(this) {
            if (loadingChapters.contains(index)) return false
            loadingChapters.add(index)
            return true
        }
    }

    private fun removeLoading(index: Int) {
        synchronized(this) {
            loadingChapters.remove(index)
        }
    }

    @Synchronized
    private fun consumeSkipCacheOnce(key: AudioCacheKey): Boolean {
        return skipCacheOnceKeys.remove(key)
    }

    @Synchronized
    private fun clearPlayingCache() {
        playingCacheKey = null
        playingCacheBookUrl = null
        playingCacheTreeUri = null
    }

    fun loadOrUpPlayUrl() {
        if (durMediaUrl.isEmpty()) {
            loadPlayUrl()
        } else {
            upPlayUrl()
        }
    }

    /**
     * 加载播放URL
     */
    private fun loadPlayUrl() {
        val index = durChapterIndex
        if (addLoading(index)) {
            val book = book
            if (book != null) {
                upDurChapter()
                val chapter = durChapter
                if (chapter == null) {
                    removeLoading(index)
                    return
                }
                if (chapter.isVolume) {
                    skipTo(index + 1)
                    removeLoading(index)
                    return
                }
                val cacheKey = AudioCacheKey.from(chapter)
                val source = bookSource
                val preloadKey = source?.let {
                    AudioPlayUrlKey(book.bookUrl, it.bookSourceUrl, chapter.index)
                }
                val cacheTreeUri = AppConfig.audioCacheTreeUri
                val skipCache = consumeSkipCacheOnce(cacheKey)
                Coroutine.async(this) {
                    if (skipCache) null else AudioCacheManager.getCachedAudio(
                        cacheTreeUri,
                        book.bookUrl,
                        chapter,
                    )
                }.onSuccess { cachedAudio ->
                    if (!isCurrentChapter(book.bookUrl, chapter, cacheKey)) {
                        removeLoading(index)
                        return@onSuccess
                    }
                    if (cachedAudio != null) {
                        preloadKey?.let(playUrlPreloadStore::invalidate)
                        synchronized(AudioPlay) {
                            playingCacheKey = cacheKey
                            playingCacheBookUrl = book.bookUrl
                            playingCacheTreeUri = cacheTreeUri
                        }
                        durPlayUrl = cachedAudio.playUrl ?: chapter.resourceUrl.orEmpty()
                        durMediaUrl = cachedAudio.mediaUri
                        durLyric = chapter.getVariable("lyric")
                        removeLoading(index)
                        upLoading(false)
                        callback?.upLyric(durLyric)
                        upPlayUrl()
                        preloadNextPlayUrl(index)
                    } else if (source != null && preloadKey != null) {
                        loadRemotePlayUrl(book, source, chapter, preloadKey)
                    } else {
                        removeLoading(index)
                        appCtx.toastOnUi("book source is null")
                    }
                }.onError {
                    AppLog.put("Read audio cache failed\n${it.localizedMessage}", it)
                    if (isCurrentChapter(book.bookUrl, chapter, cacheKey) &&
                        source != null && preloadKey != null
                    ) {
                        loadRemotePlayUrl(book, source, chapter, preloadKey)
                    } else {
                        removeLoading(index)
                    }
                }.onCancel {
                    removeLoading(index)
                }
            } else {
                removeLoading(index)
                appCtx.toastOnUi("book is null")
            }
        }
    }

    private fun loadRemotePlayUrl(
        book: Book,
        bookSource: BookSource,
        chapter: BookChapter,
        preloadKey: AudioPlayUrlKey,
    ) {
        clearPlayingCache()
        playUrlPreloadStore.consume(preloadKey)?.let { preloaded ->
            durPlayUrl = preloaded.url
            durMediaUrl = preloaded.url
            durLyric = preloaded.lyric
            removeLoading(chapter.index)
            upLoading(false)
            callback?.upLyric(durLyric)
            upPlayUrl()
            preloadNextPlayUrl(chapter.index)
            return
        }
        upLoading(true)
        WebBook.getContent(this, bookSource, book, chapter)
            .onSuccess { content ->
                val content = content.trim()
                if (content.isEmpty()) {
                    appCtx.toastOnUi("未获取到资源链接")
                } else {
                    contentLoadFinish(chapter, content)
                }
            }.onError {
                AppLog.put("获取资源链接出错\n$it", it, true)
                upLoading(false)
            }.onCancel {
                removeLoading(chapter.index)
            }.onFinally {
                callback?.upLyric(durLyric)
                removeLoading(chapter.index)
            }
    }

    private fun isCurrentChapter(
        bookUrl: String,
        chapter: BookChapter,
        cacheKey: AudioCacheKey,
    ): Boolean {
        return book?.bookUrl == bookUrl &&
                durChapterIndex == chapter.index &&
                durChapter?.let(AudioCacheKey::from) == cacheKey
    }

    /**
     * 加载完成
     */
    private fun contentLoadFinish(chapter: BookChapter, content: String) {
        if (chapter.index == book?.durChapterIndex) {
            clearPlayingCache()
            durPlayUrl = content
            durMediaUrl = content
            durLyric = chapter.getVariable("lyric")
            upPlayUrl()
            preloadNextPlayUrl(chapter.index)
        }
    }

    private fun preloadNextPlayUrl(currentIndex: Int) {
        if (playMode != PlayMode.LIST_END_STOP && playMode != PlayMode.LIST_LOOP) return
        val book = book ?: return
        val source = bookSource ?: return
        val nextChapter = findNextPlayableChapter(book.bookUrl, currentIndex + 1) ?: return
        val key = AudioPlayUrlKey(book.bookUrl, source.bookSourceUrl, nextChapter.index)
        Coroutine.async(this) {
            AudioCacheManager.getCachedUriString(
                AppConfig.audioCacheTreeUri,
                book.bookUrl,
                nextChapter,
            )
        }.onSuccess { cachedUri ->
            if (book.bookUrl != AudioPlay.book?.bookUrl ||
                source.bookSourceUrl != bookSource?.bookSourceUrl
            ) {
                return@onSuccess
            }
            if (cachedUri != null) {
                playUrlPreloadStore.invalidate(key)
                return@onSuccess
            }
            val requestGeneration = playUrlPreloadStore.begin(key) ?: return@onSuccess
            WebBook.getContent(this, source, book, nextChapter, needSave = false)
                .onSuccess { content ->
                    val resolvedPlayUrl = content.trim()
                    playUrlPreloadStore.complete(
                        key,
                        requestGeneration,
                        resolvedPlayUrl,
                        nextChapter.getVariable("lyric"),
                    )
                }
                .onError {
                    playUrlPreloadStore.finish(key)
                }
                .onCancel {
                    playUrlPreloadStore.finish(key)
                }
        }.onError {
            AppLog.put("Read next audio cache failed\n${it.localizedMessage}", it)
        }
    }

    fun retryAfterCachedPlaybackError(resumePosition: Int): Boolean {
        val book = book ?: return false
        val chapter = durChapter ?: return false
        val currentCacheKey = AudioCacheKey.from(chapter)
        val cachedRef = synchronized(this) {
            val cachedKey = playingCacheKey ?: return false
            val ref = Triple(
                playingCacheBookUrl ?: book.bookUrl,
                playingCacheTreeUri,
                cachedKey,
            )
            playingCacheKey = null
            playingCacheBookUrl = null
            playingCacheTreeUri = null
            skipCacheOnceKeys.add(cachedKey)
            ref
        }
        book.durChapterPos = resumePosition.coerceAtLeast(0)
        durChapterPos = book.durChapterPos
        bookSource?.let { source ->
            playUrlPreloadStore.invalidate(
                AudioPlayUrlKey(book.bookUrl, source.bookSourceUrl, chapter.index)
            )
        }
        durPlayUrl = ""
        durMediaUrl = ""
        val cachedBookUrl = cachedRef.first
        val cacheTreeUri = cachedRef.second
        val cachedKey = cachedRef.third
        Coroutine.async(this) {
            AudioCacheManager.removeCachedChapter(
                cacheTreeUri,
                cachedBookUrl,
                cachedKey,
            )
        }.onSuccess { removed ->
            if (removed) {
                postEvent(
                    EventBus.AUDIO_CACHE_CHANGED,
                    AudioCacheStateChanged(cachedBookUrl, cachedKey, false, cacheTreeUri),
                )
            }
            if (cachedKey != currentCacheKey) {
                synchronized(AudioPlay) { skipCacheOnceKeys.remove(cachedKey) }
            }
            if (isCurrentChapter(book.bookUrl, chapter, currentCacheKey)) {
                loadPlayUrl()
            }
        }.onError {
            AppLog.put("Remove broken audio cache failed\n${it.localizedMessage}", it)
            if (cachedKey != currentCacheKey) {
                synchronized(AudioPlay) { skipCacheOnceKeys.remove(cachedKey) }
            }
            if (isCurrentChapter(book.bookUrl, chapter, currentCacheKey)) {
                loadPlayUrl()
            }
        }
        return true
    }

    private fun findNextPlayableChapter(bookUrl: String, startIndex: Int): BookChapter? {
        var index = startIndex
        while (index in 0..<simulatedChapterSize) {
            val chapter = appDb.bookChapterDao.getChapter(bookUrl, index) ?: return null
            if (!chapter.isVolume) return chapter
            index++
        }
        return null
    }

    private fun upPlayUrl() {
        if (isPlayToEnd()) {
            playNew()
        } else {
            play()
        }
    }

    /**
     * 播放当前章节
     */
    fun play() {
        context.startService<AudioPlayService> {
            action = IntentAction.play
        }
    }

    /**
     * 从头播放新章节
     */
    private fun playNew() {
        context.startService<AudioPlayService> {
            action = IntentAction.playNew
        }
    }

    /**
     * 更新当前章节
     */
    fun upDurChapter() {
        val book = book ?: return
        durChapter = appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex)
        durAudioSize = durChapter?.end?.toInt() ?: 0
        val title = durChapter?.title ?: appCtx.getString(R.string.data_loading)
        postEvent(EventBus.AUDIO_SUB_TITLE, title)
        postEvent(EventBus.AUDIO_SIZE, durAudioSize)
        postEvent(EventBus.AUDIO_PROGRESS, durChapterPos)
    }

    fun pause(context: Context) {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.pause
            }
        }
    }

    fun resume(context: Context) {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.resume
            }
        }
    }

    fun stop() {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.stop
            }
        }
    }

    fun setSpeed(speed: Float) {
        if (AudioPlayService.isRun) {
            book?.setPlaySpeed(speed)
            val clampedSpeed = speed.coerceIn(0.5f, 3.0f)
            context.startService<AudioPlayService> {
                action = IntentAction.setSpeed
                putExtra("speed", clampedSpeed)
            }
        }
    }

     

    fun adjustProgress(position: Int) {
        durChapterPos = position
        saveRead()
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.adjustProgress
                putExtra("position", position)
            }
        }
    }

    fun skipTo(index: Int) {
        Coroutine.async {
            stopPlay()
            if (index in 0..<simulatedChapterSize) {
                durChapterIndex = index
                durChapterPos = 0
                durPlayUrl = ""
                durMediaUrl = ""
                durLyric = null
                clearPlayingCache()
                saveRead()
                loadPlayUrl()
            }
        }
    }

    fun prev() {
        Coroutine.async {
            stopPlay()
            if (durChapterIndex > 0) {
                durChapterIndex -= 1
                durChapterPos = 0
                durPlayUrl = ""
                durMediaUrl = ""
                durLyric = null
                clearPlayingCache()
                saveRead()
                loadPlayUrl()
            }
        }
    }

    fun next() {
        stopPlay()
        when (playMode) {
            PlayMode.LIST_END_STOP -> {
                if (durChapterIndex + 1 < simulatedChapterSize) {
                    durChapterIndex += 1
                    durChapterPos = 0
                    durPlayUrl = ""
                    durMediaUrl = ""
                    durLyric = null
                    clearPlayingCache()
                    saveRead()
                    loadPlayUrl()
                }
            }

            PlayMode.SINGLE_LOOP -> {
                durChapterPos = 0
                durPlayUrl = ""
                durMediaUrl = ""
                durLyric = null
                clearPlayingCache()
                saveRead()
                loadPlayUrl()
            }

            PlayMode.RANDOM -> {
                durChapterIndex = (0 until simulatedChapterSize).random()
                durChapterPos = 0
                durPlayUrl = ""
                durMediaUrl = ""
                durLyric = null
                clearPlayingCache()
                saveRead()
                loadPlayUrl()
            }

            PlayMode.LIST_LOOP -> {
                durChapterIndex = (durChapterIndex + 1) % simulatedChapterSize
                durChapterPos = 0
                durPlayUrl = ""
                durMediaUrl = ""
                durLyric = null
                clearPlayingCache()
                saveRead()
                loadPlayUrl()
            }
        }
    }

    fun setTimer(minute: Int) {
        if (AudioPlayService.isRun) {
            val intent = Intent(context, AudioPlayService::class.java)
            intent.action = IntentAction.setTimer
            intent.putExtra("minute", minute)
            context.startService(intent)
        } else {
            AudioPlayService.timeMinute = minute.coerceIn(0, 180)
            AudioPlayService.setPendingChapterStop(0)
            postEvent(EventBus.AUDIO_DS, AudioPlayService.timeMinute)
            postEvent(EventBus.AUDIO_CHAPTER_STOP, 0)
        }
    }

    fun setChapterStop(count: Int) {
        if (AudioPlayService.isRun) {
            val intent = Intent(context, AudioPlayService::class.java)
            intent.action = IntentAction.setChapterStop
            intent.putExtra("count", count)
            context.startService(intent)
        } else {
            AudioPlayService.timeMinute = 0
            AudioPlayService.setPendingChapterStop(count)
            postEvent(EventBus.AUDIO_DS, 0)
            postEvent(EventBus.AUDIO_CHAPTER_STOP, AudioPlayService.chapterToStop)
        }
    }

    fun addTimer() {
        val intent = Intent(context, AudioPlayService::class.java)
        intent.action = IntentAction.addTimer
        context.startService(intent)
    }

    fun stopPlay() {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.stopPlay
            }
        }
    }

    fun saveRead(first: Boolean = false) {
        val book = book ?: return
        Coroutine.async {
            book.lastCheckCount = 0
            val durTime = System.currentTimeMillis()
            book.durChapterTime = durTime
            val chapterChanged = book.durChapterIndex != durChapterIndex
            book.durChapterIndex = durChapterIndex
            book.durChapterPos = durChapterPos
            if (first || chapterChanged) {
                appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex)?.let {
                    book.durChapterTitle = it.getDisplayTitle(
                        ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                        book.getUseReplaceRule(),
                        replaceBook = book.toReplaceBook()
                    )
                    SourceCallBack.callBackBook(SourceCallBack.SAVE_READ, bookSource, book, it,durTime.toString())
                }
            }
            book.update()
        }
    }

    /**
     * 保存章节长度
     */
    fun saveDurChapter(audioSize: Long) {
        val chapter = durChapter ?: return
        Coroutine.async {
            durAudioSize = audioSize.toInt()
            chapter.end = audioSize
            chapter.update()
        }
    }

    fun playPositionChanged(position: Int) {
        durChapterPos = position
        saveRead()
    }

    fun upLoading(loading: Boolean) {
        callback?.upLoading(loading)
    }

    private fun isPlayToEnd(): Boolean {
        return durChapterIndex + 1 == simulatedChapterSize
                && durChapterPos == durAudioSize
    }

    fun register(context: Context) {
        activityContext = context
        callback = context as CallBack
    }

    fun unregister(context: Context) {
        if (activityContext === context) {
            activityContext = null
            callback = null
        }
        coroutineContext.cancelChildren()
    }

    fun registerService(context: Context) {
        serviceContext = context
    }

    fun unregisterService() {
        serviceContext = null
    }

    interface CallBack {

        fun upLoading(loading: Boolean)
        fun upLyric(lyric: String?)
        fun upLyricP(position: Int)
    }

}
