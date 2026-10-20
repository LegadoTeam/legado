package io.legado.app.ui.book.searchContent


import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.book.read.findContentMatches
import io.legado.app.utils.ChineseUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.regex.PatternSyntaxException

class SearchContentViewModel(application: Application) : BaseViewModel(application) {
    companion object {
        /*
         * 三个搜索选项都记住上次选择 (AppConfig), 首次安装取默认值:
         * 范围=全书、替换=关、正则=关 —— 与改动前的启动状态完全一致。
         */

        /** 替换净化 */
        var replaceEnabled: Boolean
            get() = AppConfig.contentSearchReplace
            set(value) {
                AppConfig.contentSearchReplace = value
            }

        /** 正则模式 */
        var regexReplace: Boolean
            get() = AppConfig.contentSearchRegex
            set(value) {
                AppConfig.contentSearchRegex = value
            }

        /**
         * 搜索范围: 记住上次的选择 (AppConfig), 首次安装默认全书。
         * 只把读写委托给配置, 调用方写法不变。
         */
        var searchScope: ContentSearchScope
            get() = ContentSearchScope.values()
                .getOrElse(AppConfig.contentSearchScope) { ContentSearchScope.ALL }
            set(value) {
                AppConfig.contentSearchScope = value.ordinal
            }
    }
    var bookUrl: String = ""
    var book: Book? = null
    internal var searchAnchor: ContentSearchAnchor? = null
    private var contentProcessor: ContentProcessor? = null
    var hasSearchState = false
    var lastQuery: String = ""
    var searchResultMessage: Int? = null
    var searchResultCounts = 0
    val cacheChapterNames = hashSetOf<String>()
    val searchResultList: MutableList<SearchResult> = mutableListOf()

    fun initBook(bookUrl: String, success: () -> Unit) {
        this.bookUrl = bookUrl
        execute {
            book = appDb.bookDao.getBook(bookUrl)
            book?.let {
                contentProcessor = ContentProcessor.get(it.name, it.origin)
            }
        }.onSuccess {
            success.invoke()
        }
    }

    internal suspend fun searchChapter(
        query: String,
        chapter: BookChapter,
        options: ContentSearchOptions,
        anchor: ContentSearchAnchor? = null,
    ): List<SearchResult> {
        val searchResultsWithinChapter: MutableList<SearchResult> = mutableListOf()
        val book = book ?: return searchResultsWithinChapter
        val chapterContent = BookHelp.getContent(book, chapter) ?: run {
            if (anchor != null) throw ContentSearchAnchorException()
            return searchResultsWithinChapter
        }
        currentCoroutineContext().ensureActive()
        chapter.title = when (AppConfig.chineseConverterType) {
            1 -> ChineseUtils.t2s(chapter.title)
            2 -> ChineseUtils.s2t(chapter.title)
            else -> chapter.title
        }
        currentCoroutineContext().ensureActive()
        val processed = contentProcessor!!.getContent(
            book, chapter, chapterContent, useReplace = options.replace
        )
        val (mContent, titleLength) = if (options.scope == ContentSearchScope.ALL) {
            processed.toString() to processed.titleLength
        } else {
            directionalContentSearchText(processed.toString(), processed.titleLength,
                processed.displayTitle.orEmpty(), options.splitTitle, options.hideTitle, chapter.isVolume,
                ReadBookConfig.paragraphIndent)
        }
        val searchText = if (options.scope == ContentSearchScope.ALL) null else
            ContentSearchText(chapter.index, mContent, titleLength)
        val anchorPosition = anchor?.let { searchText?.resolve(it) }
        if (anchor != null && anchorPosition == null) throw ContentSearchAnchorException()
        val positions = searchPosition(mContent, query, options.regex)
        positions.forEachIndexed { index, position ->
            currentCoroutineContext().ensureActive()
            val construct = getResultAndQueryIndex(mContent, position, query)
            val result = SearchResult(
                resultCountWithinChapter = index,
                resultText = construct.second,
                chapterTitle = chapter.title,
                query = query,
                chapterIndex = chapter.index,
                queryIndexInResult = construct.first,
                queryIndexInChapter = position,
                isRegex = options.regex,
                contentAnchor = searchText?.anchor(position)
            )
            searchResultsWithinChapter.add(result)
        }
        return selectContentSearchResults(searchResultsWithinChapter, options.scope, anchorPosition)
    }

    private suspend fun searchPosition(content: String, pattern: String, regex: Boolean): List<Int> {
        val context = currentCoroutineContext()
        return try {
            findContentMatches(content, pattern, regex, matchCase = true) { context.ensureActive() }
                .map { it.first }
        } catch (_: PatternSyntaxException) {
            emptyList()
        }
    }

    private fun getResultAndQueryIndex(
        content: String,
        queryIndexInContent: Int,
        query: String
    ): Pair<Int, String> {
        // 左右移动20个字符，构建关键词周边文字，在搜索结果里显示
        // 判断段落，只在关键词所在段落内分割
        // 利用标点符号分割完整的句
        // length和设置结合，自由调整周边文字长度
        val length = 20
        var po1 = queryIndexInContent - length
        var po2 = queryIndexInContent + query.length + length
        if (po1 < 0) {
            po1 = 0
        }
        if (po2 > content.length) {
            po2 = content.length
        }
        val queryIndexInResult = queryIndexInContent - po1
        val newText = content.substring(po1, po2)
        return queryIndexInResult to newText
    }

}
