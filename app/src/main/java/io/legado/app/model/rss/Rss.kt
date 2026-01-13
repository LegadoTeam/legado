package io.legado.app.model.rss

import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.StrResponse
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
            source = rssSource,
            ruleData = ruleData,
            coroutineContext = currentCoroutineContext(),
            hasLoginHeader = false
        )
        var res = try {
            analyzeUrl.getStrResponseAwait()
        } catch (e: Exception) {
            rssSource.loginCheckJs?.let { checkJs ->
                if (checkJs.isNotBlank()) {
                    val errResponse = Response.Builder()
                        .request(Request.Builder().url("http://localhost").build())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(500)
                        .message("Error Response")
                        .body(e.stackTraceStr.toResponseBody(null))
                        .build()
                    analyzeUrl.evalJS(checkJs, errResponse) as StrResponse
                }
            }
            throw e
        }
        //检测源是否已登录
        rssSource.loginCheckJs?.let { checkJs ->
            if (checkJs.isNotBlank()) {
                res = analyzeUrl.evalJS(checkJs, res) as StrResponse
            }
        }
        checkRedirect(rssSource, res)
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
    ): String {
        val analyzeUrl = AnalyzeUrl(
            rssArticle.link,
            baseUrl = rssArticle.origin,
            source = rssSource,
            ruleData = rssArticle,
            coroutineContext = currentCoroutineContext(),
            hasLoginHeader = false
        )
        var res = try {
            analyzeUrl.getStrResponseAwait()
        } catch (e: Exception) {
            rssSource.loginCheckJs?.let { checkJs ->
                if (checkJs.isNotBlank()) {
                    val errResponse = Response.Builder()
                        .request(Request.Builder().url("http://localhost").build())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(500)
                        .message("Error Response")
                        .body(e.stackTraceStr.toResponseBody(null))
                        .build()
                    analyzeUrl.evalJS(checkJs, errResponse) as StrResponse
                }
            }
            throw e
        }
        //检测源是否已登录
        rssSource.loginCheckJs?.let { checkJs ->
            if (checkJs.isNotBlank()) {
                res = analyzeUrl.evalJS(checkJs, res) as StrResponse
            }
        }
        checkRedirect(rssSource, res)
        Debug.log(rssSource.sourceUrl, "≡获取成功:${rssSource.sourceUrl}")
        Debug.log(rssSource.sourceUrl, res.body ?: "", state = 20)
        val analyzeRule = AnalyzeRule(rssArticle, rssSource)
        analyzeRule.setContent(res.body)
            .setBaseUrl(NetworkUtils.getAbsoluteURL(rssArticle.origin, rssArticle.link))
            .setCoroutineContext(currentCoroutineContext())
            .setRedirectUrl(res.url)
        return analyzeRule.getString(ruleContent)
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