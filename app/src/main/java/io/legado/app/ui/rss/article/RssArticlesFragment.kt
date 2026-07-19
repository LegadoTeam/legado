package io.legado.app.ui.rss.article

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.databinding.FragmentRssArticlesBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class RssArticlesFragment() : VMBaseFragment<RssArticlesViewModel>(R.layout.fragment_rss_articles),
    BaseRssArticlesAdapter.CallBack {

    constructor(sortName: String, sortUrl: String, searchKey: String?) : this() {
        arguments = Bundle().apply {
            putString("sortName", sortName)
            putString("sortUrl", sortUrl)
            putString("searchKey", searchKey)
        }
    }
    private var isResumed = false

    private val binding by viewBinding(FragmentRssArticlesBinding::bind)
    private val activityViewModel by activityViewModels<RssSortViewModel>()
    override val viewModel by viewModels<RssArticlesViewModel>()
    private val isPreload by lazy { activityViewModel.rssSource?.preload ?: false }
    private val orientation by lazy { resources.configuration.orientation }
    private val adapter: BaseRssArticlesAdapter<*> by lazy {
        when (activityViewModel.articleStyle) {
            1 -> RssArticlesAdapter1(requireContext(), this@RssArticlesFragment)
            2 -> RssArticlesAdapter2(requireContext(), this@RssArticlesFragment)
            4 -> RssArticlesAdapter4(requireContext(), this@RssArticlesFragment)
            3 -> RssArticlesAdapter3(requireContext(), this@RssArticlesFragment)
            else -> RssArticlesAdapter(requireContext(), this@RssArticlesFragment)
        }
    }
    private val loadMoreView: LoadMoreView by lazy {
        LoadMoreView(requireContext())
    }
    private var articlesFlowJob: Job? = null
    override val isGridLayout: Boolean
        get() = activityViewModel.articleStyle == 2
    private var fullRefresh = true

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.init(arguments)
        initView()
        initData()
    }

    private fun initView() = binding.run {
        refreshLayout.setColorSchemeColors(accentColor)
        recyclerView.setEdgeEffectColor(primaryColor)
        recyclerView.applyNavigationBarPadding()
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading) {
                val result = viewModel.loadResultLiveData.value
                if (result == null || viewModel.isLatestResult(result)) {
                    when (result) {
                        is RssArticlesLoadResult.Error -> when (result.retryTarget) {
                            RssRetryTarget.Refresh -> retryRefresh(result)
                            RssRetryTarget.NextPage -> scrollToBottom(true)
                        }
                        else -> scrollToBottom(true)
                    }
                }
            }
        }
        val layoutManager = when (activityViewModel.articleStyle) {
            3 -> {
                recyclerView.setPadding(20, 0, 20, 0)
                recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(
                        outRect: Rect,
                        view: View,
                        parent: RecyclerView,
                        state: RecyclerView.State
                    ) {
                        outRect.set(20,30,20,30)
                    }
                })
                recyclerView.itemAnimator = null
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) { //横屏三列
                    StaggeredGridLayoutManager(3, StaggeredGridLayoutManager.VERTICAL)
                } else {
                    StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
                }
            }
            2 -> {
                recyclerView.setPadding(8, 0, 8, 0)
                GridLayoutManager(requireContext(), 2)
            }
            4 -> {
                recyclerView.setPadding(4, 0, 4, 0)
                GridLayoutManager(requireContext(), 3)
            }
            else -> {
                recyclerView.addItemDecoration(VerticalDivider(requireContext()))
                LinearLayoutManager(requireContext())
            }
        }
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter
        adapter.addFooterView {
            ViewLoadMoreBinding.bind(loadMoreView)
        }
        refreshLayout.setOnRefreshListener {
            if (!loadArticles()) {
                refreshLayout.isRefreshing = false
            }
        }
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!recyclerView.canScrollVertically(1)) {
                    scrollToBottom()
                    return
                }
                if (layoutManager is StaggeredGridLayoutManager) {
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val firstVisibleItemPositions = layoutManager.findFirstVisibleItemPositions(null)
                    val firstVisibleItemPosition = firstVisibleItemPositions?.minOrNull() ?: 0
                    if (isPreload  && (visibleItemCount + firstVisibleItemPosition) >= (totalItemCount - 5)) {
                        scrollToBottom()
                    }
                }
            }
        })
        if (isPreload) {
            refreshLayout.post {
                refreshLayout.isRefreshing = true
                if (!loadArticles()) {
                    refreshLayout.isRefreshing = false
                }
            }
            return@run
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                refreshLayout.isRefreshing = true
                if (!loadArticles()) {
                    refreshLayout.isRefreshing = false
                }
                this@launch.cancel()
            }
        } //只刷新可见页面,非预加载时使用
    }

    private fun initData() {
        val rssUrl = activityViewModel.url ?: return
        articlesFlowJob?.cancel()
        articlesFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.rssArticleDao.flowByOriginSort(rssUrl, viewModel.sortName)
                .catch {
                    AppLog.put("订阅文章界面获取数据失败\n${it.localizedMessage}", it)
                }.flowOn(IO).collect { newList ->
                    if (!isResumed || fullRefresh || newList.isEmpty()) {
                        adapter.setItems(newList)
                    } else {
                        //用DiffUtil只对差异数据进行更新
                        //注意RecyclerView的复用机制,切换标签时采用差异化更新会报ViewHolder的状态管理混乱
                        adapter.setItems(newList, object : DiffUtil.ItemCallback<RssArticle>() {
                            override fun areItemsTheSame(
                                oldItem: RssArticle, newItem: RssArticle
                            ): Boolean {
                                return oldItem.link == newItem.link
                            }

                            override fun areContentsTheSame(
                                oldItem: RssArticle, newItem: RssArticle
                            ): Boolean {
                                return oldItem.title == newItem.title && oldItem.image == newItem.image && oldItem.read == newItem.read
                            }

                            override fun getChangePayload(
                                oldItem: RssArticle, newItem: RssArticle
                            ): Any? {
                                return if (oldItem.read != newItem.read) { "read" }
                                else if (oldItem.title != newItem.title) { "title" }
                                else { null }
                            }
                        }, true)
                    }
                    delay(200) // 200毫秒防抖
                }
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        adapter.upResumed(isResumed)
    }

    override fun onPause() {
        isResumed = false
        adapter.upResumed(isResumed)
        super.onPause()
    }

    private fun loadArticles(): Boolean {
        val rssSource = activityViewModel.rssSource ?: return false
        if (!viewModel.loadArticles(rssSource)) return false
        fullRefresh = true
        return true
    }

    private fun retryRefresh(error: RssArticlesLoadResult.Error) {
        loadMoreView.hasMore()
        if (!loadArticles() && viewModel.isLatestResult(error)) {
            loadMoreView.error(error.message)
        }
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        if (viewModel.isLoading) return
        fullRefresh = false
        if ((loadMoreView.hasMore && adapter.getActualItemCount() > 0) || forceLoad) {
            loadMoreView.hasMore()
            val rssSource = activityViewModel.rssSource
            if (rssSource == null || !viewModel.loadMore(rssSource)) {
                loadMoreView.stopLoad()
            }
        }
    }

    override fun observeLiveBus() {
        viewModel.loadResultLiveData.observe(viewLifecycleOwner) { result ->
            if (viewModel.isLatestResult(result)) {
                binding.refreshLayout.isRefreshing = false
                when (result) {
                    is RssArticlesLoadResult.Success -> {
                        if (result.hasMore) {
                            loadMoreView.hasMore()
                            loadMoreView.stopLoad()
                        } else {
                            loadMoreView.noMore()
                        }
                    }
                    is RssArticlesLoadResult.Error -> loadMoreView.error(result.message)
                }
            }
        }
    }

    override fun readRss(rssArticle: RssArticle) {
        fullRefresh = false //read会触发数据库更新,此时进行差异化更新
        ReadRss.readRss(this, rssArticle, activityViewModel.rssSource)
    }
}
