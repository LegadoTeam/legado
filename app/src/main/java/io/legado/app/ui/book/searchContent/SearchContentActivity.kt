package io.legado.app.ui.book.searchContent

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.view.allViews
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivitySearchContentBinding
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.applyTint
import io.legado.app.utils.invisible
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.showSoftInput
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class SearchContentActivity :
    VMBaseActivity<ActivitySearchContentBinding, SearchContentViewModel>(),
    SearchContentAdapter.Callback {

    override val binding by viewBinding(ActivitySearchContentBinding::inflate)
    override val viewModel by viewModels<SearchContentViewModel>()
    private val adapter by lazy { SearchContentAdapter(this, this) }
    private val mLayoutManager by lazy { UpLinearLayoutManager(this) }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var durChapterIndex = 0
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var initJob: Job? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val bbg = bottomBackground
        val btc = getPrimaryTextColor(ColorUtils.isColorLight(bbg))
        binding.llSearchBaseInfo.setBackgroundColor(bbg)
        binding.llSearchBaseInfo.applyNavigationBarMargin()
        binding.tvCurrentSearchInfo.setTextColor(btc)
        binding.ivSearchContentTop.setColorFilter(btc)
        binding.ivSearchContentBottom.setColorFilter(btc)
        val searchResultList = if (viewModel.hasSearchState) viewModel.searchResultList.toList()
            else IntentData.get<List<SearchResult>>("searchResultList")
        val position = intent.getIntExtra("searchResultIndex", 0)
        val noSearchResult = searchResultList == null
        initSearchView(noSearchResult)
        initRecyclerView()
        initView()
        val bookUrl = intent.getStringExtra("bookUrl") ?: return
        IntentData.get<ContentSearchAnchor>(intent.getStringExtra("searchAnchorKey"))?.let {
            viewModel.searchAnchor = it
        }
        viewModel.initBook(bookUrl) {
            initSearchResultList(searchResultList, position)
            initBook(noSearchResult)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.content_search, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_enable_replace)?.isChecked = SearchContentViewModel.replaceEnabled
        menu.findItem(R.id.menu_enable_regex)?.isChecked = SearchContentViewModel.regexReplace
        // 搜索范围是单选组, 只勾当前项
        menu.findItem(R.id.menu_content_scope_all)?.isChecked = SearchContentViewModel.searchScope == ContentSearchScope.ALL
        menu.findItem(R.id.menu_content_scope_down)?.isChecked = SearchContentViewModel.searchScope == ContentSearchScope.DOWN
        menu.findItem(R.id.menu_content_scope_up)?.isChecked = SearchContentViewModel.searchScope == ContentSearchScope.UP
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_enable_replace -> {
                SearchContentViewModel.replaceEnabled = !SearchContentViewModel.replaceEnabled
                item.isChecked = SearchContentViewModel.replaceEnabled
            }
            R.id.menu_enable_regex -> {
                SearchContentViewModel.regexReplace = !SearchContentViewModel.regexReplace
                item.isChecked = SearchContentViewModel.regexReplace
            }
            R.id.menu_content_scope_all, R.id.menu_content_scope_down, R.id.menu_content_scope_up -> {
                SearchContentViewModel.searchScope = when (item.itemId) {
                    R.id.menu_content_scope_down -> ContentSearchScope.DOWN
                    R.id.menu_content_scope_up -> ContentSearchScope.UP
                    else -> ContentSearchScope.ALL
                }
                item.isChecked = true
                // 切换范围后按当前关键词重搜, 不必再敲一次回车
                val query = viewModel.lastQuery
                if (query.isNotBlank()) startContentSearch(query)
            }

        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initSearchResultList(list: List<SearchResult>?, position: Int) {
        list ?: return
        viewModel.searchResultList.clear()
        viewModel.searchResultList.addAll(list)
        viewModel.searchResultCounts = list.size
        if (!viewModel.hasSearchState) {
            viewModel.lastQuery = list.firstOrNull()?.query?.takeIf { it.isNotBlank() }
                ?: intent.getStringExtra("searchWord").orEmpty()
            viewModel.hasSearchState = true
        }
        adapter.setItems(list)
        viewModel.searchResultMessage?.let {
            adapter.addItem(SearchResult(resultText = getString(it)))
        }
        binding.recyclerView.scrollToPosition(position)
    }

    private fun initSearchView(requestFocus: Boolean) {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.search)
        if (requestFocus) searchView.isIconified = false
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                startContentSearch(query.trim())
                searchView.clearFocus()
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                return false
            }
        })
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = mLayoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
    }

    private fun initView() {
        binding.ivSearchContentTop.setOnClickListener {
            mLayoutManager.scrollToPositionWithOffset(0, 0)
        }
        binding.ivSearchContentBottom.setOnClickListener {
            if (adapter.itemCount > 0) {
                mLayoutManager.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
            }
        }
        binding.tvCurrentSearchInfo.setOnClickListener {
            searchView.allViews.forEach { view ->
                if (view is EditText) {
                    view.showSoftInput()
                    return@setOnClickListener
                }
            }
        }
        binding.fbStop.setOnClickListener {
            searchJob?.cancel()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initBook(submit: Boolean = true) {
        binding.tvCurrentSearchInfo.text =
            this.getString(R.string.search_content_size) + ": ${viewModel.searchResultCounts}"
        viewModel.book?.let {
            initCacheFileNames(it)
            durChapterIndex = intent.getIntExtra("searchChapterIndex", it.durChapterIndex)
            val query = if (viewModel.hasSearchState) viewModel.lastQuery
                else intent.getStringExtra("searchWord").orEmpty()
            searchView.setQuery(query, submit)
        }
    }

    private fun initCacheFileNames(book: Book) {
        initJob = lifecycleScope.launch {
            withContext(IO) {
                viewModel.cacheChapterNames.addAll(BookHelp.getChapterFiles(book))
            }
            adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.book?.bookUrl?.let { bookUrl ->
                if (book.bookUrl == bookUrl) {
                    viewModel.cacheChapterNames.add(chapter.getFileName())
                    adapter.notifyItemChanged(chapter.index, true)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun startContentSearch(query: String) {
        // 按章节搜索内容
        if (query.isBlank()) return
        val generation = ++searchGeneration
        searchJob?.cancel()
        val options = ContentSearchOptions(
            SearchContentViewModel.searchScope,
            SearchContentViewModel.replaceEnabled,
            SearchContentViewModel.regexReplace,
            ReadBookConfig.splitChapterTitle,
            ReadBookConfig.titleMode == 2,
        )
        adapter.clearItems()
        viewModel.searchResultList.clear()
        viewModel.searchResultCounts = 0
        viewModel.hasSearchState = true
        viewModel.lastQuery = query
        viewModel.searchResultMessage = null
        binding.tvCurrentSearchInfo.text = getString(R.string.search_content_size) + ": 0"
        binding.refreshProgressBar.isAutoLoading = true
        binding.fbStop.visible()
        searchJob = lifecycleScope.launch {
            try {
                initJob?.join()
                val chapterList = withContext(IO) {
                    appDb.bookChapterDao.getChapterList(viewModel.bookUrl)
                }
                val anchorPos = if (options.scope == ContentSearchScope.ALL) -1 else {
                    chapterList.indexOfFirst { it.index == durChapterIndex }
                }
                val anchor = if (options.scope == ContentSearchScope.ALL) null else {
                    if (anchorPos < 0) throw ContentSearchAnchorException()
                    viewModel.searchAnchor?.takeIf { it.chapterIndex == durChapterIndex }
                        ?: throw ContentSearchAnchorException()
                }
                val chapters = when (options.scope) {
                    ContentSearchScope.ALL -> chapterList
                    ContentSearchScope.DOWN -> chapterList.subList(anchorPos, chapterList.size)
                    ContentSearchScope.UP -> chapterList.subList(0, anchorPos + 1).asReversed()
                }
                for (bookChapter in chapters) {
                    ensureActive()
                    if (!isLocalBook && !viewModel.cacheChapterNames.contains(bookChapter.getFileName())) {
                        if (anchor?.chapterIndex == bookChapter.index) throw ContentSearchAnchorException()
                        continue
                    }
                    val searchResults = withContext(IO) {
                        viewModel.searchChapter(query, bookChapter, options,
                            anchor?.takeIf { it.chapterIndex == bookChapter.index })
                    }
                    ensureActive()
                    if (generation != searchGeneration) return@launch
                    if (searchResults.isNotEmpty()) {
                        viewModel.searchResultList.addAll(searchResults)
                        viewModel.searchResultCounts = viewModel.searchResultList.size
                        binding.tvCurrentSearchInfo.text =
                            getString(R.string.search_content_size) + ": ${viewModel.searchResultCounts}"
                        adapter.addItems(searchResults)
                    }
                }
                if (viewModel.searchResultCounts == 0) {
                    viewModel.searchResultMessage = R.string.search_content_empty
                    adapter.addItem(SearchResult(resultText = getString(R.string.search_content_empty)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ContentSearchAnchorException) {
                ensureActive()
                if (generation != searchGeneration) return@launch
                viewModel.searchResultMessage = R.string.content_search_anchor_unavailable
                adapter.addItem(SearchResult(resultText = getString(R.string.content_search_anchor_unavailable)))
            } catch (e: Exception) {
                AppLog.put("全文搜索出错\n${e.localizedMessage}", e)
            } finally {
                if (generation == searchGeneration) {
                    binding.fbStop.invisible()
                    binding.refreshProgressBar.isAutoLoading = false
                }
            }
        }
    }

    private val isLocalBook: Boolean
        get() = viewModel.book?.isLocal == true

    override fun openSearchResult(searchResult: SearchResult, index: Int) {
        searchJob?.cancel()
        val results = viewModel.searchResultList.toList()
        postEvent(EventBus.SEARCH_RESULT, results)
        val searchData = Intent()
        val key = System.currentTimeMillis()
        IntentData.put("searchResult$key", searchResult)
        IntentData.put("searchResultList$key", results)
        searchData.putExtra("key", key)
        searchData.putExtra("index", index)
        setResult(RESULT_OK, searchData)
        finish()
    }

    override fun durChapterIndex(): Int {
        return durChapterIndex
    }

}
