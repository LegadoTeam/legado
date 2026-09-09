package io.legado.app.ui.book.read

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.replaceBookAfterSourceChange
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.book.removeType
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ImageProvider
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.SourceCallBack
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.utils.DocumentUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.mapParallelSafe
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * 阅读界面数据处理
 */
class ReadBookViewModel(application: Application) : BaseViewModel(application) {
    val permissionDenialLiveData = MutableLiveData<Int>()
    var isInitFinish = false
    var searchContentQuery = ""
    var searchResultList: List<SearchResult>? = null
    var searchResultIndex: Int = 0
    private var changeSourceCoroutine: Coroutine<*>? = null
    private var resourceRefreshCoroutine: Coroutine<*>? = null
    private var resourceTheme: Pair<String, List<Int>>? = null

    init {
        AppConfig.detectClickArea()
    }

    fun initReadBookConfig(intent: Intent) {
        val bookUrl = intent.getStringExtra("bookUrl")
        val book = when {
            bookUrl.isNullOrEmpty() -> appDb.bookDao.lastReadBook
            else -> appDb.bookDao.getBook(bookUrl)
        } ?: return
        ReadBook.upReadBookConfig(book)
    }

    /**
     * 初始化
     */
    fun initData(intent: Intent, success: (() -> Unit)? = null) {
        execute {
            ReadBook.inBookshelf = intent.getBooleanExtra("inBookshelf", true)
            val hasHighlightTarget =
                intent.hasExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH)
            ReadBook.chapterChanged =
                intent.getBooleanExtra("chapterChanged", false) || hasHighlightTarget
            val bookUrl = intent.getStringExtra("bookUrl")
            val book = when {
                bookUrl.isNullOrEmpty() -> appDb.bookDao.lastReadBook
                else -> appDb.bookDao.getBook(bookUrl)
            } ?: ReadBook.book
            when {
                book != null -> initBook(book)
                else -> {
                    ReadBook.upMsg(context.getString(R.string.no_book))
                    AppLog.put("未找到书籍\nbookUrl:$bookUrl")
                }
            }
            val index = intent.getIntExtra("index", -1)
            val chapterPos = intent.getIntExtra("chapterPos", -1)
            val highlightLayoutTitleLength = intent.takeIf { hasHighlightTarget }
                ?.getIntExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH, -1)
            val highlightAnchorText = intent.takeIf { hasHighlightTarget }
                ?.getStringExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT)
            if (index >= 0 && chapterPos >= 0) { //从目录定位正文，有进度传递
                if (hasHighlightTarget) {
                    intent.removeExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH)
                    intent.removeExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT)
                    intent.removeExtra("index")
                    intent.removeExtra("chapterPos")
                }
                ReadBook.saveCurrentBookProgress() //启用恢复进度提示
                openChapter(index, chapterPos, highlightLayoutTitleLength, highlightAnchorText)
            }
        }.onSuccess {
            success?.invoke()
        }.onError {
            val msg = "初始化数据失败\n${it.localizedMessage}"
            ReadBook.upMsg(msg)
            AppLog.put(msg, it)
        }.onFinally {
            ReadBook.saveRead()
        }
    }

    private suspend fun initBook(book: Book) {
        if (resourceTheme?.first != book.bookUrl) resourceTheme = currentResourceTheme(book)
        val isSameBook = ReadBook.book?.bookUrl == book.bookUrl
        if (isSameBook) {
            ReadBook.upData(book)
        } else {
            ReadBook.resetData(book)
        }
        isInitFinish = true
        if (!book.isLocal && book.tocUrl.isEmpty() && !loadBookInfo(book)) {
            return
        }
        if (book.isLocal && !checkLocalBookFileExist(book)) {
            return
        }
        if ((ReadBook.chapterSize == 0 || book.isLocalModified()) && !loadChapterListAwait(book)) {
            return
        }
        ReadBook.upMsg(null)
        if (!isSameBook) {
            ReadBook.loadContent(
                resetPageOffset = true,
                readPositionVersion = ReadBook.callBack?.readPositionVersion(),
            ) {
                ReadBook.bookSource?.let {
                    SourceCallBack.callBackBook(SourceCallBack.START_READ, it, book, ReadBook.curTextChapter?.chapter)
                }
            }
        } else {
            ReadBook.loadOrUpContent {
                ReadBook.bookSource?.let {
                    SourceCallBack.callBackBook(SourceCallBack.START_READ, it, book, ReadBook.curTextChapter?.chapter)
                }
            }
        }
        if (ReadBook.chapterChanged) {
            // 有章节跳转不同步阅读进度
            ReadBook.chapterChanged = false
        } else if (!(isSameBook && BaseReadAloudService.isRun) && ReadBook.inBookshelf) {
            if (AppConfig.syncBookProgressPlus) {
                ReadBook.syncProgress({ progress -> ReadBook.callBack?.sureNewProgress(progress) })
            } else {
                syncBookProgress(book)
            }
        }
        if (!book.isLocal && ReadBook.bookSource == null) {
            autoChangeSource(book.name, book.author)
            return
        }
    }

    private fun checkLocalBookFileExist(book: Book): Boolean {
        try {
            LocalBook.getBookInputStream(book).use {}
            return true
        } catch (e: Throwable) {
            ReadBook.upMsg("打开本地书籍出错: ${e.localizedMessage}")
            if (e is SecurityException || e is FileNotFoundException) {
                permissionDenialLiveData.postValue(0)
            }
            return false
        }
    }

    /**
     * 加载详情页
     */
    private suspend fun loadBookInfo(book: Book): Boolean {
        val source = ReadBook.bookSource ?: return true
        try {
            WebBook.getBookInfoAwait(source, book, canReName = false)
            return true
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            ReadBook.upMsg("详情页出错: ${e.localizedMessage}")
            return false
        }
    }

    /**
     * 加载目录
     */
    fun loadChapterList(book: Book) {
        execute {
            if (loadChapterListAwait(book)) {
                ReadBook.upMsg(null)
            }
        }
    }

    private suspend fun loadChapterListAwait(book: Book): Boolean {
        if (book.isLocal) {
            kotlin.runCatching {
                LocalBook.getChapterList(book).let {
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    appDb.bookChapterDao.insert(*it.toTypedArray())
                    book.update()
                    ReadBook.onChapterListUpdated(book)
                }
                return true
            }.onFailure {
                when (it) {
                    is SecurityException, is FileNotFoundException -> {
                        permissionDenialLiveData.postValue(1)
                    }

                    else -> {
                        AppLog.put("LoadTocError:${it.localizedMessage}", it)
                        ReadBook.upMsg("LoadTocError:${it.localizedMessage}")
                    }
                }
                return false
            }
        } else {
            ReadBook.bookSource?.let {
                val oldBook = book.copy()
                WebBook.getChapterListAwait(it, book, true)
                    .onSuccess { cList ->
                        if (oldBook.bookUrl == book.bookUrl) {
                            book.update()
                        } else {
                            appDb.bookDao.replace(oldBook, book)
                            BookHelp.updateCacheFolder(oldBook, book)
                        }
                        appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                        appDb.bookChapterDao.insert(*cList.toTypedArray())
                        ReadBook.onChapterListUpdated(book)
                        return true
                    }.onFailure {
                        currentCoroutineContext().ensureActive()
                        ReadBook.upMsg(context.getString(R.string.error_load_toc))
                        return false
                    }
            }
        }
        return true
    }

    /**
     * 同步进度
     */
    fun syncBookProgress(
        book: Book,
        alertSync: ((progress: BookProgress) -> Unit)? = null
    ) {
        if (!AppConfig.syncBookProgress) return
        execute {
            AppWebDav.getBookProgress(book)
        }.onError {
            AppLog.put("拉取阅读进度失败《${book.name}》\n${it.localizedMessage}", it)
        }.onSuccess { progress ->
            progress ?: return@onSuccess
            if (progress.durChapterIndex == book.durChapterIndex && progress.durChapterPos == book.durChapterPos) {
                return@onSuccess
            }
            if (progress.durChapterIndex < book.durChapterIndex ||
                (progress.durChapterIndex == book.durChapterIndex
                        && progress.durChapterPos < book.durChapterPos)
            ) {
                alertSync?.invoke(progress)
            } else if (progress.durChapterIndex < book.simulatedTotalChapterNum()) {
                ReadBook.setProgress(progress)
                AppLog.put("自动同步阅读进度成功《${book.name}》 ${progress.durChapterTitle}")
                context.toastOnUi("已同步最新阅读进度")
            }
        }
    }

    /**
     * 换源
     */
    fun changeTo(book: Book, toc: List<BookChapter>, onSuccess: () -> Unit = {}) {
        changeSourceCoroutine?.cancel()
        changeSourceCoroutine = execute {
            ReadBook.upMsg(context.getString(R.string.loading))
            ReadBook.upReadTime()
            ReadBook.book?.migrateTo(book, toc)
            book.removeType(BookType.updateError)
            replaceBookAfterSourceChange(ReadBook.book, book, toc)
            ReadBook.resetData(book)
            ReadBook.upMsg(null)
            ReadBook.loadContent(resetPageOffset = true)
        }.onSuccess {
            onSuccess()
        }.onError {
            AppLog.put("换源失败\n$it", it, true)
            ReadBook.upMsg(null)
        }.onFinally {
            postEvent(EventBus.SOURCE_CHANGED, book.bookUrl)
        }
    }

    /**
     * 自动换源
     */
    private fun autoChangeSource(name: String, author: String) {
        if (!AppConfig.autoChangeSource) return
        execute {
            val sources = appDb.bookSourceDao.allTextEnabledPart
            flow {
                for (source in sources) {
                    source.getBookSource()?.let {
                        emit(it)
                    }
                }
            }.onStart {
                ReadBook.upMsg(context.getString(R.string.source_auto_changing))
            }.mapParallelSafe(AppConfig.threadCount) { source ->
                val book = WebBook.preciseSearchAwait(source, name, author).getOrThrow()
                if (book.tocUrl.isEmpty()) {
                    WebBook.getBookInfoAwait(source, book)
                }
                val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
                val chapter = toc.getOrElse(book.durChapterIndex) {
                    toc.last()
                }
                val nextChapter = toc.getOrElse(chapter.index) {
                    toc.first()
                }
                WebBook.getContentAwait(
                    bookSource = source,
                    book = book,
                    bookChapter = chapter,
                    nextChapterUrl = nextChapter.url
                )
                book to toc
            }.take(1).onEach { (book, toc) ->
                changeTo(book, toc)
            }.onEmpty {
                throw NoStackTraceException("没有合适书源")
            }.onCompletion {
                ReadBook.upMsg(null)
            }.catch {
                AppLog.put("自动换源失败\n${it.localizedMessage}", it)
                context.toastOnUi("自动换源失败\n${it.localizedMessage}")
            }.collect()
        }
    }

    fun openChapter(
        index: Int,
        durChapterPos: Int = 0,
        highlightLayoutTitleLength: Int? = null,
        highlightAnchorText: String? = null,
        pdfPageIndex: Int? = null,
        success: (() -> Unit)? = null
    ) {
        ReadBook.openChapter(
            index,
            durChapterPos,
            highlightLayoutTitleLength = highlightLayoutTitleLength,
            highlightAnchorText = highlightAnchorText,
            pdfPageIndex = pdfPageIndex,
            success = success
        )
    }

    fun removeFromBookshelf(success: (() -> Unit)?) {
        val book = ReadBook.book
        Coroutine.async {
            book?.delete()
        }.onSuccess {
            success?.invoke()
        }
    }

    fun upBookSource(success: (() -> Unit)?) {
        execute {
            ReadBook.book?.let { book ->
                ReadBook.bookSource = appDb.bookSourceDao.getBookSource(book.origin)
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    fun refreshContentDur(book: Book) {
        val index = ReadBook.durChapterIndex
        execute {
            appDb.bookChapterDao.getChapter(book.bookUrl, index)
                ?.let { chapter ->
                    BookHelp.delContent(book, chapter)
                    CacheBook.invalidateChapters(book.bookUrl, index..index)
                    withContext(Main) {
                        if (ReadBook.book?.bookUrl == book.bookUrl) {
                            ReadBook.clearResourceChapters(index..index)
                            ReadBook.loadContent(index, resetPageOffset = false)
                        }
                    }
                }
        }
    }

    private fun currentResourceTheme(book: Book) = book.bookUrl to listOf(
        ReadBookConfig.styleSelect, ReadBookConfig.textColor, ReadBookConfig.textAccentColor,
        if (AppConfig.isNightTheme) 1 else 0, if (AppConfig.isEInkMode) 1 else 0,
    )

    fun resourceThemeChanged(book: Book): Boolean =
        resourceTheme != null && resourceTheme != currentResourceTheme(book)

    fun refreshResources(book: Book, includePreloaded: Boolean) {
        val source = ReadBook.bookSource ?: return
        val currentIndex = ReadBook.durChapterIndex
        val before = if (includePreloaded) maxOf(1, minOf(5, AppConfig.preDownloadNum)) else 0
        val after = if (includePreloaded) maxOf(1, AppConfig.preDownloadNum) else 0
        val indexes = maxOf(0, currentIndex - before)..minOf(ReadBook.chapterSize - 1, currentIndex + after)
        val images = ReadBook.resourceImageSources(indexes).toMutableSet()
        val theme = currentResourceTheme(book)
        resourceRefreshCoroutine?.cancel()
        resourceRefreshCoroutine = execute {
            val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl, indexes.first, indexes.last)
            chapters.forEach { chapter ->
                BookHelp.getContent(book, chapter)?.let { content ->
                    BookHelp.flowImages(chapter, content).collect { images.add(it) }
                    val processed = ContentProcessor.get(book).getContent(book, chapter, content,
                        includeTitle = false)
                    BookHelp.flowImages(chapter, processed.textList.joinToString("\n"))
                        .collect { images.add(it) }
                }
            }
            chapters.forEach { chapter ->
                ensureActive()
                BookHelp.delContent(book, chapter)
            }
            CacheBook.invalidateChapters(book.bookUrl, indexes)
            withContext(Main) {
                if (ReadBook.book?.bookUrl == book.bookUrl) {
                    ReadBook.clearResourceChapters(indexes)
                    ReadBook.callBack?.upContent()
                }
                // Evict/recycle only between UI frames, after detaching the old layouts.
                images.forEach { src ->
                    ensureActive()
                    ImageProvider.clearImage(book, src)
                }
                if (ReadBook.book?.bookUrl == book.bookUrl) {
                    if (ReadBook.durChapterIndex in indexes) ReadBook.loadContent(false)
                }
            }
            // Explicit refresh also fetches images for chapters beyond the three rendered layouts.
            chapters.sortedBy { kotlin.math.abs(it.index - currentIndex) }.forEach { chapter ->
                ensureActive()
                if (!chapter.isVolume) {
                    val result = CacheBook.getOrCreate(source, book).downloadAwait(chapter)
                    val content = BookHelp.getContent(book, chapter) ?: throw NoStackTraceException(result)
                    BookHelp.saveImages(source, book, chapter, content)
                }
            }
        }.onSuccess {
            if (ReadBook.book?.bookUrl == book.bookUrl) resourceTheme = theme
        }
    }

    fun refreshContentAfter(book: Book) {
        val indexes = ReadBook.durChapterIndex until book.totalChapterNum
        execute {
            appDb.bookChapterDao.getChapterList(
                book.bookUrl,
                indexes.first,
                book.totalChapterNum
            ).forEach { chapter ->
                BookHelp.delContent(book, chapter)
            }
            CacheBook.invalidateChapters(book.bookUrl, indexes)
            withContext(Main) {
                if (ReadBook.book?.bookUrl == book.bookUrl) {
                    ReadBook.clearResourceChapters(indexes)
                    ReadBook.loadContent(false)
                }
            }
        }
    }

    fun refreshContentAll(book: Book) {
        execute {
            BookHelp.clearCache(book)
            ReadBook.loadContent(false)
        }
    }

    /**
     * 保存内容
     */
    fun saveContent(book: Book, content: String) {
        execute {
            appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?.let { chapter ->
                    BookHelp.saveText(book, chapter, content)
                    ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
                }
        }
    }

    /**
     * 反转内容
     */
    fun reverseContent(book: Book) {
        val chapterIndex = ReadBook.durChapterIndex
        execute {
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                ?: return@execute
            if (BookHelp.reverseContent(book, chapter) &&
                ReadBook.book?.bookUrl == book.bookUrl && ReadBook.durChapterIndex == chapterIndex) {
                ReadBook.loadContent(chapterIndex, resetPageOffset = false)
            }
        }
    }

    /**
     * 内容搜索跳转
     */
    fun searchResultPositions(
        textChapter: TextChapter,
        searchResult: SearchResult
    ): Array<Int> {
        // calculate search result's pageIndex
        val pages = textChapter.pages
        val content = textChapter.getContent()
        var queryLength = searchContentQuery.length

        var index: Int
        if (searchResult.isRegex) {
            val regex = Regex(searchContentQuery)
            val matches = regex.findAll(content)
            val match = matches.elementAtOrNull(searchResult.resultCountWithinChapter)
            queryLength = match?.value?.length ?: 0
            index = match?.range?.first ?: -1
        } else {
            var count = 0
            index = content.indexOf(searchContentQuery)
            while (count != searchResult.resultCountWithinChapter) {
                index = content.indexOf(searchContentQuery, index + queryLength)
                count += 1
            }
        }
        val contentPosition = index
        var pageIndex = 0
        var length = pages[pageIndex].text.length
        while (length < contentPosition && pageIndex + 1 < pages.size) {
            pageIndex += 1
            length += pages[pageIndex].text.length
        }

        // calculate search result's lineIndex
        val currentPage = pages[pageIndex]
        val curTextLines = currentPage.lines
        var lineIndex = 0
        var curLine = curTextLines[lineIndex]
        length = length - currentPage.text.length + curLine.text.length
        if (curLine.isParagraphEnd) length++
        while (length <= contentPosition && lineIndex + 1 < curTextLines.size) {
            lineIndex += 1
            curLine = curTextLines[lineIndex]
            length += curLine.text.length
            if (curLine.isParagraphEnd) length++
        }

        // charIndex
        val currentLine = currentPage.lines[lineIndex]
        var curLineLength = currentLine.text.length
        if (currentLine.isParagraphEnd) curLineLength++
        length -= curLineLength

        val charIndex = contentPosition - length
        var addLine = 0
        var charIndex2 = 0
        // change line
        if ((charIndex + queryLength) > curLineLength) {
            addLine = 1
            charIndex2 = charIndex + queryLength - curLineLength - 1
        }
        // changePage
        if ((lineIndex + addLine + 1) > currentPage.lines.size) {
            addLine = -1
            charIndex2 = charIndex + queryLength - curLineLength - 1
        }
        return arrayOf(pageIndex, lineIndex, charIndex, addLine, charIndex2, queryLength)
    }

    /**
     * 翻转删除重复标题
     */
    fun reverseRemoveSameTitle() {
        execute {
            val book = ReadBook.book ?: return@execute
            val textChapter = ReadBook.curTextChapter ?: return@execute
            BookHelp.setRemoveSameTitle(
                book, textChapter.chapter, !textChapter.sameTitleRemoved
            )
            ReadBook.loadContent(ReadBook.durChapterIndex)
        }
    }

    /**
     * 刷新图片
     */
    fun refreshImage(src: String) {
        val book = ReadBook.book ?: return
        execute {
            ImageProvider.clearImage(book, src)
        }.onSuccess {
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.loadContent(false)
            }
        }
    }

    /**
     * 保存图片
     */
    fun saveImage(src: String?, uri: Uri) {
        src ?: return
        val book = ReadBook.book ?: return
        execute {
            val image = BookHelp.getImage(book, src)
            FileInputStream(image).use { input ->
                if (uri.isContentScheme()) {
                    DocumentFile.fromTreeUri(context, uri)?.let { doc ->
                        val imageDoc = DocumentUtils.createFileIfNotExist(doc, image.name)!!
                        context.contentResolver.openOutputStream(imageDoc.uri)!!.use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    val dir = File(uri.path ?: uri.toString())
                    val file = FileUtils.createFileIfNotExist(dir, image.name)
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }.onError {
            AppLog.put("保存图片出错\n${it.localizedMessage}", it)
            context.toastOnUi("保存图片出错\n${it.localizedMessage}")
        }
    }

    /**
     * 替换规则变化
     */
    fun replaceRuleChanged() {
        execute {
            ReadBook.book?.let {
                ContentProcessor.get(it.name, it.origin).upReplaceRules()
                ReadBook.clearTextChapter()
                ReadBook.loadContent(resetPageOffset = false)
            }
        }
    }

    fun disableSource() {
        execute {
            ReadBook.bookSource?.let {
                it.enabled = false
                appDb.bookSourceDao.update(it)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (BaseReadAloudService.isRun && BaseReadAloudService.pause) {
            ReadAloud.stop(context)
        }
    }

}
