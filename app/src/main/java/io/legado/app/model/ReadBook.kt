package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PageAnim.scrollPageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.AppWebDav
import io.legado.app.help.HighlightAnchor
import io.legado.app.help.HighlightRuleMatcher
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightTextBuilder
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.globalExecutor
import io.legado.app.model.localBook.TextFile
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.CacheBookService
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.utils.GSON
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

internal fun resolveHighlightChapterPosition(
    rawPosition: Int,
    sourceTitleLength: Int,
    currentTitleLength: Int
): Int {
    val currentLength = currentTitleLength.coerceAtLeast(0)
    val sourceLength = sourceTitleLength.takeIf { it >= 0 } ?: currentLength
    return (rawPosition - sourceLength).coerceAtLeast(0) + currentLength
}

// ponytail: fixed 64-character anchor; use contextual matching if sources rewrite larger spans.
private const val REFRESH_POSITION_ANCHOR_LENGTH = 64

internal fun resolveReplacePreviewPosition(
    sourceText: String,
    sourceTitleLength: Int,
    sourcePosition: Int,
    previewText: String,
    previewTitleLength: Int,
): Int {
    val sourceTitle = sourceTitleLength.coerceAtLeast(0)
    val previewTitle = previewTitleLength.coerceAtLeast(0)
    val sourceBody = sourceText.drop(sourceTitle)
    val previewBody = previewText.drop(previewTitle)
    val sourceBodyPosition = (sourcePosition - sourceTitle).coerceIn(0, sourceBody.length)
    val anchor = sourceBody.drop(sourceBodyPosition).take(REFRESH_POSITION_ANCHOR_LENGTH)
    val previewBodyPosition = (if (anchor.isEmpty()) {
        sourceBodyPosition.coerceAtMost(previewBody.length)
    } else {
        HighlightAnchor.jumpPos(previewBody, sourceBodyPosition, anchor)
    }).coerceIn(0, previewBody.length)
    return previewTitle + previewBodyPosition
}


@Suppress("MemberVisibilityCanBePrivate")
object ReadBook : CoroutineScope by MainScope() {

    data class ReplacePreview(
        val sourceChapter: TextChapter,
        val previewChapter: TextChapter,
        val sourcePosition: Int,
        val sourceProgressPosition: Int,
        val chapterPosition: Int,
        val bookUrl: String,
        val chapterIndex: Int,
    )

    private data class ManualReplaceRules(
        val title: List<ReplaceRule>,
        val content: List<ReplaceRule>,
    ) {
        val enabled get() = title.isNotEmpty() || content.isNotEmpty()
    }

    var book: Book? = null
    var callBack: CallBack? = null
    var highlights: List<BookHighlight> = emptyList()
        private set
    @Volatile
    private var highlightsVersion = 0L
    var highlightRules: List<HighlightRule> = emptyList()
        private set
    private var highlightRulesVersion = 0L
    private var highlightRulesBookUrl: String? = null
    var inBookshelf = false
    var chapterSize = 0
    var simulatedChapterSize = 0
    var durChapterIndex = 0
    var durChapterPos = 0
    var isLocalBook = true
    var chapterChanged = false
    var prevTextChapter: TextChapter? = null
    var curTextChapter: TextChapter? = null
    var nextTextChapter: TextChapter? = null
    var bookSource: BookSource? = null
    var msg: String? = null
    private val loadingChapters = arrayListOf<Int>()
    private val readRecord = ReadRecord()
    private val chapterLoadingJobs = ConcurrentHashMap<Int, Coroutine<*>>()
    private val prevChapterLoadingLock = Mutex()
    private val curChapterLoadingLock = Mutex()
    private val nextChapterLoadingLock = Mutex()
    private var pendingHighlightJump: PendingHighlightJump? = null
    private var pendingHighlightAnchor: PendingHighlightAnchor? = null
    var readStartTime: Long = System.currentTimeMillis()

    /* 跳转进度前进度记录 */
    var lastBookProgress: BookProgress? = null

    /* web端阅读进度记录 */
    var webBookProgress: BookProgress? = null

    var preDownloadTask: Job? = null
    val downloadedChapters = hashSetOf<Int>()
    val downloadFailChapters = hashMapOf<Int, Int>()
    var contentProcessor: ContentProcessor? = null
    val downloadScope = CoroutineScope(SupervisorJob() + IO)
    val preDownloadSemaphore = Semaphore(2)
    val executor = globalExecutor

    fun resetData(book: Book) {
        val positionAnchor = pendingHighlightAnchor
        releaseAndCancel()
        ReadBook.book = book
        loadHighlights(book)
        loadHighlightRules(book)
        readRecord.bookName = book.name
        readRecord.author = book.author
        readRecord.readTime = appDb.readRecordDao
            .getReadTime(readRecord.deviceId, book.name) ?: 0
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        contentProcessor = ContentProcessor.get(book)
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        isLocalBook = book.isLocal
        upWebBook(book)
        clearTextChapter()
        pendingHighlightAnchor = positionAnchor?.takeIf {
            it.waitForLayout &&
                it.bookUrl == book.bookUrl &&
                it.chapterIndex == book.durChapterIndex &&
                it.rawPosition == book.durChapterPos
        }
        callBack?.upContent()
        callBack?.upMenuView()
        callBack?.upPageAnim()
        lastBookProgress = null
        webBookProgress = null
        TextFile.clear()
        synchronized(this) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }

    private fun manualReplaceRules(book: Book): ManualReplaceRules? {
        if (!AppConfig.manualReplaceRule) return null
        val ids = book.config.manualReplaceRuleIds
        val rules = if (ids.isEmpty()) {
            emptyList()
        } else {
            appDb.replaceRuleDao.findByIds(*ids.toLongArray())
        }
        return ManualReplaceRules(
            title = rules.filter { it.scopeTitle },
            content = rules.filter { it.scopeContent },
        )
    }

    fun loadHighlights(book: Book) {
        highlights = appDb.bookHighlightDao.getByBook(book.bookUrl)
        highlightsVersion++
    }

    fun loadHighlightRules(book: Book) {
        invalidateHighlightRuleMatches()
        highlightRules = appDb.highlightRuleDao.findEnabledByBook(book.name, book.origin)
        highlightRulesBookUrl = book.bookUrl
        highlightRulesVersion++
    }

    fun upHighlightRules() {
        book?.let { loadHighlightRules(it) }
        callBack?.upContent(resetPageOffset = false)
    }

    fun ruleMatchesOfChapter(textChapter: TextChapter): List<HighlightRuleMatcher.RuleMatch> {
        val currentBook = book ?: return emptyList()
        if (highlightRules.isEmpty() || !textChapter.isCompleted) return emptyList()
        if (!textChapter.isForBook(currentBook) || !isActiveTextChapter(textChapter)) {
            return emptyList()
        }
        val version = highlightRulesVersion
        val bookUrl = currentBook.bookUrl
        if (textChapter.highlightRuleMatchesVersion == version &&
            textChapter.highlightRuleMatchesBookUrl == bookUrl
        ) {
            return textChapter.highlightRuleMatches ?: emptyList()
        }
        if (textChapter.highlightRuleMatchesJob?.isActive == true) return emptyList()
        val rules = highlightRules.map {
            HighlightRuleMatcher.Rule(
                it.id,
                it.pattern,
                it.isRegex,
                it.styleObj(),
                it.timeoutMillisecond,
                applyToTitle = it.applyToTitle,
                applyToBody = it.applyToBody
            )
        }
        val chapterBookUrl = textChapter.chapter.bookUrl
        val chapterIndex = textChapter.chapter.index
        lateinit var job: Job
        job = launch(Default, start = CoroutineStart.LAZY) {
            val matchResult = HighlightRuleMatcher.matchDetailed(
                chapterText(textChapter),
                rules,
                shouldContinue = { job.isActive },
                titleLength = textChapter.layoutTitleLength
            )
            withContext(Main) {
                if (highlightRulesVersion != version ||
                    highlightRulesBookUrl != bookUrl ||
                    book?.bookUrl != bookUrl ||
                    textChapter.chapter.bookUrl != chapterBookUrl ||
                    textChapter.chapter.index != chapterIndex ||
                    !textChapter.isCompleted ||
                    !isActiveTextChapter(textChapter) ||
                    textChapter.highlightRuleMatchesJob !== job
                ) return@withContext
                textChapter.highlightRuleMatches = if (matchResult.completed) {
                    matchResult.matches
                } else {
                    emptyList()
                }
                textChapter.highlightRuleMatchesVersion = version
                textChapter.highlightRuleMatchesBookUrl = bookUrl
                callBack?.upContent(resetPageOffset = false)
            }
        }
        textChapter.highlightRuleMatchesJob = job
        job.invokeOnCompletion {
            if (textChapter.highlightRuleMatchesJob === job) {
                textChapter.highlightRuleMatchesJob = null
            }
        }
        job.start()
        return emptyList()
    }

    private fun chapterText(textChapter: TextChapter): String {
        textChapter.highlightText?.let { return it }
        val cacheResult = textChapter.isCompleted
        val text = HighlightTextBuilder.build(
            textChapter.pages.flatMap { page ->
                page.lines.map { line ->
                    HighlightTextBuilder.LineInput(line.text, line.isParagraphEnd)
                }
            }
        )
        if (cacheResult) textChapter.highlightText = text
        return text
    }

    private fun isActiveTextChapter(textChapter: TextChapter): Boolean {
        return prevTextChapter === textChapter ||
            curTextChapter === textChapter ||
            nextTextChapter === textChapter
    }

    private fun observeHighlightRuleLayout(textChapter: TextChapter) {
        textChapter.setProgressListener(object : LayoutProgressListener {
            override fun onLayoutCompleted() {
                launch { ruleMatchesOfChapter(textChapter) }
            }
        })
        if (textChapter.isCompleted) ruleMatchesOfChapter(textChapter)
    }

    private fun invalidateHighlightRuleMatches() {
        prevTextChapter?.invalidateHighlightRuleMatches()
        curTextChapter?.invalidateHighlightRuleMatches()
        nextTextChapter?.invalidateHighlightRuleMatches()
    }

    fun highlightsOfChapter(
        chapter: TextChapter,
        layoutTitleLength: Int? = null
    ): List<BookHighlight> {
        val currentBook = book ?: return emptyList()
        val bookChapter = chapter.chapter
        val legacyBound = highlights.filter {
            it.bindLegacyChapter(currentBook, bookChapter, chapter.title)
        }
        if (legacyBound.isNotEmpty()) {
            val legacyTimes = legacyBound.map { it.time }
            executor.execute {
                appDb.bookHighlightDao.bindChapterUrl(legacyTimes, bookChapter.url)
            }
        }
        val chapterHighlights = highlights
            .filter { it.isForChapter(currentBook, bookChapter) }
            .sortedWith(compareBy(BookHighlight::chapterPos, BookHighlight::time))
        val titleLength = layoutTitleLength ?: return chapterHighlights
        val pinned = chapterHighlights.filter { it.pinLayoutTitleLength(titleLength) }
        if (pinned.isNotEmpty()) {
            executor.execute {
                appDb.bookHighlightDao.pinLayoutTitleLength(
                    currentBook.bookUrl,
                    bookChapter.url,
                    titleLength
                )
            }
        }
        return chapterHighlights
    }

    fun anchoredHighlightsOfChapter(
        chapter: TextChapter,
        layoutTitleLength: Int
    ): List<Pair<BookHighlight, HighlightAnchor.Anchor>> {
        val version = highlightsVersion
        if (chapter.isCompleted &&
            chapter.manualHighlightAnchorsVersion == version &&
            chapter.manualHighlightAnchorsTitleLength == layoutTitleLength
        ) {
            return chapter.manualHighlightAnchors.orEmpty()
        }
        val chapterHighlights = highlightsOfChapter(chapter, layoutTitleLength)
        if (!chapter.isCompleted) {
            return chapterHighlights.map { highlight ->
                highlight to HighlightAnchor.Anchor(
                    highlight.bodyStart(layoutTitleLength),
                    highlight.bodyEnd(layoutTitleLength)
                )
            }
        }
        val anchors = if (chapterHighlights.isEmpty()) {
            emptyList()
        } else {
            val bodyText = chapterText(chapter).drop(layoutTitleLength)
            chapterHighlights.mapNotNull { highlight ->
                HighlightAnchor.reanchor(
                    bodyText,
                    highlight.bodyStart(layoutTitleLength),
                    highlight.bodyEnd(layoutTitleLength),
                    highlight.bookText
                )?.let { highlight to it }
            }
        }
        if (highlightsVersion == version) {
            chapter.manualHighlightAnchors = anchors
            chapter.manualHighlightAnchorsTitleLength = layoutTitleLength
            chapter.manualHighlightAnchorsVersion = version
        }
        return anchors
    }

    fun addHighlight(highlight: BookHighlight) {
        appDb.bookHighlightDao.insert(highlight)
        if (!highlight.isForBook(book)) return
        highlights = (highlights.filterNot { it.time == highlight.time } + highlight)
            .sortedWith(
                compareBy(BookHighlight::chapterIndex, BookHighlight::chapterPos, BookHighlight::time)
            )
        highlightsVersion++
        callBack?.upContent(resetPageOffset = false)
    }

    fun updateHighlight(highlight: BookHighlight) {
        appDb.bookHighlightDao.update(highlight)
        if (!highlight.isForBook(book)) return
        highlights = highlights.map { if (it.time == highlight.time) highlight else it }
        highlightsVersion++
        callBack?.upContent(resetPageOffset = false)
    }

    fun removeHighlight(highlight: BookHighlight) {
        appDb.bookHighlightDao.delete(highlight)
        if (!highlight.isForBook(book)) return
        highlights = highlights.filter { it.time != highlight.time }
        highlightsVersion++
        callBack?.upContent(resetPageOffset = false)
    }

    fun saveLastHighlightStyle(style: HighlightStyle) {
        appCtx.putPrefString(PreferKey.highlightLastStyle, GSON.toJson(style.normalized()))
    }

    fun upData(book: Book) {
        releaseAndCancel()
        ReadBook.book = book
        loadHighlights(book)
        loadHighlightRules(book)
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        if (durChapterIndex != book.durChapterIndex) {
            durChapterIndex = book.durChapterIndex
            durChapterPos = book.durChapterPos
            clearTextChapter()
        }
        if (curTextChapter?.isCompleted == false) {
            curTextChapter = null
        }
        if (nextTextChapter?.isCompleted == false) {
            nextTextChapter = null
        }
        if (prevTextChapter?.isCompleted == false) {
            prevTextChapter = null
        }
        upWebBook(book)
        callBack?.upMenuView()
        synchronized(this) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }

    fun upWebBook(book: Book) {
        if (book.isLocal) {
            bookSource = null
            if (book.getImageStyle().isNullOrBlank() && (book.isImage || book.isPdf)) {
                book.setImageStyle(Book.imgStyleFull)
            }
        } else {
            appDb.bookSourceDao.getBookSource(book.origin)?.let {
                bookSource = it
                if (book.getImageStyle().isNullOrBlank()) {
                    var imageStyle = it.getContentRule().imageStyle
                    if (imageStyle.isNullOrBlank() && (book.isImage || book.isPdf)) {
                        imageStyle = Book.imgStyleFull
                    }
                    book.setImageStyle(imageStyle)
                    if (imageStyle.equals(Book.imgStyleSingle, true)) {
                        book.setPageAnim(0)
                    }
                }
            } ?: let {
                bookSource = null
            }
        }
    }

    fun upReadBookConfig(book: Book) {
        val oldIndex = ReadBookConfig.styleSelect
        ReadBookConfig.isComic = book.isImage
        if (oldIndex != ReadBookConfig.styleSelect) {
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            if (AppConfig.readBarStyleFollowPage) {
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
        }
    }

    fun setProgress(progress: BookProgress) {
        if (BaseReadAloudService.isRun) {
            ReadAloud.detachReadAloudFollow()
        }
        if (progress.durChapterIndex < chapterSize &&
            (durChapterIndex != progress.durChapterIndex
                    || durChapterPos != progress.durChapterPos)
        ) {
            durChapterIndex = progress.durChapterIndex
            durChapterPos = progress.durChapterPos
            saveRead()
            clearTextChapter()
            callBack?.upContent()
            loadContent(resetPageOffset = true)
        }
    }

    //暂时保存跳转前进度
    fun saveCurrentBookProgress() {
        if (lastBookProgress != null) return //避免进度条连续跳转不能覆盖最初的进度记录
        lastBookProgress = book?.let { BookProgress(it) }
    }

    //恢复跳转前进度
    fun restoreLastBookProgress() {
        lastBookProgress?.let {
            setProgress(it)
            lastBookProgress = null
        }
    }

    fun clearTextChapter() {
        clearExpiredChapterLoadingJob(true)
        pendingHighlightJump = null
        pendingHighlightAnchor = null
        invalidateHighlightRuleMatches()
        prevTextChapter = null
        curTextChapter = null
        nextTextChapter = null
    }

    fun preserveCurrentPositionForRefresh() {
        pendingHighlightAnchor = currentPositionAnchor()
    }

    fun clearSearchResult() {
        curTextChapter?.clearSearchResult()
        prevTextChapter?.clearSearchResult()
        nextTextChapter?.clearSearchResult()
    }

    fun uploadProgress(toast: Boolean = false, successAction: (() -> Unit)? = null) {
        book?.let {
            launch(IO) {
                AppWebDav.uploadBookProgress(it, toast) {
                    successAction?.invoke()
                }
                ensureActive()
                it.update()
            }
        }
    }

    /**
     * 同步阅读进度
     * 如果当前进度快于服务器进度或者没有进度进行上传，如果慢与服务器进度则执行传入动作
     */
    fun syncProgress(
        newProgressAction: ((progress: BookProgress) -> Unit)? = null,
        uploadSuccessAction: (() -> Unit)? = null,
        syncSuccessAction: (() -> Unit)? = null
    ) {
        if (!AppConfig.syncBookProgress) return
        val book = book ?: return
        Coroutine.async {
            AppWebDav.getBookProgress(book)
        }.onError {
            AppLog.put("拉取阅读进度失败", it)
        }.onSuccess { progress ->
            if (progress == null || progress.durChapterIndex < book.durChapterIndex ||
                (progress.durChapterIndex == book.durChapterIndex
                        && progress.durChapterPos < book.durChapterPos)
            ) {
                // 服务器没有进度或者进度比服务器快，上传现有进度
                Coroutine.async {
                    AppWebDav.uploadBookProgress(BookProgress(book), uploadSuccessAction)
                    book.update()
                }
            } else if (progress.durChapterIndex > book.durChapterIndex ||
                progress.durChapterPos > book.durChapterPos
            ) {
                // 进度比服务器慢，执行传入动作
                newProgressAction?.invoke(progress)
            } else {
                syncSuccessAction?.invoke()
            }
        }
    }

    fun upReadTime() {
        if (!AppConfig.enableReadRecord) {
            return
        }
        val author = book?.author.orEmpty()
        executor.execute {
            readRecord.author = author
            readRecord.readTime = readRecord.readTime + System.currentTimeMillis() - readStartTime
            readStartTime = System.currentTimeMillis()
            readRecord.lastRead = System.currentTimeMillis()
            appDb.readRecordDao.insert(readRecord)
        }
    }

    fun upMsg(msg: String?) {
        if (ReadBook.msg != msg) {
            ReadBook.msg = msg
            callBack?.upContent()
        }
    }

    private fun prepareReadAloudPageNavigation(syncReadAloudFollow: Boolean): Boolean {
        val restartReadAloud = ReadAloudManualPagePolicy.shouldRestartFromVisiblePage(
            isReadAloudRunning = BaseReadAloudService.isRun,
            speechDrivenNavigation = syncReadAloudFollow,
            followManualPageTurns = AppConfig.readAloudFollowManualPage,
            followingReadAloudPosition = ReadAloud.followReadAloudPosition
        )
        if (BaseReadAloudService.isRun && !syncReadAloudFollow && !restartReadAloud) {
            ReadAloud.detachReadAloudFollow()
        }
        return restartReadAloud
    }

    fun moveToNextPage(syncReadAloudFollow: Boolean = false): Boolean {
        if (BaseReadAloudService.isRun && !syncReadAloudFollow) {
            ReadAloud.detachReadAloudFollow()
        }
        if (syncReadAloudFollow && !BaseReadAloudService.shouldSyncSpeechNavigation()) {
            return false
        }
        var hasNextPage = false
        curTextChapter?.let {
            val nextPagePos = it.getNextPageLength(durChapterPos)
            if (nextPagePos >= 0) {
                hasNextPage = true
                it.getPage(durPageIndex)?.removePageAloudSpan()
                durChapterPos = nextPagePos
                callBack?.cancelSelect()
                callBack?.upContent()
                saveRead(true)
            }
        }
        return hasNextPage
    }

    fun moveToPrevPage(syncReadAloudFollow: Boolean = false): Boolean {
        if (BaseReadAloudService.isRun && !syncReadAloudFollow) {
            ReadAloud.detachReadAloudFollow()
        }
        if (syncReadAloudFollow && !BaseReadAloudService.shouldSyncSpeechNavigation()) {
            return false
        }
        var hasPrevPage = false
        curTextChapter?.let {
            val prevPagePos = it.getPrevPageLength(durChapterPos)
            if (prevPagePos >= 0) {
                hasPrevPage = true
                durChapterPos = prevPagePos
                callBack?.upContent()
                saveRead(true)
            }
        }
        return hasPrevPage
    }

    fun moveToNextChapter(
        upContent: Boolean,
        upContentInPlace: Boolean = true,
        syncReadAloudFollow: Boolean = false
    ): Boolean {
        if (syncReadAloudFollow && !BaseReadAloudService.shouldSyncSpeechNavigation()) {
            return false
        }
        if (durChapterIndex < simulatedChapterSize - 1) {
            val restartReadAloud = prepareReadAloudPageNavigation(syncReadAloudFollow)
            durChapterPos = 0
            durChapterIndex++
            clearExpiredChapterLoadingJob()
            prevTextChapter?.invalidateHighlightRuleMatches()
            prevTextChapter = curTextChapter
            curTextChapter = nextTextChapter
            nextTextChapter = null
            if (curTextChapter == null) {
                AppLog.putDebug("moveToNextChapter-章节未加载,开始加载")
                if (upContentInPlace) callBack?.upContent()
                loadContent(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapter-章节已加载,刷新视图")
                callBack?.upContent()
            }
            loadContent(durChapterIndex.plus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            AppLog.putDebug("moveToNextChapter-curPageChanged()")
            curPageChanged(
                syncReadAloudFollow = syncReadAloudFollow,
                restartReadAloudFromVisiblePage = restartReadAloud
            )
            return true
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
            return false
        }
    }

    suspend fun moveToNextChapterAwait(
        upContent: Boolean,
        upContentInPlace: Boolean = true,
        syncReadAloudFollow: Boolean = false
    ): Boolean {
        if (BaseReadAloudService.isRun && !syncReadAloudFollow) {
            ReadAloud.detachReadAloudFollow()
        }
        if (syncReadAloudFollow && !BaseReadAloudService.shouldSyncSpeechNavigation()) {
            return false
        }
        if (durChapterIndex < simulatedChapterSize - 1) {
            durChapterPos = 0
            durChapterIndex++
            clearExpiredChapterLoadingJob()
            prevTextChapter?.invalidateHighlightRuleMatches()
            prevTextChapter = curTextChapter
            curTextChapter = nextTextChapter
            nextTextChapter = null
            if (curTextChapter == null) {
                AppLog.putDebug("moveToNextChapter-章节未加载,开始加载")
                if (upContentInPlace) callBack?.upContentAwait()
                loadContentAwait(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapter-章节已加载,刷新视图")
                callBack?.upContentAwait()
            }
            loadContent(durChapterIndex.plus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            AppLog.putDebug("moveToNextChapter-curPageChanged()")
            curPageChanged(syncReadAloudFollow = syncReadAloudFollow)
            return true
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
            return false
        }
    }

    fun moveToPrevChapter(
        upContent: Boolean,
        toLast: Boolean = true,
        upContentInPlace: Boolean = true,
        syncReadAloudFollow: Boolean = false
    ): Boolean {
        if (syncReadAloudFollow && !BaseReadAloudService.shouldSyncSpeechNavigation()) {
            return false
        }
        if (durChapterIndex > 0) {
            val restartReadAloud = prepareReadAloudPageNavigation(syncReadAloudFollow)
            durChapterPos = if (toLast) prevTextChapter?.lastReadLength ?: Int.MAX_VALUE else 0
            durChapterIndex--
            clearExpiredChapterLoadingJob()
            nextTextChapter?.invalidateHighlightRuleMatches()
            nextTextChapter = curTextChapter
            curTextChapter = prevTextChapter
            prevTextChapter = null
            if (curTextChapter == null) {
                if (upContentInPlace) callBack?.upContent()
                loadContent(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                callBack?.upContent()
            }
            loadContent(durChapterIndex.minus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            curPageChanged(
                syncReadAloudFollow = syncReadAloudFollow,
                restartReadAloudFromVisiblePage = restartReadAloud
            )
            return true
        } else {
            return false
        }
    }

    fun skipToPage(index: Int, success: (() -> Unit)? = null) {
        if (BaseReadAloudService.isRun) {
            ReadAloud.detachReadAloudFollow()
        }
        durChapterPos = curTextChapter?.getReadLength(index) ?: index
        callBack?.upContent {
            success?.invoke()
        }
        curPageChanged()
        saveRead(true)
    }

    fun setPageIndex(index: Int, syncReadAloudFollow: Boolean = false) {
        if (syncReadAloudFollow && !BaseReadAloudService.shouldSyncSpeechNavigation()) {
            return
        }
        val restartReadAloud = prepareReadAloudPageNavigation(syncReadAloudFollow)
        recycleRecorders(durPageIndex, index)
        durChapterPos = curTextChapter?.getReadLength(index) ?: index
        saveRead(true)
        curPageChanged(
            pageChanged = true,
            syncReadAloudFollow = syncReadAloudFollow,
            restartReadAloudFromVisiblePage = restartReadAloud
        )
    }

    fun recycleRecorders(beforeIndex: Int, afterIndex: Int) {
        if (!AppConfig.optimizeRender) {
            return
        }
        executor.execute {
            val textChapter = curTextChapter ?: return@execute
            if (afterIndex > beforeIndex) {
                textChapter.getPage(afterIndex - 2)?.recycleRecorders()
            }
            if (afterIndex < beforeIndex) {
                textChapter.getPage(afterIndex + 3)?.recycleRecorders()
            }
        }
    }

    fun openChapter(
        index: Int,
        durChapterPos: Int = 0,
        upContent: Boolean = true,
        highlightLayoutTitleLength: Int? = null,
        highlightAnchorText: String? = null,
        success: (() -> Unit)? = null
    ) {
        if (BaseReadAloudService.isRun) {
            ReadAloud.detachReadAloudFollow()
        }
        if (index < chapterSize) {
            clearTextChapter()
            if (upContent) callBack?.upContent()
            durChapterIndex = index
            ReadBook.durChapterPos = durChapterPos
            pendingHighlightJump = highlightLayoutTitleLength?.let { sourceTitleLength ->
                book?.let {
                    PendingHighlightJump(
                        it.bookUrl,
                        index,
                        durChapterPos,
                        sourceTitleLength
                    )
                }
            }
            pendingHighlightAnchor = highlightAnchorText?.takeIf(String::isNotEmpty)?.let {
                book?.let { currentBook ->
                    PendingHighlightAnchor(
                        currentBook.bookUrl,
                        index,
                        durChapterPos,
                        highlightLayoutTitleLength ?: -1,
                        it
                    )
                }
            }
            if (pendingHighlightJump == null) {
                saveRead()
            }
            loadContent(resetPageOffset = true) {
                success?.invoke()
            }
        }
    }

    /**
     * 当前页面变化
     */
    private fun curPageChanged(
        pageChanged: Boolean = false,
        syncReadAloudFollow: Boolean = false,
        restartReadAloudFromVisiblePage: Boolean = false
    ) {
        callBack?.pageChanged()
        curTextChapter?.let {
            if (BaseReadAloudService.isRun && it.isCompleted) {
                if (!syncReadAloudFollow) {
                    if (!restartReadAloudFromVisiblePage) {
                        ReadAloud.detachReadAloudFollow()
                        return@let
                    }
                }
                if (restartReadAloudFromVisiblePage) {
                    readAloud(!BaseReadAloudService.pause)
                } else {
                    val scrollPageAnim = pageAnim() == 3
                    if (scrollPageAnim && pageChanged) {
                        ReadAloud.pause(appCtx)
                    } else {
                        readAloud(!BaseReadAloudService.pause)
                    }
                }
            }
        }
        upReadTime()
        preDownload()
    }

    /**
     * 朗读
     */
    fun readAloud(
        play: Boolean = true,
        startPos: Int = 0,
        rewindToSentenceStart: Boolean = false
    ) {
        book ?: return
        val textChapter = curTextChapter ?: return
        if (textChapter.isCompleted) {
            ReadAloud.play(
                appCtx,
                play,
                startPos = startPos,
                rewindToSentenceStart = rewindToSentenceStart
            )
        }
    }

    /**
     * 当前页数
     */
    val durPageIndex: Int
        get() {
            return curTextChapter?.getPageIndexByCharIndex(durChapterPos) ?: durChapterPos
        }

    /**
     * 是否排版到了当前阅读位置
     */
    val isLayoutAvailable inline get() = durPageIndex >= 0

    val isScroll inline get() = pageAnim() == scrollPageAnim

    val contentLoadFinish get() = curTextChapter != null || msg != null

    /**
     * chapterOnDur: 0为当前页,1为下一页,-1为上一页
     */
    fun textChapter(chapterOnDur: Int = 0): TextChapter? {
        return when (chapterOnDur) {
            0 -> curTextChapter
            1 -> nextTextChapter
            -1 -> prevTextChapter
            else -> null
        }
    }

    /**
     * 加载当前章节和前后一章内容
     * @param resetPageOffset 滚动阅读是否重置滚动位置
     * @param success 当前章节加载完成回调
     */
    fun loadContent(
        resetPageOffset: Boolean,
        readPositionVersion: Long? = null,
        success: (() -> Unit)? = null,
    ) {
        loadContent(
            durChapterIndex,
            resetPageOffset = resetPageOffset,
            readPositionVersion = readPositionVersion,
            success = { success?.invoke() },
        )
        loadContent(
            durChapterIndex + 1,
            resetPageOffset = resetPageOffset,
            readPositionVersion = readPositionVersion,
        )
        loadContent(
            durChapterIndex - 1,
            resetPageOffset = resetPageOffset,
            readPositionVersion = readPositionVersion,
        )
    }

    fun loadOrUpContent(success: (() -> Unit)? = null) {
        if (curTextChapter == null) {
            loadContent(durChapterIndex) {
                success?.invoke()
            }
        } else {
            callBack?.upContent()
        }
        if (nextTextChapter == null) {
            loadContent(durChapterIndex + 1)
        }
        if (prevTextChapter == null) {
            loadContent(durChapterIndex - 1)
        }
    }

    suspend fun buildReplacePreview(sourcePosition: Int): ReplacePreview? {
        val currentBook = book ?: return null
        val sourceChapter = curTextChapter?.takeIf {
            it.isCompleted &&
                it.chapter.index == durChapterIndex &&
                it.chapter.bookUrl == currentBook.bookUrl
        } ?: return null
        val sourceProgressPosition = durChapterPos
        val chapterIndex = durChapterIndex
        return withContext(IO) {
            val chapter = sourceChapter.chapter
            val rawContent = BookHelp.getContent(currentBook, chapter)
                ?: return@withContext null
            val processor = ContentProcessor.get(currentBook)
            val manualRules = manualReplaceRules(currentBook)
            val replaceEnabled = if (manualRules != null) {
                false
            } else {
                !currentBook.getUseReplaceRule()
            }
            val titleRules = manualRules?.let { emptyList<ReplaceRule>() }
                ?: processor.getTitleReplaceRules()
            val displayTitle = chapter.getDisplayTitle(
                titleRules,
                useReplace = replaceEnabled,
                replaceBook = currentBook.toReplaceBook(),
            )
            val contents = processor.getContent(
                currentBook,
                chapter,
                rawContent,
                includeTitle = false,
                replaceEnabledOverride = replaceEnabled,
                titleReplaceRulesOverride = manualRules?.let { emptyList<ReplaceRule>() },
                contentReplaceRulesOverride = manualRules?.let { emptyList<ReplaceRule>() },
            )
            val previewChapter = ChapterProvider.getTextChapterAsync(
                this,
                currentBook,
                chapter,
                displayTitle,
                contents,
                simulatedChapterSize,
                saveChapterData = false,
            )
            try {
                previewChapter.layoutChannel.receiveAsFlow().collect()
                ensureActive()
                val sourceText = chapterText(sourceChapter)
                val previewText = chapterText(previewChapter)
                ReplacePreview(
                    sourceChapter = sourceChapter,
                    previewChapter = previewChapter,
                    sourcePosition = sourcePosition,
                    sourceProgressPosition = sourceProgressPosition,
                    chapterPosition = resolveReplacePreviewPosition(
                        sourceText = sourceText,
                        sourceTitleLength = sourceChapter.layoutTitleLength,
                        sourcePosition = sourcePosition,
                        previewText = previewText,
                        previewTitleLength = previewChapter.layoutTitleLength,
                    ),
                    bookUrl = currentBook.bookUrl,
                    chapterIndex = chapterIndex,
                )
            } catch (e: CancellationException) {
                previewChapter.cancelLayout()
                throw e
            }
        }
    }

    fun isCurrentReplacePreview(preview: ReplacePreview): Boolean {
        return book?.bookUrl == preview.bookUrl &&
            durChapterIndex == preview.chapterIndex &&
            durChapterPos == preview.sourceProgressPosition &&
            curTextChapter === preview.sourceChapter
    }

    /**
     * 加载章节内容
     * @param index 章节序号
     * @param upContent 是否更新视图
     * @param resetPageOffset 滚动阅读是否重置滚动位置
     * @param success 加载完成回调
     */
    fun loadContent(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        readPositionVersion: Long? = null,
        success: (() -> Unit)? = null,
    ) {
        Coroutine.async {
            val book = book!!
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: return@async
            if (addLoading(index)) {
                BookHelp.getContent(book, chapter)?.let {
                    contentLoadFinish(
                        book,
                        chapter,
                        it,
                        upContent,
                        resetPageOffset,
                        readPositionVersion = readPositionVersion,
                        success = success
                    )
                } ?: download(
                    downloadScope,
                    chapter,
                    resetPageOffset,
                    readPositionVersion = readPositionVersion,
                )
            }
        }.onError {
            AppLog.put("加载正文出错\n${it.localizedMessage}")
        }
    }

    suspend fun loadContentAwait(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        readPositionVersion: Long? = null,
        success: (() -> Unit)? = null,
    ) = withContext(IO) {
        if (addLoading(index)) {
            try {
                val book = book!!
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index)!!
                val content = BookHelp.getContent(book, chapter) ?: downloadAwait(chapter)
                contentLoadFinishAwait(
                    book,
                    chapter,
                    content,
                    upContent,
                    resetPageOffset,
                    readPositionVersion,
                )
                success?.invoke()
            } catch (e: Exception) {
                AppLog.put("加载正文出错\n${e.localizedMessage}")
            } finally {
                removeLoading(index)
            }
        }
    }

    /**
     * 下载正文
     */
    private suspend fun downloadIndex(index: Int) {
        if (index < 0) return
        if (index > chapterSize - 1) {
            upToc()
            return
        }
        val book = book ?: return
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: return
        if (BookHelp.hasContent(book, chapter)) {
            downloadedChapters.add(chapter.index)
        } else {
            delay(1000)
            if (addLoading(index)) {
                download(downloadScope, chapter, false, preDownloadSemaphore)
            }
        }
    }

    /**
     * 下载正文
     */
    private fun download(
        scope: CoroutineScope,
        chapter: BookChapter,
        resetPageOffset: Boolean,
        semaphore: Semaphore? = null,
        readPositionVersion: Long? = null,
        success: (() -> Unit)? = null,
    ) {
        val book = book ?: return removeLoading(chapter.index)
        val bookSource = bookSource
        if (bookSource != null) {
            CacheBook.getOrCreate(bookSource, book).download(
                scope,
                chapter,
                semaphore,
                resetPageOffset = resetPageOffset,
                readPositionVersion = readPositionVersion,
            )
        } else {
            val msg = if (book.isLocal) "无内容" else "没有书源"
            contentLoadFinish(
                book,
                chapter,
                "加载正文失败\n$msg",
                resetPageOffset = resetPageOffset,
                readPositionVersion = readPositionVersion,
                success = success
            )
        }
    }

    private suspend fun downloadAwait(chapter: BookChapter): String {
        val book = book!!
        val bookSource = bookSource
        if (bookSource != null) {
            return CacheBook.getOrCreate(bookSource, book).downloadAwait(chapter)
        } else {
            val msg = if (book.isLocal) "无内容" else "没有书源"
            return "加载正文失败\n$msg"
        }
    }

    @Synchronized
    private fun addLoading(index: Int): Boolean {
        if (loadingChapters.contains(index)) return false
        loadingChapters.add(index)
        return true
    }

    @Synchronized
    fun removeLoading(index: Int) {
        loadingChapters.remove(index)
    }

    /**
     * 内容加载完成
     */
    @Synchronized
    fun contentLoadFinish(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean,
        canceled: Boolean = false,
        readPositionVersion: Long? = null,
        success: (() -> Unit)? = null,
    ) {
        removeLoading(chapter.index)
        if (canceled || chapter.index !in durChapterIndex - 1..durChapterIndex + 1) {
            return
        }
        val shouldResetPageOffset = resetPageOffset &&
            shouldApplyReadPositionReset(readPositionVersion)
        chapterLoadingJobs[chapter.index]?.cancel()
        val job = Coroutine.async(this, start = CoroutineStart.LAZY) {
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            val manualRules = manualReplaceRules(book)
            val titleRules = manualRules?.title ?: contentProcessor.getTitleReplaceRules()
            val replaceEnabled = manualRules?.enabled ?: book.getUseReplaceRule()
            val displayTitle = chapter.getDisplayTitle(
                titleRules,
                replaceEnabled,
                replaceBook = book.toReplaceBook()
            )
            val contents = contentProcessor.getContent(
                book,
                chapter,
                content,
                includeTitle = false,
                replaceEnabledOverride = manualRules?.enabled,
                titleReplaceRulesOverride = manualRules?.title,
                contentReplaceRulesOverride = manualRules?.content,
            )
            ensureActive()
            val textChapter = ChapterProvider.getTextChapterAsync(
                this, book, chapter, displayTitle, contents, simulatedChapterSize
            )
            when (val offset = chapter.index - durChapterIndex) {
                0 -> curChapterLoadingLock.withLock {
                    withContext(Main) {
                        ensureActive()
                        curTextChapter?.invalidateHighlightRuleMatches()
                        curTextChapter = textChapter
                        observeHighlightRuleLayout(textChapter)
                    }
                    callBack?.upMenuView()
                    var available = false
                    for (page in textChapter.layoutChannel) {
                        val index = page.index
                        val positionReady = resolvePendingHighlightJump(book, textChapter)
                        if (positionReady && !available && page.containPos(durChapterPos)) {
                            if (upContent) {
                                callBack?.upContent(
                                    offset,
                                    shouldResetPageOffset,
                                    readPositionVersion = readPositionVersion,
                                )
                            }
                            available = true
                        }
                        if (positionReady && upContent && isScroll) {
                            if (max(index - 3, 0) < durPageIndex) {
                                callBack?.upContent(offset, false)
                            }
                        }
                        callBack?.onLayoutPageCompleted(index, page)
                    }
                    resolvePendingHighlightAnchor(book, textChapter)
                    if (upContent) {
                        callBack?.upContent(
                            offset,
                            !available && shouldResetPageOffset,
                            readPositionVersion = readPositionVersion,
                        )
                    }
                    curPageChanged(
                        syncReadAloudFollow = BaseReadAloudService.shouldSyncSpeechNavigation()
                    )
                    callBack?.contentLoadFinish()
                }

                -1 -> prevChapterLoadingLock.withLock {
                    withContext(Main) {
                        ensureActive()
                        prevTextChapter?.invalidateHighlightRuleMatches()
                        prevTextChapter = textChapter
                        observeHighlightRuleLayout(textChapter)
                    }
                    textChapter.layoutChannel.receiveAsFlow().collect()
                    if (upContent) {
                        callBack?.upContent(
                            offset,
                            shouldResetPageOffset,
                            readPositionVersion = readPositionVersion,
                        )
                    }
                }

                1 -> nextChapterLoadingLock.withLock {
                    withContext(Main) {
                        ensureActive()
                        nextTextChapter?.invalidateHighlightRuleMatches()
                        nextTextChapter = textChapter
                        observeHighlightRuleLayout(textChapter)
                    }
                    for (page in textChapter.layoutChannel) {
                        if (page.index > 1) {
                            continue
                        }
                        if (upContent) {
                            callBack?.upContent(
                                offset,
                                shouldResetPageOffset,
                                readPositionVersion = readPositionVersion,
                            )
                        }
                    }
                }
            }

            return@async
        }.onError {
            if (it is CancellationException) {
                return@onError
            }
            AppLog.put("ChapterProvider ERROR", it)
            appCtx.toastOnUi("ChapterProvider ERROR:\n${it.stackTraceStr}")
        }.onSuccess {
            success?.invoke()
        }
        chapterLoadingJobs[chapter.index] = job
        job.start()
    }

    suspend fun contentLoadFinishAwait(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean,
        readPositionVersion: Long? = null,
    ) {
        removeLoading(chapter.index)
        if (chapter.index !in durChapterIndex - 1..durChapterIndex + 1) {
            return
        }
        val shouldResetPageOffset = resetPageOffset &&
            shouldApplyReadPositionReset(readPositionVersion)
        kotlin.runCatching {
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            val manualRules = manualReplaceRules(book)
            val titleRules = manualRules?.title ?: contentProcessor.getTitleReplaceRules()
            val replaceEnabled = manualRules?.enabled ?: book.getUseReplaceRule()
            val displayTitle = chapter.getDisplayTitle(
                titleRules,
                replaceEnabled,
                replaceBook = book.toReplaceBook()
            )
            val contents = contentProcessor.getContent(
                book,
                chapter,
                content,
                includeTitle = false,
                replaceEnabledOverride = manualRules?.enabled,
                titleReplaceRulesOverride = manualRules?.title,
                contentReplaceRulesOverride = manualRules?.content,
            )
            val textChapter = ChapterProvider.getTextChapterAsync(
                this@ReadBook, book, chapter, displayTitle, contents, simulatedChapterSize
            )
            when (val offset = chapter.index - durChapterIndex) {
                0 -> {
                    curTextChapter?.cancelLayout()
                    withContext(Main) {
                        curTextChapter = textChapter
                        observeHighlightRuleLayout(textChapter)
                    }
                    callBack?.upMenuView()
                    var available = false
                    for (page in textChapter.layoutChannel) {
                        val index = page.index
                        val positionReady = resolvePendingHighlightJump(book, textChapter)
                        if (positionReady && !available && page.containPos(durChapterPos)) {
                            if (upContent) {
                                callBack?.upContent(
                                    offset,
                                    shouldResetPageOffset,
                                    readPositionVersion = readPositionVersion,
                                )
                            }
                            available = true
                        }
                        if (positionReady && upContent && isScroll) {
                            if (max(index - 3, 0) < durPageIndex) {
                                callBack?.upContent(offset, false)
                            }
                        }
                        callBack?.onLayoutPageCompleted(index, page)
                    }
                    resolvePendingHighlightAnchor(book, textChapter)
                    if (upContent) {
                        callBack?.upContent(
                            offset,
                            !available && shouldResetPageOffset,
                            readPositionVersion = readPositionVersion,
                        )
                    }
                    curPageChanged(
                        syncReadAloudFollow = BaseReadAloudService.shouldSyncSpeechNavigation()
                    )
                    callBack?.contentLoadFinish()
                }

                -1 -> {
                    prevTextChapter?.cancelLayout()
                    withContext(Main) {
                        prevTextChapter = textChapter
                        observeHighlightRuleLayout(textChapter)
                    }
                    textChapter.layoutChannel.receiveAsFlow().collect()
                    if (upContent) {
                        callBack?.upContent(
                            offset,
                            shouldResetPageOffset,
                            readPositionVersion = readPositionVersion,
                        )
                    }
                }

                1 -> {
                    nextTextChapter?.cancelLayout()
                    withContext(Main) {
                        nextTextChapter = textChapter
                        observeHighlightRuleLayout(textChapter)
                    }
                    for (page in textChapter.layoutChannel) {
                        if (page.index > 1) {
                            continue
                        }
                        if (upContent) {
                            callBack?.upContent(
                                offset,
                                shouldResetPageOffset,
                                readPositionVersion = readPositionVersion,
                            )
                        }
                    }
                }
            }
        }.onFailure {
            if (it is CancellationException) {
                return@onFailure
            }
            AppLog.put("ChapterProvider ERROR", it)
            appCtx.toastOnUi("ChapterProvider ERROR:\n${it.stackTraceStr}")
        }
    }

    /**
     * 预下载时，章节已完，更新目录
     */
    @Synchronized
    fun upToc() {
        val bookSource = bookSource ?: return
        val book = book ?: return
        if (!book.canUpdate) return
        if (chapterSize - durChapterIndex - 1 >= 3) return
        if (System.currentTimeMillis() - book.lastCheckTime < 600000) return
        book.lastCheckTime = System.currentTimeMillis()
        val oldBook = book.copy()
        WebBook.getChapterList(this, bookSource, book).onSuccess(IO) { cList ->
            ensureActive()
            if (cList.size > chapterSize) {
                if (oldBook.bookUrl == book.bookUrl) {
                    book.update()
                } else {
                    appDb.bookDao.replace(oldBook, book)
                    BookHelp.updateCacheFolder(oldBook, book)
                }
                appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                appDb.bookChapterDao.insert(*cList.toTypedArray())
                onChapterListUpdated(book, false)
                nextTextChapter ?: loadContent(durChapterIndex + 1)
            }
        }
    }

    fun pageAnim(): Int {
        return book?.getPageAnim() ?: ReadBookConfig.pageAnim
    }

    fun setCharset(charset: String) {
        book?.let {
            it.charset = charset
            callBack?.loadChapterList(it)
        }
        saveRead()
    }

    fun saveRead(pageChanged: Boolean = false) {
        if (hasPendingHighlightJump()) return
        val book = book ?: return
        executor.execute {
            kotlin.runCatching {
                book.lastCheckCount = 0
                val durTime = System.currentTimeMillis()
                book.durChapterTime = durTime
                val chapterChanged = book.durChapterIndex != durChapterIndex
                book.durChapterIndex = durChapterIndex
                book.durChapterPos = durChapterPos
                if (!pageChanged || chapterChanged) {
                    appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex)?.let {
                        book.durChapterTitle = it.getDisplayTitle(
                            ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                            book.getUseReplaceRule(),
                            replaceBook = book.toReplaceBook()
                        )
                        SourceCallBack.callBackBook(SourceCallBack.SAVE_READ, bookSource, book, it, durTime.toString())
                    }
                }
                book.update()
            }.onFailure {
                AppLog.put("保存书籍阅读进度信息出错\n$it", it)
            }
        }
    }

    /**
     * 预下载
     */
    private fun preDownload() {
        if (book?.isLocal == true) return
        executor.execute {
            if (AppConfig.preDownloadNum < 2) {
                upToc()
                return@execute
            }
            preDownloadTask?.cancel()
            preDownloadTask = launch(IO) {
                //预下载
                launch {
                    val maxChapterIndex =
                        min(durChapterIndex + AppConfig.preDownloadNum, chapterSize)
                    for (i in durChapterIndex.plus(2)..maxChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
                launch {
                    val minChapterIndex = durChapterIndex - min(5, AppConfig.preDownloadNum)
                    for (i in durChapterIndex.minus(2) downTo minChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
            }
        }
    }

    fun cancelPreDownloadTask() {
        if (contentLoadFinish) {
            preDownloadTask?.cancel()
            downloadScope.coroutineContext.cancelChildren()
        }
    }

    fun onChapterListUpdated(newBook: Book, loadContent: Boolean = true) {
        if (newBook.isSameNameAuthor(book)) {
            val positionAnchor = if (callBack == null) currentPositionAnchor() else null
            book = newBook
            chapterSize = newBook.totalChapterNum
            simulatedChapterSize = newBook.simulatedTotalChapterNum()
            if (simulatedChapterSize > 0 && durChapterIndex > simulatedChapterSize - 1) {
                durChapterIndex = simulatedChapterSize - 1
            }
            callBack?.upMenuView()
            if (callBack == null) {
                clearTextChapter()
                pendingHighlightAnchor = positionAnchor?.takeIf {
                    it.bookUrl == newBook.bookUrl && it.chapterIndex == durChapterIndex
                }
            } else if (loadContent) {
                loadContent(
                    resetPageOffset = true,
                    readPositionVersion = callBack?.readPositionVersion(),
                )
            }
        }
    }

    private fun shouldApplyReadPositionReset(readPositionVersion: Long?): Boolean {
        return readPositionVersion == null ||
            callBack?.isReadPositionVersionCurrent(readPositionVersion) != false
    }

    private fun clearExpiredChapterLoadingJob(clearAll: Boolean = false) {
        val iterator = chapterLoadingJobs.iterator()
        while (iterator.hasNext()) {
            val (index, job) = iterator.next()
            if (clearAll || index !in durChapterIndex - 1..durChapterIndex + 1) {
                job.cancel()
                iterator.remove()
            }
        }
    }

    private fun resolvePendingHighlightJump(
        layoutBook: Book,
        textChapter: TextChapter
    ): Boolean {
        if (curTextChapter !== textChapter) return false
        val pending = pendingHighlightJump
            ?: return pendingHighlightAnchor?.waitForLayout != true
        if (pending.bookUrl != layoutBook.bookUrl ||
            pending.chapterIndex != durChapterIndex ||
            pending.chapterIndex != textChapter.chapter.index ||
            pending.rawPosition != durChapterPos
        ) {
            pendingHighlightJump = null
            return true
        }
        val currentTitleLength = textChapter.layoutTitleLength
        if (currentTitleLength < 0) return false
        durChapterPos = resolveHighlightChapterPosition(
            pending.rawPosition,
            pending.sourceTitleLength,
            currentTitleLength
        )
        pendingHighlightJump = null
        saveRead()
        return pendingHighlightAnchor?.waitForLayout != true
    }

    private fun hasPendingHighlightJump(): Boolean {
        val pending = pendingHighlightJump ?: return false
        if (pending.bookUrl == book?.bookUrl &&
            pending.chapterIndex == durChapterIndex &&
            pending.rawPosition == durChapterPos
        ) {
            return true
        }
        pendingHighlightJump = null
        return false
    }

    private fun resolvePendingHighlightAnchor(
        layoutBook: Book,
        textChapter: TextChapter
    ) {
        val pending = pendingHighlightAnchor ?: return
        if (curTextChapter !== textChapter) return
        if (pending.bookUrl != layoutBook.bookUrl ||
            pending.chapterIndex != durChapterIndex ||
            pending.chapterIndex != textChapter.chapter.index
        ) {
            pendingHighlightAnchor = null
            return
        }
        val currentTitleLength = textChapter.layoutTitleLength.takeIf { it >= 0 } ?: return
        val expectedPosition = resolveHighlightChapterPosition(
            pending.rawPosition,
            pending.sourceTitleLength,
            currentTitleLength
        )
        pendingHighlightAnchor = null
        if (durChapterPos != expectedPosition) return
        val bodyText = chapterText(textChapter).drop(currentTitleLength)
        val bodyPosition = (expectedPosition - currentTitleLength).coerceAtLeast(0)
        durChapterPos = currentTitleLength +
            HighlightAnchor.jumpPos(bodyText, bodyPosition, pending.bookText)
        saveRead()
    }

    private fun currentPositionAnchor(): PendingHighlightAnchor? {
        val currentBook = book ?: return null
        val textChapter = curTextChapter?.takeIf {
            it.isCompleted &&
                it.chapter.index == durChapterIndex &&
                it.chapter.bookUrl == currentBook.bookUrl
        } ?: return null
        val titleLength = textChapter.layoutTitleLength.takeIf { it >= 0 } ?: return null
        val bodyText = chapterText(textChapter).drop(titleLength)
        val bodyPosition = (durChapterPos - titleLength).coerceAtLeast(0)
        val anchorText = bodyText.drop(bodyPosition).take(REFRESH_POSITION_ANCHOR_LENGTH)
        if (anchorText.isEmpty()) return null
        return PendingHighlightAnchor(
            currentBook.bookUrl,
            durChapterIndex,
            durChapterPos,
            titleLength,
            anchorText,
            waitForLayout = true
        )
    }

    private data class PendingHighlightJump(
        val bookUrl: String,
        val chapterIndex: Int,
        val rawPosition: Int,
        val sourceTitleLength: Int
    )

    private data class PendingHighlightAnchor(
        val bookUrl: String,
        val chapterIndex: Int,
        val rawPosition: Int,
        val sourceTitleLength: Int,
        val bookText: String,
        val waitForLayout: Boolean = false
    )

    /**
     * 注册回调
     */
    fun register(cb: CallBack) {
        callBack?.notifyBookChanged()
        callBack = cb
    }

    /**
     * 取消注册回调
     */
    fun unregister(cb: CallBack) {
        if (callBack === cb) {
            callBack = null
        }
        releaseAndCancel()
    }

    private fun releaseAndCancel() {
        msg = null
        preDownloadTask?.cancel()
        invalidateHighlightRuleMatches()
        downloadScope.coroutineContext.cancelChildren()
        coroutineContext.cancelChildren()
        ImageProvider.clear()
        clearExpiredChapterLoadingJob(true)
        if (!CacheBookService.isRun) {
            CacheBook.close()
        }
    }

    interface CallBack : LayoutProgressListener {
        fun upMenuView()

        fun loadChapterList(book: Book)

        fun upContent(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            readPositionVersion: Long? = null,
            success: (() -> Unit)? = null
        )

        fun readPositionVersion(): Long? = null

        fun isReadPositionVersionCurrent(version: Long): Boolean = true

        suspend fun upContentAwait(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            readPositionVersion: Long? = null,
            success: (() -> Unit)? = null
        )

        fun pageChanged()

        fun contentLoadFinish()

        fun upPageAnim(upRecorder: Boolean = false)

        fun notifyBookChanged()

        fun sureNewProgress(progress: BookProgress)

        fun cancelSelect()
    }

}

internal fun BookHighlight.isForBook(book: Book?): Boolean {
    return book != null && bookUrl == book.bookUrl
}

internal fun BookHighlight.isForChapter(book: Book?, chapter: BookChapter): Boolean {
    return isForBook(book) && book?.bookUrl == chapter.bookUrl && chapterUrl == chapter.url
}

internal fun BookHighlight.bindLegacyChapter(
    book: Book?,
    chapter: BookChapter,
    displayTitle: String = chapter.title
): Boolean {
    if (!isForBook(book) || chapterUrl.isNotBlank()) return false
    if (book?.bookUrl != chapter.bookUrl) return false
    if (chapterIndex != chapter.index) return false
    if (chapterName != chapter.title && chapterName != displayTitle) return false
    chapterUrl = chapter.url
    return true
}

internal fun TextChapter.isForBook(book: Book?): Boolean {
    return book != null && chapter.bookUrl == book.bookUrl
}
