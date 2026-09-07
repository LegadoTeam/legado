package io.legado.app.ui.book.changesource

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.MutableLiveData
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ChapterSourceMatch
import io.legado.app.help.book.matchChapterSource
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal sealed interface OriginalChaptersState {
    data object Loading : OriginalChaptersState
    data class Success(val chapters: List<BookChapter>) : OriginalChaptersState
    data class Error(val message: String) : OriginalChaptersState
}

internal sealed interface ChapterTocState {
    data object Idle : ChapterTocState
    data class Loading(val book: Book) : ChapterTocState
    data class Success(
        val book: Book,
        val toc: List<BookChapter>,
        val source: BookSource,
    ) : ChapterTocState

    data class Error(val throwable: Throwable) : ChapterTocState
}

internal sealed interface ChapterContentResult {
    data class Success(val content: String) : ChapterContentResult
    data class Error(val message: String) : ChapterContentResult
}

internal sealed interface ChapterCacheResult {
    data class Success(
        val cachedChapterIndex: Int,
        val nextChapter: BookChapter?,
        val targetPosition: Int,
        val automationSessionId: Long? = null,
    ) : ChapterCacheResult

    data class Error(val message: String) : ChapterCacheResult
}

internal sealed interface ChapterSourceAutomationPause {
    data object Ambiguous : ChapterSourceAutomationPause
    data object Missing : ChapterSourceAutomationPause
    data class ContentError(val message: String) : ChapterSourceAutomationPause
}

internal sealed interface ChapterSourceAutomationState {
    data object Idle : ChapterSourceAutomationState
    data class Ready(
        val sessionId: Long,
        val chapter: BookChapter,
        val position: Int,
        val total: Int,
    ) : ChapterSourceAutomationState

    data class Caching(
        val sessionId: Long,
        val chapter: BookChapter,
        val position: Int,
        val total: Int,
        val targetPositions: List<Int>,
    ) : ChapterSourceAutomationState

    data class Paused(
        val sessionId: Long,
        val chapter: BookChapter,
        val position: Int,
        val total: Int,
        val reason: ChapterSourceAutomationPause,
        val targetPositions: List<Int>,
    ) : ChapterSourceAutomationState

    data class Finished(val total: Int) : ChapterSourceAutomationState
}

internal class ChapterSourceAutomationSession(
    val id: Long,
    val originalBook: Book,
    val chapters: List<BookChapter>,
    val targetBook: Book,
    val targetToc: List<BookChapter>,
) {
    var position: Int = 0
        private set
    var stopAfterCurrent: Boolean = false
        private set

    val currentChapter: BookChapter?
        get() = chapters.getOrNull(position)

    val total: Int
        get() = chapters.size

    fun advance(expectedChapterIndex: Int): Boolean {
        if (currentChapter?.index != expectedChapterIndex) return false
        position++
        return true
    }

    fun requestStopAfterCurrent() {
        stopAfterCurrent = true
    }
}

internal fun chapterSourceAutomationRange(
    chapters: List<BookChapter>,
    start: Int,
    endInclusive: Int,
): List<BookChapter> {
    val contentChapters = chapters.filterNot { it.isVolume }
    if (start !in 1..contentChapters.size || endInclusive !in start..contentChapters.size) {
        return emptyList()
    }
    return contentChapters.subList(start - 1, endInclusive)
}

internal class ChapterSourceProgress {
    var chapterIndex: Int = 0
        private set
    var chapterTitle: String = ""
        private set
    var isFinished: Boolean = false
        private set
    private var initialized = false

    fun initialize(chapterIndex: Int, chapterTitle: String) {
        if (initialized) return
        initialized = true
        this.chapterIndex = chapterIndex
        this.chapterTitle = chapterTitle
    }

    fun moveTo(chapter: BookChapter) {
        initialized = true
        isFinished = false
        chapterIndex = chapter.index
        chapterTitle = chapter.title
    }

    fun finish() {
        isFinished = true
    }

    fun currentChapter(chapters: List<BookChapter>): BookChapter? {
        return if (isFinished) null else chapters.firstOrNull { it.index == chapterIndex }
    }

    fun advance(chapters: List<BookChapter>, chapter: BookChapter): BookChapter? {
        val nextChapter = nextChapterSourceOriginal(chapters, chapter.index)
        isFinished = nextChapter == null
        if (nextChapter != null) {
            chapterIndex = nextChapter.index
            chapterTitle = nextChapter.title
        }
        return nextChapter
    }
}

@Suppress("MemberVisibilityCanBePrivate")
class ChangeChapterSourceViewModel(application: Application) :
    ChangeBookSourceViewModel(application) {

    protected override val pinCurrentSource = true
    private val progress = ChapterSourceProgress()
    val chapterIndex: Int
        get() = progress.chapterIndex
    val chapterTitle: String
        get() = progress.chapterTitle
    internal val originalChaptersState = MutableLiveData<OriginalChaptersState>()
    internal val tocState = MutableLiveData<ChapterTocState>(ChapterTocState.Idle)
    val contentLoading = MutableLiveData(false)
    internal val contentResult = MutableLiveData<PendingEvent<ChapterContentResult>>()
    val batchCaching = MutableLiveData(false)
    internal val batchCacheResult = MutableLiveData<PendingEvent<ChapterCacheResult>>()
    internal val automationState = MutableLiveData<ChapterSourceAutomationState>(
        ChapterSourceAutomationState.Idle
    )
    private var originalBookUrl: String? = null
    private var originalChapters = emptyList<BookChapter>()
    private var originalChaptersTask: Coroutine<List<BookChapter>>? = null
    private var tocTask: Coroutine<Pair<List<BookChapter>, BookSource>>? = null
    private var contentTask: Coroutine<String>? = null
    private var cacheTask: Coroutine<Unit>? = null
    private var cacheCommitStarted = false
    private var automationGeneration = 0L
    private var automationSession: ChapterSourceAutomationSession? = null

    val currentOriginalChapter: BookChapter?
        get() = progress.currentChapter(originalChapters)

    val isBatchFinished: Boolean
        get() = progress.isFinished

    val isAutomationActive: Boolean
        get() = automationSession != null

    override fun initData(arguments: Bundle?, book: Book?, fromReadBookActivity: Boolean) {
        super.initData(arguments, book, fromReadBookActivity)
        arguments?.let { bundle ->
            progress.initialize(
                chapterIndex = bundle.getInt("chapterIndex"),
                chapterTitle = bundle.getString("chapterTitle").orEmpty(),
            )
        }
    }

    fun loadContent(
        book: Book,
        chapter: BookChapter,
        nextChapterUrl: String?,
    ) {
        contentTask?.cancel()
        contentLoading.value = true
        contentTask = execute {
            val bookSource = appDb.bookSourceDao.getBookSource(book.origin)
                ?: throw NoStackTraceException("书源不存在")
            WebBook.getContentAwait(bookSource, book, chapter, nextChapterUrl, false)
        }.onSuccess {
            contentTask = null
            contentLoading.value = false
            contentResult.value = PendingEvent(ChapterContentResult.Success(it))
        }.onError {
            contentTask = null
            contentLoading.value = false
            contentResult.value = PendingEvent(
                ChapterContentResult.Error(it.localizedMessage ?: "获取正文出错")
            )
        }
    }

    fun loadOriginalChapters(bookUrl: String) {
        val state = originalChaptersState.value
        if (originalBookUrl == bookUrl &&
            (state is OriginalChaptersState.Loading || state is OriginalChaptersState.Success)
        ) {
            return
        }
        originalBookUrl = bookUrl
        originalChaptersTask?.cancel()
        originalChaptersState.value = OriginalChaptersState.Loading
        originalChaptersTask = execute {
            appDb.bookChapterDao.getChapterList(bookUrl)
        }.onSuccess { chapters ->
            originalChaptersTask = null
            originalChapters = chapters
            originalChaptersState.value = OriginalChaptersState.Success(chapters)
        }.onError {
            originalChaptersTask = null
            originalChaptersState.value = OriginalChaptersState.Error(
                it.localizedMessage ?: "获取目录出错"
            )
        }
    }

    fun loadToc(book: Book) {
        if (isAutomationActive || batchCaching.value == true) return
        cancelContent()
        tocTask?.cancel()
        tocState.value = ChapterTocState.Loading(book)
        tocTask = getToc(book, { toc, source ->
            tocTask = null
            tocState.value = ChapterTocState.Success(book, toc, source)
        }, { throwable ->
            tocTask = null
            tocState.value = ChapterTocState.Error(throwable)
        })
    }

    fun clearToc() {
        cancelContent()
        tocTask?.cancel()
        tocTask = null
        tocState.value = ChapterTocState.Idle
    }

    private fun cancelContent() {
        contentTask?.cancel()
        contentTask = null
        contentLoading.value = false
    }

    fun cacheContents(
        sourceBook: Book,
        sourceChapters: List<Pair<BookChapter, String?>>,
        originalBook: Book,
        originalChapter: BookChapter,
        targetPosition: Int,
    ) {
        cacheContents(
            sourceBook = sourceBook,
            sourceChapters = sourceChapters,
            originalBook = originalBook,
            originalChapter = originalChapter,
            targetPosition = targetPosition,
            automationSessionId = null,
            automationTargetPositions = emptyList(),
        )
    }

    private fun cacheContents(
        sourceBook: Book,
        sourceChapters: List<Pair<BookChapter, String?>>,
        originalBook: Book,
        originalChapter: BookChapter,
        targetPosition: Int,
        automationSessionId: Long?,
        automationTargetPositions: List<Int>,
    ) {
        if (batchCaching.value == true) return
        cacheCommitStarted = false
        batchCaching.value = true
        cacheTask = execute {
            val bookSource = appDb.bookSourceDao.getBookSource(sourceBook.origin)
                ?: throw NoStackTraceException("书源不存在")
            val contents = sourceChapters.map { (chapter, nextChapterUrl) ->
                WebBook.getContentAwait(
                    bookSource,
                    sourceBook,
                    chapter,
                    nextChapterUrl,
                    false,
                )
            }
            val mergedContent = mergeChapterSourceContents(contents)
            if (mergedContent.isBlank()) throw NoStackTraceException("正文为空")
            ensureActive()
            withContext(Main) {
                cacheCommitStarted = true
            }
            withContext(NonCancellable) {
                BookHelp.saveText(
                    originalBook,
                    originalChapter,
                    mergedContent,
                    saveChapterMetadata = true,
                )
                withContext(Main) {
                    cacheTask = null
                    cacheCommitStarted = false
                    batchCaching.value = false
                    batchCacheResult.value = PendingEvent(
                        ChapterCacheResult.Success(
                            cachedChapterIndex = originalChapter.index,
                            nextChapter = if (automationSessionId == null) {
                                advanceOriginalChapter(originalChapter)
                            } else {
                                null
                            },
                            targetPosition = targetPosition,
                            automationSessionId = automationSessionId,
                        )
                    )
                }
            }
        }.onError { throwable ->
            cacheTask = null
            cacheCommitStarted = false
            batchCaching.value = false
            val message = throwable.localizedMessage ?: "获取正文出错"
            if (automationSessionId == null || pauseAutomationAfterError(
                    automationSessionId,
                    originalChapter,
                    automationTargetPositions,
                    message,
                )
            ) {
                batchCacheResult.value = PendingEvent(
                    ChapterCacheResult.Error(message)
                )
            }
        }
    }

    fun automationRangeDefaults(): IntRange? {
        val contentChapters = originalChapters.filterNot { it.isVolume }
        val currentPosition = contentChapters.indexOfFirst { it.index == progress.chapterIndex }
        if (currentPosition < 0) return null
        return (currentPosition + 1)..contentChapters.size
    }

    fun startAutomation(
        originalBook: Book,
        targetBook: Book,
        targetToc: List<BookChapter>,
        start: Int,
        endInclusive: Int,
    ): Boolean {
        if (isAutomationActive || batchCaching.value == true) return false
        val chapters = chapterSourceAutomationRange(originalChapters, start, endInclusive)
        if (chapters.isEmpty()) return false
        val session = ChapterSourceAutomationSession(
            id = ++automationGeneration,
            originalBook = originalBook.copy(),
            chapters = chapters.map { it.copy() },
            targetBook = targetBook.copy(),
            targetToc = targetToc.map { it.copy() },
        )
        automationSession = session
        progress.moveTo(requireNotNull(session.currentChapter))
        automationState.value = readyAutomationState(session)
        return true
    }

    fun runNextAutomationIfReady() {
        val state = automationState.value as? ChapterSourceAutomationState.Ready ?: return
        val session = automationSession?.takeIf { it.id == state.sessionId } ?: return
        val chapter = session.currentChapter?.takeIf { it.index == state.chapter.index } ?: return
        when (val match = matchChapterSource(chapter, session.targetToc)) {
            is ChapterSourceMatch.Unique -> cacheAutomationPositions(
                session,
                chapter,
                listOf(match.targetPosition),
            )

            is ChapterSourceMatch.Ambiguous -> pauseAutomation(
                session,
                chapter,
                ChapterSourceAutomationPause.Ambiguous,
                match.targetPositions,
            )

            ChapterSourceMatch.Missing -> pauseAutomation(
                session,
                chapter,
                ChapterSourceAutomationPause.Missing,
                emptyList(),
            )
        }
    }

    fun cacheAutomationSelection(targetPositions: List<Int>) {
        val state = automationState.value as? ChapterSourceAutomationState.Paused ?: return
        val session = automationSession?.takeIf { it.id == state.sessionId } ?: return
        val chapter = session.currentChapter?.takeIf { it.index == state.chapter.index } ?: return
        val positions = targetPositions.distinct().sorted().filter { position ->
            session.targetToc.getOrNull(position)?.isVolume == false
        }
        if (positions.isEmpty()) return
        cacheAutomationPositions(session, chapter, positions)
    }

    fun acknowledgeAutomationCache(sessionId: Long, chapterIndex: Int): Boolean {
        val state = automationState.value as? ChapterSourceAutomationState.Caching ?: return false
        val session = automationSession?.takeIf { it.id == sessionId } ?: return false
        return state.sessionId == sessionId && advanceAutomation(session, chapterIndex)
    }

    fun skipAutomationChapter(): Boolean {
        val state = automationState.value
        val sessionId = when (state) {
            is ChapterSourceAutomationState.Ready -> state.sessionId
            is ChapterSourceAutomationState.Paused -> state.sessionId
            else -> return false
        }
        val session = automationSession?.takeIf { it.id == sessionId } ?: return false
        val chapter = session.currentChapter ?: return false
        return advanceAutomation(session, chapter.index)
    }

    private fun advanceAutomation(
        session: ChapterSourceAutomationSession,
        chapterIndex: Int,
    ): Boolean {
        if (!session.advance(chapterIndex)) return false
        val nextChapter = session.currentChapter
        if (nextChapter == null) {
            progress.finish()
            automationSession = null
            automationState.value = ChapterSourceAutomationState.Finished(session.total)
        } else if (session.stopAfterCurrent) {
            progress.moveTo(nextChapter)
            automationSession = null
            automationState.value = ChapterSourceAutomationState.Idle
        } else {
            progress.moveTo(nextChapter)
            automationState.value = readyAutomationState(session)
        }
        return true
    }

    fun stopAutomation() {
        if (cacheCommitStarted) {
            automationSession?.requestStopAfterCurrent()
            return
        }
        automationGeneration++
        automationSession = null
        automationState.value = ChapterSourceAutomationState.Idle
        cancelCacheContents()
    }

    private fun cacheAutomationPositions(
        session: ChapterSourceAutomationSession,
        chapter: BookChapter,
        targetPositions: List<Int>,
    ) {
        if (batchCaching.value == true) return
        val sourceChapters = targetPositions.mapNotNull { position ->
            session.targetToc.getOrNull(position)?.takeUnless { it.isVolume }?.let {
                it to session.targetToc.getOrNull(position + 1)?.url
            }
        }
        if (sourceChapters.size != targetPositions.size) return
        automationState.value = ChapterSourceAutomationState.Caching(
            session.id,
            chapter,
            session.position,
            session.total,
            targetPositions,
        )
        cacheContents(
            sourceBook = session.targetBook,
            sourceChapters = sourceChapters,
            originalBook = session.originalBook,
            originalChapter = chapter,
            targetPosition = targetPositions.last() + 1,
            automationSessionId = session.id,
            automationTargetPositions = targetPositions,
        )
    }

    private fun pauseAutomation(
        session: ChapterSourceAutomationSession,
        chapter: BookChapter,
        reason: ChapterSourceAutomationPause,
        targetPositions: List<Int>,
    ) {
        automationState.value = ChapterSourceAutomationState.Paused(
            session.id,
            chapter,
            session.position,
            session.total,
            reason,
            targetPositions,
        )
    }

    private fun pauseAutomationAfterError(
        sessionId: Long,
        chapter: BookChapter,
        targetPositions: List<Int>,
        message: String,
    ): Boolean {
        val session = automationSession?.takeIf { it.id == sessionId } ?: return false
        val currentChapter = session.currentChapter?.takeIf { it.index == chapter.index }
            ?: return false
        pauseAutomation(
            session,
            currentChapter,
            ChapterSourceAutomationPause.ContentError(message),
            targetPositions,
        )
        return true
    }

    private fun readyAutomationState(
        session: ChapterSourceAutomationSession,
    ): ChapterSourceAutomationState.Ready {
        return ChapterSourceAutomationState.Ready(
            session.id,
            requireNotNull(session.currentChapter),
            session.position,
            session.total,
        )
    }

    fun cancelCacheContents() {
        if (cacheCommitStarted) return
        cacheTask?.cancel()
        cacheTask = null
        batchCaching.value = false
    }

    fun advanceOriginalChapter(chapter: BookChapter): BookChapter? {
        return progress.advance(originalChapters, chapter)
    }

}

internal fun mergeChapterSourceContents(contents: List<String>): String = buildString {
    contents.forEachIndexed { index, content ->
        if (index > 0) {
            val previous = contents[index - 1]
            val lastContentIndex = previous.indexOfLast { !it.isWhitespace() }
            if (lastContentIndex >= 0 && previous[lastContentIndex] in "。！？.!?" &&
                (lastContentIndex + 1 until previous.length)
                    .none { previous[it] in "\r\n" }
            ) {
                append('\n')
            }
        }
        append(content)
    }
}

internal fun nextChapterSourceOriginal(
    chapters: List<BookChapter>,
    currentIndex: Int,
): BookChapter? = chapters.firstOrNull { !it.isVolume && it.index > currentIndex }
