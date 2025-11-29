package io.legado.app.ui.book.explore

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.startActivity
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
    private val menuPage by lazy {
        binding.titleBar.menu.add(getString(R.string.menu_page, 1)).apply {
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
                            viewModel.explore(it)
                            adapter.clearItems() //清空，然后会自动触发scrollToBottom
                        }
                    }
                true
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = intent.getStringExtra("exploreName")
        initRecyclerView()
        viewModel.booksData.observe(this) { upData(it) }
        viewModel.initData(intent)
        viewModel.errorLiveData.observe(this) {
            loadMoreView.error(it)
        }
        viewModel.upAdapterLiveData.observe(this) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount, bundleOf(it to null))
        }
        viewModel.pageLiveData.observe(this) {
            menuPage.title = getString(R.string.menu_page, it)
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
                }
            }
        })
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        if ((loadMoreView.hasMore && !loadMoreView.isLoading) || forceLoad) {
            loadMoreView.hasMore()
            viewModel.explore()
        }
    }

    private fun upData(books: List<SearchBook>) {
        loadMoreView.stopLoad()
        if (books.isEmpty() && adapter.isEmpty()) {
            loadMoreView.noMore(getString(R.string.empty))
        } else if (adapter.getActualItemCount() == books.size) {
            loadMoreView.noMore()
        } else {
            adapter.setItems(books)
        }
    }

    override fun isInBookshelf(name: String, author: String): Boolean {
        return viewModel.isInBookShelf(name, author)
    }

    override fun showBookInfo(book: Book) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }
}
