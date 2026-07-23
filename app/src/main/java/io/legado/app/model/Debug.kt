package io.legado.app.model

import android.annotation.SuppressLint
import android.util.Log
import io.legado.app.BuildConfig
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.*
import io.legado.app.help.book.isWebFile
import io.legado.app.help.coroutine.CompositeCoroutine
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.sortUrls
import io.legado.app.model.rss.Rss
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.CoroutineScope
import java.text.SimpleDateFormat
import java.util.*

object Debug {
    @get:Synchronized
    var callback: Callback? = null
        private set
    private var debugSource: String? = null
    private val tasks: CompositeCoroutine = CompositeCoroutine()
    private var debugSessionId = 0L
    val debugMessageMap = HashMap<String, String>()
    private val debugTimeMap = HashMap<String, Long>()
    @get:Synchronized
    var isChecking: Boolean = false
        private set

    @SuppressLint("ConstantLocale")
    private val debugTimeFormat = SimpleDateFormat("[mm:ss.SSS]", Locale.getDefault())
    private var startTime: Long = System.currentTimeMillis()

    @Synchronized
    fun log(
        sourceUrl: String?,
        msg: String = "",
        print: Boolean = true,
        isHtml: Boolean = false,
        showTime: Boolean = true,
        state: Int = 1
    ) {
        if (BuildConfig.DEBUG) {
            Log.d("sourceDebug", msg)
        }
        //调试信息始终要执行
        callback?.let {
            if ((debugSource != sourceUrl || !print)) return
            var printMsg = msg
            if (isHtml) {
                printMsg = HtmlFormatter.format(msg)
            }
            if (showTime) {
                val time = debugTimeFormat.format(Date(System.currentTimeMillis() - startTime))
                printMsg = "$time $printMsg"
            }
            it.printLog(state, printMsg)
        }
        if (isChecking && sourceUrl != null && (msg).length < 30) {
            var printMsg = msg
            if (isHtml) {
                printMsg = HtmlFormatter.format(msg)
            }
            if (showTime && debugTimeMap[sourceUrl] != null) {
                val time =
                    debugTimeFormat.format(Date(System.currentTimeMillis() - debugTimeMap[sourceUrl]!!))
                printMsg = printMsg.replace(AppPattern.debugMessageSymbolRegex, "")

                debugMessageMap[sourceUrl] = "$time $printMsg"
            }
        }
    }

    @Synchronized
    internal fun isDebugging(sourceUrl: String): Boolean {
        return callback != null && debugSource == sourceUrl
    }

    @Synchronized
    fun tryAcquireCallback(owner: Callback): Boolean {
        if (isChecking) return false
        val current = callback
        if (current != null) return current === owner
        callback = owner
        return true
    }

    @Synchronized
    fun cancelDebug(owner: Callback): Boolean {
        if (callback !== owner) return false
        debugSessionId++
        tasks.clear()
        debugSource = null
        callback = null
        return true
    }

    @Synchronized
    fun log(msg: String?) {
        log(debugSource, msg ?: "", true)
    }

    @Synchronized
    private fun beginDebugSession(sourceUrl: String): Long {
        tasks.clear()
        debugSessionId++
        debugSource = sourceUrl
        startTime = System.currentTimeMillis()
        return debugSessionId
    }

    @Synchronized
    fun startSimpleDebug(owner: Callback, sourceUrl: String): Boolean {
        if (!tryAcquireCallback(owner)) return false
        beginDebugSession(sourceUrl)
        return true
    }

    private inline fun withActiveDebugSession(sessionId: Long, block: () -> Unit) {
        synchronized(this) {
            if (callback != null && debugSessionId == sessionId) {
                block()
            }
        }
    }

    private fun trackDebugTask(sessionId: Long, task: Coroutine<*>) {
        synchronized(this) {
            if (callback != null && debugSessionId == sessionId) {
                tasks.add(task)
            } else {
                task.cancel()
            }
        }
    }

    @Synchronized
    fun tryStartChecking(): Boolean {
        if (callback != null || isChecking) return false
        isChecking = true
        return true
    }

    @Synchronized
    fun startChecking(source: BookSource) {
        if (!isChecking || callback != null) return
        debugTimeMap[source.bookSourceUrl] = System.currentTimeMillis()
        debugMessageMap[source.bookSourceUrl] = "${debugTimeFormat.format(Date(0))} 开始校验"
    }

    @Synchronized
    fun finishChecking() {
        isChecking = false
    }

    fun getRespondTime(sourceUrl: String): Long {
        return debugTimeMap[sourceUrl] ?: CheckSource.timeout
    }

    fun updateFinalMessage(sourceUrl: String, state: String) {
        if (debugTimeMap[sourceUrl] != null && debugMessageMap[sourceUrl] != null) {
            val spendingTime = System.currentTimeMillis() - debugTimeMap[sourceUrl]!!
            debugTimeMap[sourceUrl] =
                if (state == "校验成功") spendingTime else CheckSource.timeout + spendingTime
            val printTime = debugTimeFormat.format(Date(spendingTime))
            debugMessageMap[sourceUrl] = "$printTime $state"
        }
    }

    suspend fun startDebug(scope: CoroutineScope, rssSource: RssSource) {
        val sessionId = beginDebugSession(rssSource.sourceUrl)
        withActiveDebugSession(sessionId) {
            log(debugSource, "︾开始解析")
        }
        val sort = rssSource.sortUrls().firstOrNull() ?: ("" to rssSource.sourceUrl)
        val articles = Rss.getArticles(scope, sort.first, sort.second, rssSource, 1)
            .onSuccess {
                withActiveDebugSession(sessionId) {
                    if (it.first.isEmpty()) {
                        log(debugSource, "⇒列表页解析成功，为空")
                        log(debugSource, "︽解析完成", state = 1000)
                    } else {
                        val ruleContent = rssSource.ruleContent
                        if (!rssSource.ruleArticles.isNullOrBlank() && rssSource.ruleDescription.isNullOrBlank()) {
                            log(debugSource, "︽列表页解析完成")
                            log(debugSource, showTime = false)
                            if (ruleContent.isNullOrEmpty()) {
                                log(debugSource, "⇒内容规则为空，默认获取整个网页", state = 1000)
                            } else {
                                rssContentDebug(scope, it.first[0], ruleContent, rssSource, sessionId)
                            }
                        } else {
                            log(debugSource, "⇒存在描述规则，不解析内容页")
                            log(debugSource, "︽解析完成", state = 1000)
                        }
                    }
                }
            }
            .onError {
                withActiveDebugSession(sessionId) {
                    log(debugSource, it.stackTraceStr, state = -1)
                }
            }
        trackDebugTask(sessionId, articles)
    }

    fun startDebug(scope: CoroutineScope, rssSource: RssSource, key: String) {
        val sessionId = beginDebugSession(rssSource.sourceUrl)
        withActiveDebugSession(sessionId) {
            when {
                key.contains("::") -> {
                    val name = key.substringBefore("::")
                    val url = key.substringAfter("::")
                    log(debugSource, "⇒开始访问分类页:$url")
                    log(debugSource, "︾开始解析分类页")
                    sortDebug(scope, rssSource, name, url, sessionId = sessionId)
                }

                key.isAbsUrl() -> {
                    val ruleContent = rssSource.ruleContent
                    if (!rssSource.ruleArticles.isNullOrBlank() && rssSource.ruleDescription.isNullOrBlank()) {
                        if (ruleContent.isNullOrEmpty()) {
                            log(debugSource, "⇒内容规则为空，默认获取整个网页", state = 1000)
                        } else {
                            val rssArticle = RssArticle()
                            rssArticle.origin = rssSource.sourceUrl
                            rssArticle.link = key
                            log(debugSource, "⇒开始访问内容页:$key")
                            rssContentDebug(scope, rssArticle, ruleContent, rssSource, sessionId)
                        }
                    } else {
                        log(debugSource, "⇒存在描述规则，不解析内容页")
                        log(debugSource, "︽解析完成", state = 1000)
                    }
                }

                else -> {
                    val searchUrl = rssSource.searchUrl
                    if (searchUrl.isNullOrEmpty()) {
                        log(debugSource, "⇒搜索URL为空", state = -1)
                        return@withActiveDebugSession
                    }
                    log(debugSource, "⇒开始搜索关键字:$key")
                    log(debugSource, "︾开始解析搜索页")
                    sortDebug(scope, rssSource, "搜索", searchUrl, key, sessionId)
                }
            }
        }
    }

    private fun sortDebug(
        scope: CoroutineScope,
        rssSource: RssSource,
        name: String,
        url: String,
        key: String? = null,
        sessionId: Long,
    ) {
        val articles = Rss.getArticles(scope, name, url, rssSource, 1, key)
            .onSuccess {
                withActiveDebugSession(sessionId) {
                    if (it.first.isEmpty()) {
                        log(debugSource, "⇒列表页解析成功，为空")
                        log(debugSource, "︽解析完成", state = 1000)
                    } else {
                        val ruleContent = rssSource.ruleContent
                        if (!rssSource.ruleArticles.isNullOrBlank() && rssSource.ruleDescription.isNullOrBlank()) {
                            log(debugSource, "︽列表页解析完成")
                            log(debugSource, showTime = false)
                            if (ruleContent.isNullOrEmpty()) {
                                log(debugSource, "⇒内容规则为空，默认获取整个网页", state = 1000)
                            } else {
                                rssContentDebug(scope, it.first[0], ruleContent, rssSource, sessionId)
                            }
                        } else {
                            log(debugSource, "⇒存在描述规则，不解析内容页")
                            log(debugSource, "︽解析完成", state = 1000)
                        }
                    }
                }
            }
            .onError {
                withActiveDebugSession(sessionId) {
                    log(debugSource, it.stackTraceStr, state = -1)
                }
            }
        trackDebugTask(sessionId, articles)
    }

    private fun rssContentDebug(
        scope: CoroutineScope,
        rssArticle: RssArticle,
        ruleContent: String,
        rssSource: RssSource,
        sessionId: Long,
    ) {
        log(debugSource, "︾开始解析内容页")
        val content = Rss.getContent(scope, rssArticle, ruleContent, rssSource)
            .onSuccess {
                withActiveDebugSession(sessionId) {
                    log(debugSource, it)
                    log(debugSource, "︽内容页解析完成", state = 1000)
                }
            }
            .onError {
                withActiveDebugSession(sessionId) {
                    log(debugSource, it.stackTraceStr, state = -1)
                }
            }
        trackDebugTask(sessionId, content)
    }

    fun startDebug(scope: CoroutineScope, bookSource: BookSource, key: String) {
        val sessionId = beginDebugSession(bookSource.bookSourceUrl)
        withActiveDebugSession(sessionId) {
            when {
                key.isAbsUrl() -> {
                    val book = Book()
                    book.origin = bookSource.bookSourceUrl
                    book.bookUrl = key
                    log(debugSource, "⇒开始访问详情页:$key")
                    infoDebug(scope, bookSource, book, sessionId)
                }

                key.contains("::") -> {
                    val url = key.substringAfter("::")
                    log(debugSource, "⇒开始访问发现页:$url")
                    exploreDebug(scope, bookSource, url, sessionId)
                }

                key.startsWith("++") -> {
                    val url = key.substring(2)
                    val book = Book()
                    book.origin = bookSource.bookSourceUrl
                    book.tocUrl = url
                    log(debugSource, "⇒开始访目录页:$url")
                    tocDebug(scope, bookSource, book, sessionId)
                }

                key.startsWith("--") -> {
                    val url = key.substring(2)
                    val book = Book()
                    book.origin = bookSource.bookSourceUrl
                    log(debugSource, "⇒开始访正文页:$url")
                    val chapter = BookChapter()
                    chapter.title = "调试"
                    chapter.url = url
                    contentDebug(scope, bookSource, book, chapter, null, sessionId)
                }

                else -> {
                    log(debugSource, "⇒开始搜索关键字:$key")
                    searchDebug(scope, bookSource, key, sessionId)
                }
            }
        }
    }

    private fun exploreDebug(scope: CoroutineScope, bookSource: BookSource, url: String, sessionId: Long) {
        log(debugSource, "︾开始解析发现页")
        val explore = WebBook.exploreBook(scope, bookSource, url, 1)
            .onSuccess { exploreBooks ->
                withActiveDebugSession(sessionId) {
                    if (exploreBooks.isNotEmpty()) {
                        log(debugSource, "︽发现页解析完成")
                        log(debugSource, showTime = false)
                        infoDebug(scope, bookSource, exploreBooks[0].toBook(), sessionId)
                    } else {
                        log(debugSource, "︽未获取到书籍", state = -1)
                    }
                }
            }
            .onError {
                withActiveDebugSession(sessionId) {
                    log(debugSource, it.stackTraceStr, state = -1)
                }
            }
        trackDebugTask(sessionId, explore)
    }

    private fun searchDebug(scope: CoroutineScope, bookSource: BookSource, key: String, sessionId: Long) {
        log(debugSource, "︾开始解析搜索页")
        val search = WebBook.searchBook(scope, bookSource, key, 1)
            .onSuccess { searchBooks ->
                withActiveDebugSession(sessionId) {
                    if (searchBooks.isNotEmpty()) {
                        log(debugSource, "︽搜索页解析完成")
                        log(debugSource, showTime = false)
                        infoDebug(scope, bookSource, searchBooks[0].toBook(), sessionId)
                    } else {
                        log(debugSource, "︽未获取到书籍", state = -1)
                    }
                }
            }
            .onError {
                withActiveDebugSession(sessionId) {
                    log(debugSource, it.stackTraceStr, state = -1)
                }
            }
        trackDebugTask(sessionId, search)
    }

    private fun infoDebug(scope: CoroutineScope, bookSource: BookSource, book: Book, sessionId: Long) {
        if (book.tocUrl.isNotBlank()) {
            log(debugSource, "≡已获取目录链接,跳过详情页")
            log(debugSource, showTime = false)
            tocDebug(scope, bookSource, book, sessionId)
            return
        }
        log(debugSource, "︾开始解析详情页")
        val info = WebBook.getBookInfo(scope, bookSource, book)
            .onSuccess {
                withActiveDebugSession(sessionId) {
                    log(debugSource, "︽详情页解析完成")
                    log(debugSource, showTime = false)
                    if (!book.isWebFile) {
                        tocDebug(scope, bookSource, book, sessionId)
                    } else {
                        log(debugSource, "≡文件类书源跳过解析目录", state = 1000)
                    }
                }
            }
            .onError {
                withActiveDebugSession(sessionId) {
                    log(debugSource, it.stackTraceStr, state = -1)
                }
            }
        trackDebugTask(sessionId, info)
    }

    private fun tocDebug(scope: CoroutineScope, bookSource: BookSource, book: Book, sessionId: Long) {
        log(debugSource, "︾开始解析目录页")
        val chapterList = WebBook.getChapterList(scope, bookSource, book)
            .onSuccess { chapters ->
                withActiveDebugSession(sessionId) {
                    log(debugSource, "︽目录页解析完成")
                    log(debugSource, showTime = false)
                    val toc = chapters.filter { !(it.isVolume && it.url.startsWith(it.title)) }
                    if (toc.isEmpty()) {
                        log(debugSource, "≡没有正文章节", state = -1)
                    } else {
                        val nextChapterUrl = toc.getOrNull(1)?.url ?: toc.first().url
                        contentDebug(scope, bookSource, book, toc.first(), nextChapterUrl, sessionId)
                    }
                }
            }
            .onError {
                withActiveDebugSession(sessionId) {
                    log(debugSource, it.stackTraceStr, state = -1)
                }
            }
        trackDebugTask(sessionId, chapterList)
    }

    private fun contentDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String?,
        sessionId: Long,
    ) {
        log(debugSource, "︾开始解析正文页")
        val content = WebBook.getContent(
            scope = scope,
            bookSource = bookSource,
            book = book,
            bookChapter = bookChapter,
            nextChapterUrl = nextChapterUrl,
            needSave = false
        ).onSuccess {
            withActiveDebugSession(sessionId) {
                log(debugSource, "︽正文页解析完成", state = 1000)
            }
        }.onError {
            withActiveDebugSession(sessionId) {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        }
        trackDebugTask(sessionId, content)
    }

    interface Callback {
        fun printLog(state: Int, msg: String)
    }
}
