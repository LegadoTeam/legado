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
    ) : ChapterCacheResult

    data class Error(val message: String) : ChapterCacheResult
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
    private var originalBookUrl: String? = null
    private var originalChapters = emptyList<BookChapter>()
    private var originalChaptersTask: Coroutine<List<BookChapter>>? = null
    private var tocTask: Coroutine<Pair<List<BookChapter>, BookSource>>? = null
    private var contentTask: Coroutine<String>? = null
    private var cacheTask: Coroutine<Unit>? = null
    private var cacheCommitStarted = false

    val currentOriginalChapter: BookChapter?
        get() = progress.currentChapter(originalChapters)

    val isBatchFinished: Boolean
        get() = progress.isFinished

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
        tocTask?.cancel()
        tocTask = null
        tocState.value = ChapterTocState.Idle
    }

    fun cacheContents(
        sourceBook: Book,
        sourceChapters: List<Pair<BookChapter, String?>>,
        originalBook: Book,
        originalChapter: BookChapter,
        targetPosition: Int,
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
                            nextChapter = advanceOriginalChapter(originalChapter),
                            targetPosition = targetPosition,
                        )
                    )
                }
            }
        }.onError {
            cacheTask = null
            cacheCommitStarted = false
            batchCaching.value = false
            batchCacheResult.value = PendingEvent(
                ChapterCacheResult.Error(it.localizedMessage ?: "获取正文出错")
            )
        }
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
