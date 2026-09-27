package io.legado.app.model.rss

import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.StrResponse
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.mapAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.CoroutineContext

@Suppress("MemberVisibilityCanBePrivate")
object Rss {

    fun getArticles(
        scope: CoroutineScope,
        sortName: String,
        sortUrl: String,
        rssSource: RssSource,
        page: Int,
        key: String? = null,
        context: CoroutineContext = Dispatchers.IO
    ): Coroutine<Pair<MutableList<RssArticle>, String?>> {
        return Coroutine.async(scope, context) {
            getArticlesAwait(sortName, sortUrl, rssSource, page, key)
        }
    }

    suspend fun getArticlesAwait(
        sortName: String,
        sortUrl: String,
        rssSource: RssSource,
        page: Int,
        key: String? = null
    ): Pair<MutableList<RssArticle>, String?> {
        val ruleData = RuleData()
        val analyzeUrl = AnalyzeUrl(
            sortUrl,
            page = page,
            key = key,
            baseUrl = rssSource.sourceUrl,
            source = rssSource,
            ruleData = ruleData,
            coroutineContext = currentCoroutineContext(),
            hasLoginHeader = false
        )
        val checkJs = rssSource.loginCheckJs
        val res = kotlin.runCatching {
            analyzeUrl.getStrResponseAwait().let {
                if (!checkJs.isNullOrBlank()) { //检测源是否已登录
                    analyzeUrl.evalJS(checkJs, it) as StrResponse
                } else {
                    it
                }
            }
        }.getOrElse { throwable ->
            if (!checkJs.isNullOrBlank()) {
                val errResponse = analyzeUrl.getErrStrResponse(throwable)
                try {
                    (analyzeUrl.evalJS(checkJs, errResponse) as StrResponse).also {
                        if (it.code() == 500) {
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
        checkRedirect(rssSource, res)
        Debug.log(rssSource.sourceUrl, "≡获取成功:${analyzeUrl.ruleUrl}")
        return RssParserByRule.parseXML(sortName, sortUrl, res.url, res.body, rssSource, ruleData)
    }

    fun getContent(
        scope: CoroutineScope,
        rssArticle: RssArticle,
        ruleContent: String,
        rssSource: RssSource,
        context: CoroutineContext = Dispatchers.IO
    ): Coroutine<String> {
        return Coroutine.async(scope, context) {
            getContentAwait(rssArticle, ruleContent, rssSource)
        }
    }

    suspend fun getContentAwait(
        rssArticle: RssArticle,
        ruleContent: String,
        rssSource: RssSource,
    ): String = getContentAwait(
        rssArticle,
        ruleContent,
        rssSource,
        AppConfig.threadCount,
    ) { url, printLog ->
        getContentResponse(url, rssArticle, rssSource, printLog)
    }

    internal suspend fun getContentAwait(
        rssArticle: RssArticle,
        ruleContent: String,
        rssSource: RssSource,
        concurrency: Int = 1,
        getResponse: suspend (url: String, printLog: Boolean) -> StrResponse,
    ): String {
        val contentList = arrayListOf<String>()
        val firstResponse = getResponse(rssArticle.link, true)
        var contentData = analyzeContentPage(
            rssArticle,
            rssSource,
            ruleContent,
            rssArticle.link,
            firstResponse,
        )
        contentList.add(contentData.first)
        if (contentData.second.size == 1) {
            val visitedUrls = hashSetOf(firstResponse.url)
            var nextUrl = contentData.second.first()
            while (nextUrl.isNotEmpty() && visitedUrls.add(nextUrl)) {
                currentCoroutineContext().ensureActive()
                val response = getResponse(nextUrl, false)
                contentData = analyzeContentPage(
                    rssArticle,
                    rssSource,
                    ruleContent,
                    nextUrl,
                    response,
                    printLog = false,
                )
                contentList.add(contentData.first)
                nextUrl = contentData.second.firstOrNull().orEmpty()
                Debug.log(rssSource.sourceUrl, "第${contentList.size}页完成")
            }
            Debug.log(rssSource.sourceUrl, "◇正文总页数:${contentList.size}")
        } else if (contentData.second.size > 1) {
            Debug.log(rssSource.sourceUrl, "◇并发解析正文,总页数:${contentData.second.size}")
            flow {
                contentData.second.forEach { emit(it) }
            }.mapAsync(concurrency) { nextUrl ->
                val response = getResponse(nextUrl, false)
                analyzeContentPage(
                    rssArticle,
                    rssSource,
                    ruleContent,
                    nextUrl,
                    response,
                    getNextPageUrl = false,
                    printLog = false,
                ).first
            }.collect {
                currentCoroutineContext().ensureActive()
                contentList.add(it)
            }
        }
        return contentList.joinToString("\n")
    }

    private suspend fun getContentResponse(
        url: String,
        rssArticle: RssArticle,
        rssSource: RssSource,
        printLog: Boolean = true,
    ): StrResponse {
        val analyzeUrl = AnalyzeUrl(
            url,
            baseUrl = rssArticle.origin,
            source = rssSource,
            ruleData = rssArticle,
            coroutineContext = currentCoroutineContext(),
            hasLoginHeader = false
        )
        val checkJs = rssSource.loginCheckJs
        val res = kotlin.runCatching {
            analyzeUrl.getStrResponseAwait().let {
                if (!checkJs.isNullOrBlank()) { //检测源是否已登录
                    analyzeUrl.evalJS(checkJs, it) as StrResponse
                } else {
                    it
                }
            }
        }.getOrElse { throwable ->
            if (!checkJs.isNullOrBlank()) {
                val errResponse = analyzeUrl.getErrStrResponse(throwable)
                try {
                    (analyzeUrl.evalJS(checkJs, errResponse) as StrResponse).also {
                        if (it.code() == 500) {
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
        checkRedirect(rssSource, res)
        Debug.log(rssSource.sourceUrl, "≡获取成功:${res.url}", printLog)
        if (printLog) Debug.log(rssSource.sourceUrl, res.body ?: "", state = 20)
        return res
    }

    internal suspend fun analyzeContentPage(
        rssArticle: RssArticle,
        rssSource: RssSource,
        ruleContent: String,
        baseUrl: String,
        response: StrResponse,
        getNextPageUrl: Boolean = true,
        printLog: Boolean = true,
    ): Pair<String, List<String>> {
        val analyzeRule = AnalyzeRule(rssArticle, rssSource)
        analyzeRule.setContent(response.body)
            .setBaseUrl(NetworkUtils.getAbsoluteURL(rssArticle.origin, baseUrl))
            .setCoroutineContext(currentCoroutineContext())
            .setRedirectUrl(response.url)
        val content = analyzeRule.getString(ruleContent)
        val nextUrlList = arrayListOf<String>()
        if (getNextPageUrl && rssSource.type == 0 && rssArticle.type == 0 &&
            !rssSource.nextContentUrl.isNullOrEmpty()
        ) {
            Debug.log(rssSource.sourceUrl, "┌获取正文下一页链接", printLog)
            analyzeRule.getStringList(rssSource.nextContentUrl, isUrl = true)?.let {
                nextUrlList.addAll(it)
            }
            Debug.log(rssSource.sourceUrl, "└" + nextUrlList.joinToString("，"), printLog)
        }
        return content to nextUrlList
    }

    /**
     * 检测重定向
     */
    private fun checkRedirect(rssSource: RssSource, response: StrResponse) {
        response.raw.priorResponse?.let {
            if (it.isRedirect) {
                Debug.log(rssSource.sourceUrl, "≡检测到重定向(${it.code})")
                Debug.log(rssSource.sourceUrl, "┌重定向后地址")
                Debug.log(rssSource.sourceUrl, "└${response.url}")
            }
        }
    }
}
