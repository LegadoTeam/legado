package io.legado.app.ui.rss.article

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RssPaginationStateTest {

    @Test
    fun `initial state is idle`() {
        val state = RssPaginationState()

        assertFalse(state.isLoading)
        assertEquals(1, state.page)
        assertFalse(state.hasNextPage)
        assertNull(state.retryTarget)
    }

    @Test
    fun `single page refresh failure retries refresh and success clears failure`() {
        val state = RssPaginationState()
        val failedRequest = startRefresh(state)

        assertTrue(state.failRefresh(failedRequest))

        assertFalse(state.isLoading)
        assertEquals(RssRetryTarget.Refresh, state.retryTarget)
        assertTrue(state.startNextPage() is RssNextPageAction.NoMore)

        val retryRequest = startRefresh(state)
        assertEquals(1, retryRequest.page)
        assertTrue(state.completeRefresh(retryRequest, null))
        assertNull(state.retryTarget)
        assertFalse(state.hasNextPage)
    }

    @Test
    fun `concurrent refresh is rejected without invalidating active refresh`() {
        val state = RssPaginationState()
        val request = startRefresh(state)

        assertEquals(RssRefreshAction.InProgress, state.startRefresh())
        assertTrue(state.isActive(request))
        assertTrue(state.completeRefresh(request, "page-2"))

        assertFalse(state.isLoading)
        assertEquals("page-2", state.nextPageUrl)
    }

    @Test
    fun `refresh is rejected while next page callback is active`() {
        val state = readyState("page-2")
        val nextPageRequest = state.startNextPage() as RssNextPageAction.Request

        assertEquals(RssRefreshAction.InProgress, state.startRefresh())
        assertTrue(state.isActive(nextPageRequest))
        assertTrue(state.completeNextPage(nextPageRequest, "page-3"))

        assertFalse(state.isLoading)
        assertEquals(2, state.page)
        assertEquals("page-3", state.nextPageUrl)
    }

    @Test
    fun `active refresh rejects pagination`() {
        val state = RssPaginationState()
        val request = startRefresh(state)

        assertEquals(RssNextPageAction.InProgress, state.startNextPage())
        assertTrue(state.completeRefresh(request, "page-2"))
    }

    @Test
    fun `completed result becomes stale when a new request starts`() {
        val state = RssPaginationState()
        val completedRequest = startRefresh(state)
        assertTrue(state.completeRefresh(completedRequest, "page-2"))
        assertTrue(state.isLatestResult(completedRequest.requestId))

        val newRequest = startRefresh(state)

        assertFalse(state.isLatestResult(completedRequest.requestId))
        assertTrue(state.isLatestResult(newRequest.requestId))
        assertTrue(state.failRefresh(newRequest))
    }

    @Test
    fun `no more result generation becomes stale after a new request`() {
        val state = RssPaginationState()
        val noMore = state.startNextPage() as RssNextPageAction.NoMore
        assertTrue(state.isLatestResult(noMore.resultId))

        val request = startRefresh(state)

        assertFalse(state.isLatestResult(noMore.resultId))
        assertTrue(state.isLatestResult(request.requestId))
        assertTrue(state.failRefresh(request))
    }

    @Test
    fun `empty next page ends loading without advancing page`() {
        val state = RssPaginationState()
        val request = startRefresh(state)

        assertTrue(state.completeRefresh(request, "  "))

        assertFalse(state.isLoading)
        assertFalse(state.hasNextPage)
        assertEquals(1, state.page)
        assertTrue(state.startNextPage() is RssNextPageAction.NoMore)
        assertFalse(state.isLoading)
    }

    @Test
    fun `failed next page retries the same page and url`() {
        val state = readyState("page-2")
        val failedRequest = state.startNextPage() as RssNextPageAction.Request

        assertTrue(state.failNextPage(failedRequest))

        assertFalse(state.isLoading)
        assertEquals(1, state.page)
        assertEquals(RssRetryTarget.NextPage, state.retryTarget)
        val retryRequest = state.startNextPage() as RssNextPageAction.Request
        assertEquals(failedRequest.page, retryRequest.page)
        assertEquals(failedRequest.url, retryRequest.url)
    }

    @Test
    fun `successful next page retry clears failure and keeps following page`() {
        val state = readyState("page-2")
        val failedRequest = state.startNextPage() as RssNextPageAction.Request
        assertTrue(state.failNextPage(failedRequest))
        val retryRequest = state.startNextPage() as RssNextPageAction.Request

        assertTrue(state.completeNextPage(retryRequest, "page-3"))

        assertEquals(2, state.page)
        assertEquals("page-3", state.nextPageUrl)
        assertTrue(state.hasNextPage)
        assertFalse(state.isLoading)
        assertNull(state.retryTarget)
    }

    @Test
    fun `successful final page commits page and reports no more data`() {
        val state = readyState("page-2")
        val request = state.startNextPage() as RssNextPageAction.Request

        assertTrue(state.completeNextPage(request, null))

        assertEquals(2, state.page)
        assertFalse(state.hasNextPage)
        assertFalse(state.isLoading)
        assertTrue(state.startNextPage() is RssNextPageAction.NoMore)
    }

    private fun readyState(nextPageUrl: String): RssPaginationState {
        return RssPaginationState().apply {
            completeRefresh(startRefresh(this), nextPageUrl)
        }
    }

    private fun startRefresh(state: RssPaginationState): RssRefreshAction.Request {
        return state.startRefresh() as RssRefreshAction.Request
    }
}
