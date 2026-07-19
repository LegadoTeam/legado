package io.legado.app.ui.rss.article

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.model.rss.Rss
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.Dispatchers.IO


internal sealed interface RssArticlesLoadResult {
    val requestId: Long

    data class Success(
        override val requestId: Long,
        val hasMore: Boolean,
    ) : RssArticlesLoadResult
    data class Error(
        override val requestId: Long,
        val message: String,
        val retryTarget: RssRetryTarget,
    ) : RssArticlesLoadResult
}

class RssArticlesViewModel(application: Application) : BaseViewModel(application) {
    internal val loadResultLiveData = MutableLiveData<RssArticlesLoadResult>()
    private val paginationState = RssPaginationState()
    val isLoading: Boolean
        get() = paginationState.isLoading
    var order = System.currentTimeMillis()
    var sortName: String = ""
    var sortUrl: String = ""
    var searchKey: String? = null
    val page: Int
        get() = paginationState.page

    internal fun isLatestResult(result: RssArticlesLoadResult): Boolean {
        return paginationState.isLatestResult(result.requestId)
    }

    fun init(bundle: Bundle?) {
        bundle?.let {
            sortName = it.getString("sortName") ?: ""
            sortUrl = it.getString("sortUrl") ?: ""
            searchKey = it.getString("searchKey")
        }
    }

    fun loadArticles(rssSource: RssSource): Boolean {
        val request = when (val action = paginationState.startRefresh()) {
            RssRefreshAction.InProgress -> return false
            is RssRefreshAction.Request -> action
        }
        order = System.currentTimeMillis()
        Rss.getArticles(
            viewModelScope,
            sortName,
            sortUrl,
            rssSource,
            request.page,
            searchKey
        ).onSuccess(IO) {
            if (!paginationState.isActive(request)) return@onSuccess
            val articles = it.first
            articles.forEach { rssArticle ->
                rssArticle.order = order--
            }
            appDb.rssArticleDao.insert(*articles.toTypedArray())
            if (!rssSource.ruleNextPage.isNullOrEmpty()) {
                appDb.rssArticleDao.clearOld(rssSource.sourceUrl, sortName, order)
            }
            val hasMore = articles.isNotEmpty() && !it.second.isNullOrBlank()
            if (!paginationState.completeRefresh(request, it.second)) return@onSuccess
            loadResultLiveData.postValue(
                RssArticlesLoadResult.Success(request.requestId, hasMore)
            )
        }.onError {
            if (!paginationState.failRefresh(request)) return@onError
            loadResultLiveData.postValue(
                RssArticlesLoadResult.Error(
                    request.requestId,
                    it.stackTraceStr,
                    RssRetryTarget.Refresh
                )
            )
            AppLog.put("rss获取内容失败", it)
        }
        return true
    }

    fun loadMore(rssSource: RssSource): Boolean {
        val request = when (val action = paginationState.startNextPage()) {
            RssNextPageAction.InProgress -> return false
            is RssNextPageAction.NoMore -> {
                loadResultLiveData.postValue(
                    RssArticlesLoadResult.Success(action.resultId, false)
                )
                return true
            }
            is RssNextPageAction.Request -> action
        }
        Rss.getArticles(
            viewModelScope,
            sortName,
            request.url,
            rssSource,
            request.page,
            searchKey
        ).onSuccess(IO) {
            if (!paginationState.isActive(request)) return@onSuccess
            val addedArticles = loadMoreSuccess(it.first)
            val hasMore = addedArticles && !it.second.isNullOrBlank()
            if (!paginationState.completeNextPage(request, it.second)) return@onSuccess
            loadResultLiveData.postValue(
                RssArticlesLoadResult.Success(request.requestId, hasMore)
            )
        }.onError {
            if (!paginationState.failNextPage(request)) return@onError
            loadResultLiveData.postValue(
                RssArticlesLoadResult.Error(
                    request.requestId,
                    it.stackTraceStr,
                    RssRetryTarget.NextPage
                )
            )
            AppLog.put("rss获取内容失败", it)
        }
        return true
    }

    private fun loadMoreSuccess(articles: MutableList<RssArticle>): Boolean {
        if (articles.isEmpty()) {
            return false
        }
        val firstArticle = articles.first()
        val dbFirstArticle =
            appDb.rssArticleDao.get(firstArticle.origin, firstArticle.link, firstArticle.sort)
        val lastArticle = articles.last()
        val dbLastArticle =
            appDb.rssArticleDao.get(lastArticle.origin, lastArticle.link, firstArticle.sort)
        if (dbFirstArticle != null && dbLastArticle != null) {
            return false
        }
        articles.forEach {
            it.order = order--
        }
        appDb.rssArticleDao.append(*articles.toTypedArray())
        return true
    }

}
