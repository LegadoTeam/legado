package io.legado.app.ui.book.searchContent


import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ChineseUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 全文搜索范围。
 *
 * - [ALL] 全书, 与旧行为一致(默认)
 * - [DOWN] 从当前阅读位置向下: 当前章从当前位置起, 之后的所有章节
 * - [UP] 从当前阅读位置向上: 当前章到当前位置止(结果由近及远), 之前的章节倒序
 *
 * 锚点是"章节 + 章内字符偏移"两段式, 而不是只看章节:
 * 无目录的单章大文本(几百上千页)只按章切分会退化成"向上无结果 / 向下即全书"。
 */
enum class ContentSearchScope {
    ALL, DOWN, UP
}

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
    private var contentProcessor: ContentProcessor? = null
    var lastQuery: String = ""
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

    suspend fun searchChapter(
        query: String,
        chapter: BookChapter
    ): List<SearchResult> {
        val searchResultsWithinChapter: MutableList<SearchResult> = mutableListOf()
        val book = book ?: return searchResultsWithinChapter
        val chapterContent = BookHelp.getContent(book, chapter) ?: return searchResultsWithinChapter
        currentCoroutineContext().ensureActive()
        chapter.title = when (AppConfig.chineseConverterType) {
            1 -> ChineseUtils.t2s(chapter.title)
            2 -> ChineseUtils.s2t(chapter.title)
            else -> chapter.title
        }
        currentCoroutineContext().ensureActive()
        val mContent = contentProcessor!!.getContent(
            book, chapter, chapterContent, useReplace = replaceEnabled
        ).toString()
        val positions = searchPosition(mContent, query)
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
                isRegex = regexReplace
            )
            searchResultsWithinChapter.add(result)
        }
        searchResultCounts += searchResultsWithinChapter.size
        return searchResultsWithinChapter
    }

    private suspend fun searchPosition(content: String, pattern: String): List<Int> {
        val position: MutableList<Int> = mutableListOf()
        if (regexReplace) { // 正则表达式搜索
            try {
                Regex(pattern).findAll(content).forEach { match ->
                    currentCoroutineContext().ensureActive()
                    position.add(match.range.first)
                }
            } catch (e: Exception) {
                return position
            }
        } else {
            var index = content.indexOf(pattern)
            while (index >= 0) {
                currentCoroutineContext().ensureActive()
                position.add(index)
                index = content.indexOf(pattern, index + pattern.length)
            }
        }
        return position
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
