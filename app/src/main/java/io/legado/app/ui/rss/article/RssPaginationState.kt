package io.legado.app.ui.rss.article

internal enum class RssRetryTarget {
    Refresh,
    NextPage,
}

internal sealed interface RssRefreshAction {
    data object InProgress : RssRefreshAction
    data class Request(
        val page: Int,
        internal val requestId: Long,
    ) : RssRefreshAction
}

internal sealed interface RssNextPageAction {
    data object InProgress : RssNextPageAction
    data class NoMore(internal val resultId: Long) : RssNextPageAction
    data class Request(
        val page: Int,
        val url: String,
        internal val requestId: Long,
    ) : RssNextPageAction
}

internal class RssPaginationState {

    private var requestSequence = 0L
    private var activeRequestId: Long? = null

    @Volatile
    var isLoading = false
        private set

    @Volatile
    var page = 1
        private set

    @Volatile
    var nextPageUrl: String? = null
        private set

    @Volatile
    var retryTarget: RssRetryTarget? = null
        private set

    val hasNextPage: Boolean
        get() = !nextPageUrl.isNullOrBlank()

    @Synchronized
    fun startRefresh(): RssRefreshAction {
        if (isLoading) return RssRefreshAction.InProgress
        isLoading = true
        page = 1
        nextPageUrl = null
        return RssRefreshAction.Request(page, startRequest())
    }

    @Synchronized
    fun completeRefresh(
        request: RssRefreshAction.Request,
        nextPageUrl: String?,
    ): Boolean {
        if (!isActive(request)) return false
        this.nextPageUrl = nextPageUrl.normalizedPageUrl()
        finishRequest()
        return true
    }

    @Synchronized
    fun startNextPage(): RssNextPageAction {
        if (isLoading) return RssNextPageAction.InProgress
        val pageUrl = nextPageUrl.normalizedPageUrl()
            ?: return RssNextPageAction.NoMore(requestSequence)
        isLoading = true
        return RssNextPageAction.Request(page + 1, pageUrl, startRequest())
    }

    @Synchronized
    fun isLatestResult(resultId: Long): Boolean {
        return requestSequence == resultId
    }

    @Synchronized
    fun completeNextPage(
        request: RssNextPageAction.Request,
        nextPageUrl: String?,
    ): Boolean {
        if (!isActive(request)) return false
        page = request.page
        this.nextPageUrl = nextPageUrl.normalizedPageUrl()
        finishRequest()
        return true
    }

    @Synchronized
    fun failRefresh(request: RssRefreshAction.Request): Boolean {
        if (!isActive(request)) return false
        finishRequest(RssRetryTarget.Refresh)
        return true
    }

    @Synchronized
    fun failNextPage(request: RssNextPageAction.Request): Boolean {
        if (!isActive(request)) return false
        finishRequest(RssRetryTarget.NextPage)
        return true
    }

    @Synchronized
    fun isActive(request: RssRefreshAction.Request): Boolean {
        return activeRequestId == request.requestId
    }

    @Synchronized
    fun isActive(request: RssNextPageAction.Request): Boolean {
        return activeRequestId == request.requestId
    }

    private fun startRequest(): Long {
        requestSequence += 1
        activeRequestId = requestSequence
        return requestSequence
    }

    private fun finishRequest(retryTarget: RssRetryTarget? = null) {
        activeRequestId = null
        isLoading = false
        this.retryTarget = retryTarget
    }

    private fun String?.normalizedPageUrl(): String? = this?.takeIf { it.isNotBlank() }
}
