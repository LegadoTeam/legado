package io.legado.app.ui.book.explore

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 发现列表
 */
class ExploreShowActivity : VMBaseActivity<ActivityExploreShowBinding, ExploreShowViewModel>(),
    ExploreShowAdapter.CallBack {
    override val binding by viewBinding(ActivityExploreShowBinding::inflate)
    override val viewModel by viewModels<ExploreShowViewModel>()

    private val adapter by lazy { ExploreShowAdapter(this, this) }
    private val loadMoreView by lazy { LoadMoreView(this) }
    private val loadMoreViewTop by lazy { LoadMoreView(this) }
    private var oldPage = -1
    private var isClearAll = false
    private val categoryTabs = arrayListOf<Pair<ExploreCategory, TabLayout.Tab>>()
    private var renderedCategories: List<ExploreCategory>? = null
    private var menuCategories: MenuItem? = null
    private var menuAddLoadedBooks: MenuItem? = null
    private var menuPage: MenuItem? = null

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuCategories = menu.add(Menu.NONE, R.id.menu_show_explore_categories, Menu.NONE,
            R.string.show_explore_categories).apply {
            isCheckable = true
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                AppConfig.showExploreCategories = !AppConfig.showExploreCategories
                updateCategories()
                true
            }
        }
        menuAddLoadedBooks = menu.add(R.string.add_loaded_books_to_bookshelf).apply {
            isEnabled = viewModel.addBooksBusy.value != true
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                alertAddLoadedBooksToShelf()
                true
            }
        }
        menuPage = menu.add(getString(R.string.menu_page, viewModel.pageLiveData.value ?: 1)).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                val page = viewModel.pageLiveData.value ?: 1
                NumberPickerDialog(this@ExploreShowActivity)
                    .setTitle(getString(R.string.change_page))
                    .setMaxValue(999)
                    .setMinValue(1)
                    .setValue(page)
                    .show {
                        if (page != it) {
                            updateTopHeader(it)
                            oldPage = it
                            viewModel.skipPage(it)
                            loadMoreViewTop.stopLoad()
                            loadMoreView.hasMore()
                            isClearAll = true
                            adapter.clearItems()
                            viewModel.explore()
                        }
                    }
                true
            }
        }
        updateCategories()
        return true
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = intent.getStringExtra("exploreName")
        initRecyclerView()
        viewModel.booksData.observe(this) { upData(it) }
        viewModel.categoryData.observe(this) {
            binding.titleBar.title = it.title
            updateCategorySelection()
        }
        viewModel.categoriesData.observe(this) { updateCategories() }
        viewModel.initData(intent, savedInstanceState)
        viewModel.errorLiveData.observe(this) {
            if (it != null) loadMoreView.error(it)
        }
        viewModel.errorTopLiveData.observe(this) {
            if (it != null) loadMoreViewTop.error(it)
        }
        viewModel.upAdapterLiveData.observe(this) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount, Bundle().apply {
                putString(it, null)
            })
        }
        viewModel.pageLiveData.observe(this) {
            menuPage?.title = getString(R.string.menu_page, it)
        }
        viewModel.addBooksBusy.observe(this) {
            menuAddLoadedBooks?.isEnabled = !it
        }
    }

    override fun onResume() {
        super.onResume()
        updateCategories()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        viewModel.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun updateCategories() {
        val show = AppConfig.showExploreCategories
        menuCategories?.isChecked = show
        if (show) viewModel.loadCategories()
        val categories = viewModel.categoriesData.value.orEmpty().map {
            ExploreCategory(it.title, it.url.orEmpty())
        }
        binding.categoriesContainer.isVisible = show && categories.isNotEmpty()
        if (!show || categories == renderedCategories) return
        renderedCategories = categories
        binding.categoriesContainer.removeAllViews()
        categoryTabs.clear()
        splitExploreCategoryRows(categories).forEach { row ->
            val tabs = TabLayout(this).apply {
                tabMode = TabLayout.MODE_SCROLLABLE
                tabGravity = TabLayout.GRAVITY_START
                minimumHeight = 40.dpToPx()
                setPadding(0, 0, 0, 0)
                setTabTextColors(getCompatColor(R.color.primaryText), accentColor)
                setSelectedTabIndicatorColor(accentColor)
                setTabIndicatorFullWidth(false)
            }
            row.forEach { category ->
                val tab = tabs.newTab().setText(category.title).setTag(category)
                tabs.addTab(tab, false)
                categoryTabs.add(category to tab)
            }
            (tabs.getChildAt(0) as? ViewGroup)?.let { tabStrip ->
                for (index in 0 until tabStrip.childCount) {
                    tabStrip.getChildAt(index).apply {
                        setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
                        minimumHeight = 40.dpToPx()
                    }
                }
            }
            tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    val category = tab.tag as ExploreCategory
                    if (category != viewModel.categoryData.value) {
                        isClearAll = false
                        binding.recyclerView.scrollToPosition(0)
                        viewModel.switchCategory(category)
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
            binding.categoriesContainer.addView(tabs, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        updateCategorySelection()
    }

    private fun updateCategorySelection() {
        val selected = viewModel.categoryData.value
        categoryTabs.forEach { (category, tab) ->
            if (category == selected) {
                tab.select()
            } else if (tab.isSelected) {
                tab.parent?.selectTab(null)
            }
        }
    }

    private fun updateTopHeader(page: Int) {
        if (page > 1 && adapter.getHeaderCount() == 0) {
            adapter.addHeaderView { ViewLoadMoreBinding.bind(loadMoreViewTop) }
        }
        loadMoreViewTop.layoutParams?.let {
            it.height = if (page > 1) ViewGroup.LayoutParams.WRAP_CONTENT else 0
            loadMoreViewTop.layoutParams = it
        }
    }

    private fun alertAddLoadedBooksToShelf() {
        val loadedBooks = viewModel.getLoadedBooks()
        if (loadedBooks.isEmpty()) {
            toastOnUi(R.string.no_loaded_books_to_add)
            return
        }
        alert(titleResource = R.string.add_loaded_books_to_bookshelf) {
            setMessage(
                getString(R.string.add_loaded_books_to_bookshelf_message, loadedBooks.size)
            )
            yesButton {
                val started = viewModel.addLoadedBooksToShelf(loadedBooks)
                if (!started) {
                    toastOnUi(R.string.add_loaded_books_to_bookshelf_in_progress)
                }
            }
            noButton()
        }
    }

    private fun initRecyclerView() {
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        adapter.addFooterView {
            ViewLoadMoreBinding.bind(loadMoreView)
        }
        loadMoreView.startLoad()
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading) {
                scrollToBottom(true)
            }
        }
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!recyclerView.canScrollVertically(1)) {
                    scrollToBottom()
                } else if (!recyclerView.canScrollVertically(-1) && dy < 0) {
                    scrollToTop()
                }
            }
        })
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        if ((loadMoreView.hasMore && !loadMoreView.isLoading && !loadMoreViewTop.isLoading) || forceLoad) {
            loadMoreView.hasMore()
            viewModel.explore()
        }
    }

    private fun scrollToTop(forceLoad: Boolean = false) {
        if ((oldPage > 1 && !loadMoreView.isLoading && !loadMoreViewTop.isLoading) || forceLoad) {
            loadMoreViewTop.hasMore()
            viewModel.explore(oldPage - 1)
        } else if (oldPage <= 1) {
            viewModel.showPage(1)
        }
    }

    private fun upData(state: ExploreListState) {
        val layoutManager = binding.recyclerView.layoutManager as LinearLayoutManager
        val hadBooks = !adapter.isEmpty()
        val position = layoutManager.findFirstVisibleItemPosition()
        val anchor = adapter.getItemByLayoutPosition(position)
        val offset = layoutManager.findViewByPosition(position)?.top ?: 0
        adapter.setItems(state.books)
        oldPage = state.firstPage
        updateTopHeader(oldPage)
        loadMoreViewTop.stopLoad()
        if (state.loading) {
            loadMoreView.hasMore()
        } else if (state.books.isEmpty()) {
            loadMoreView.noMore(getString(R.string.empty))
        } else if (!state.hasMore) {
            loadMoreView.noMore()
        } else {
            loadMoreView.hasMore()
            loadMoreView.stopLoad()
        }
        if (hadBooks && state.prependCount != null && position >= 0) {
            val target = anchor?.let { state.books.indexOf(it) }?.takeIf { it >= 0 }
                ?.plus(adapter.getHeaderCount()) ?: (position + state.prependCount)
            layoutManager.scrollToPositionWithOffset(target, offset)
        } else if (!state.loading && isClearAll) {
            layoutManager.scrollToPositionWithOffset(adapter.getHeaderCount(), 0)
            isClearAll = false
        }
    }

    override fun isInBookshelf(book: SearchBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    override fun showBookInfo(book: SearchBook) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }
}

internal fun <T> splitExploreCategoryRows(categories: List<T>): List<List<T>> {
    if (categories.isEmpty()) return emptyList()
    val rowCount = ((categories.size - 1) / 10 + 1).coerceAtMost(3)
    val perRow = categories.size / rowCount
    val extra = categories.size % rowCount
    var start = 0
    return List(rowCount) { row ->
        val end = start + perRow + if (row < extra) 1 else 0
        categories.subList(start, end).also { start = end }
    }
}
