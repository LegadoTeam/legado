package io.legado.app.ui.main.bookshelf.style2

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewConfiguration
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf2Binding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.utils.MenuExtensions
import io.legado.app.utils.cnCompare
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.getCompatDrawable
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setTintMutate
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 书架界面
 */
class BookshelfFragment2() : BaseBookshelfFragment(R.layout.fragment_bookshelf2),
    SearchView.OnQueryTextListener,
    BaseBooksAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBookshelf2Binding::bind)
    private val bookshelfLayout by lazy { AppConfig.bookshelfLayout }
    private val booksAdapter: BaseBooksAdapter<*> by lazy {
        if (bookshelfLayout >= 2) {
            BooksAdapterGrid(requireContext(), this)
        } else {
            BooksAdapterList(requireContext(), this)
        }
    }
    private var bookGroups: List<BookGroup> = emptyList()
    private val bookGroupsFlow = MutableStateFlow<List<BookGroup>>(emptyList())
    private var groupItems: List<BookshelfGroupItem> = emptyList()
    private var booksFlowJob: Job? = null
    override var groupId = BookGroup.IdRoot
    override var books: List<Book> = emptyList()
    private var enableRefresh = true
    override var onlyUpdateRead = false
    private val bookshelfMargin by lazy { AppConfig.bookshelfMargin }
    private var itemCount = 0
    private var totalRows = 0

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        binding.titleBar.setNavigationOnClickListener { back() }
        bindShelfHeader(binding.shelfHeader)
        initRecyclerView()
        initBookGroupData()
        initBooksData()
    }

    private fun initRecyclerView() {
        binding.rvBookshelf.setEdgeEffectColor(primaryColor)
        binding.rvBookshelf.canHandleHorizontalSwipe = { offset ->
            adjacentBookshelfGroupId(bookGroups, groupId, offset) != null
        }
        binding.rvBookshelf.onHorizontalSwipe = ::switchBookGroup
        binding.fastScroller.attachRecyclerView(binding.rvBookshelf)
        upFastScrollerBar()
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            activityViewModel.upToc(books, onlyUpdateRead)
        }
        if (bookshelfLayout >= 2) {
            binding.rvBookshelf.layoutManager = GridLayoutManager(context, bookshelfLayout)
        } else {
            binding.rvBookshelf.layoutManager = LinearLayoutManager(context)
        }
        binding.rvBookshelf.adapter = booksAdapter
        /**
         * 采用 layoutManager?.onRestoreInstanceState(layoutState)
         * 恢复滚动位置
         * **/
        binding.rvBookshelf.itemAnimator =  null
        binding.rvBookshelf.addItemDecoration( object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val position = parent.getChildAdapterPosition(view)
                if (bookshelfLayout >= 2) {
                    val spanCount = bookshelfLayout
                    val rowIndex = position / spanCount
                    when (rowIndex) {
                        0 -> { //第一行加额外上边距
                            outRect.set(bookshelfMargin, bookshelfMargin + 24, bookshelfMargin, bookshelfMargin)
                        }
                        totalRows - 1 -> { //最后一行加额外下边距
                            outRect.set(bookshelfMargin, bookshelfMargin, bookshelfMargin, bookshelfMargin + 24)
                        }
                        else -> {
                            outRect.set(bookshelfMargin, bookshelfMargin, bookshelfMargin, bookshelfMargin)
                        }
                    }
                } else {
                    when (position) {
                        0 -> {
                            outRect.set(0, bookshelfMargin + 24, 0, bookshelfMargin)
                        }
                        itemCount - 1 -> {
                            outRect.set(0, bookshelfMargin, 0, bookshelfMargin + 24)
                        }
                        else -> {
                            outRect.set(0, bookshelfMargin, 0, bookshelfMargin)
                        }
                    }
                }
            }
        })
    }

    private fun upFastScrollerBar() {
        val show = AppConfig.showBookshelfFastScroller
        binding.fastScroller.isEnabled = show
        binding.rvBookshelf.scrollBarSize = if (show) {
            0
        } else {
            ViewConfiguration.get(requireContext()).scaledScrollBarSize
        }
    }

    override fun upGroup(data: List<BookGroup>) {
        if (data != bookGroups) {
            bookGroups = data
            bookGroupsFlow.value = data
        }
    }

    override fun upSort() {
        initBooksData()
    }

    private fun initBooksData() {
        upNavigationIcon()
        if (groupId == BookGroup.IdRoot) {
            if (isAdded) {
                binding.titleBar.title = getString(R.string.bookshelf)
                binding.refreshLayout.isEnabled = true
                enableRefresh = true
            }
        } else {
            bookGroups.firstOrNull {
                groupId == it.groupId
            }?.let {
                binding.titleBar.title = "${getString(R.string.bookshelf)}(${it.groupName})"
                binding.refreshLayout.isEnabled = it.enableRefresh
                enableRefresh = it.enableRefresh
                onlyUpdateRead = it.onlyUpdateRead
            }
        }
        booksFlowJob?.cancel()
        val currentGroupId = groupId
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            val bookDao = appDb.bookDao
            val sortedBooksFlow = bookDao.flowByGroup(currentGroupId).map { currentBooks ->
                sortBooks(currentBooks, currentGroupId)
            }
            val booksFlow = if (currentGroupId == BookGroup.IdRoot) {
                val shelfDataFlow = combine(
                    sortedBooksFlow,
                    bookDao.flowBookshelfBooks(),
                ) { currentBooks, allBooks ->
                    currentBooks to allBooks
                }.flowWithLifecycleAndDatabaseChangeFirst(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_TABLE_NAME,
                    AppDatabase.BOOK_GROUP_TABLE_NAME,
                )
                combine(shelfDataFlow, bookGroupsFlow) { (currentBooks, allBooks), groups ->
                    currentBooks to buildBookshelfGroupItems(groups, allBooks)
                }
            } else {
                sortedBooksFlow.map { it to emptyList<BookshelfGroupItem>() }
                    .flowWithLifecycleAndDatabaseChangeFirst(
                        viewLifecycleOwner.lifecycle,
                        Lifecycle.State.RESUMED,
                        AppDatabase.BOOK_TABLE_NAME,
                    )
            }
            booksFlow.catch {
                AppLog.put("书架更新出错", it)
            }.conflate().flowOn(Dispatchers.Default).collect { (currentBooks, items) ->
                if (groupId != currentGroupId) return@collect
                books = currentBooks
                if (currentGroupId == BookGroup.IdRoot) {
                    groupItems = items
                }
                booksAdapter.updateItems(currentGroupId)
                itemCount = getItemCount()
                val spanCount = bookshelfLayout
                if (spanCount >= 2) {
                    totalRows = if (itemCount % spanCount == 0) itemCount / spanCount else itemCount / spanCount + 1
                }
                binding.tvEmptyMsg.isGone = itemCount > 0
                binding.refreshLayout.isEnabled = enableRefresh && itemCount > 0
                delay(100)
            }
        }
    }

    private fun upNavigationIcon() {
        binding.titleBar.toolbar.apply {
            navigationIcon = if (groupId == BookGroup.IdRoot) {
                null
            } else {
                getCompatDrawable(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            }
            navigationContentDescription = getString(R.string.back)
            navigationIcon?.setTintMutate(
                MenuExtensions.getMenuColor(
                    requireContext(),
                    transparentBar = binding.titleBar.usesTransparentForeground,
                )
            )
        }
    }

    private fun switchBookGroup(offset: Int) {
        val targetGroupId = adjacentBookshelfGroupId(bookGroups, groupId, offset) ?: return
        groupId = targetGroupId
        initBooksData()
    }

    private fun sortBooks(books: List<Book>, groupId: Long): List<Book> {
        return when (AppConfig.getBookSortByGroupId(groupId)) {
            1 -> books.sortedByDescending { it.latestChapterTime }
            2 -> books.sortedWith { first, second -> first.name.cnCompare(second.name) }
            3 -> books.sortedBy { it.order }
            4 -> books.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
            else -> books.sortedByDescending { it.durChapterTime }
        }
    }

    fun back(): Boolean {
        if (groupId != BookGroup.IdRoot) {
            groupId = BookGroup.IdRoot
            initBooksData()
            return true
        }
        return false
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        SearchActivity.start(requireContext(), query)
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        return false
    }

    override fun gotoTop() {
        if (AppConfig.isEInkMode) {
            binding.rvBookshelf.scrollToPosition(0)
        } else {
            binding.rvBookshelf.smoothScrollToPosition(0)
        }
    }

    override fun onItemClick(item: Any) {
        when (item) {
            is Book -> startActivityForBook(item)

            is BookGroup -> {
                groupId = item.groupId
                initBooksData()
            }
        }
    }

    override fun onItemLongClick(item: Any) {
        when (item) {
            is Book -> startActivity<BookInfoActivity> {
                putExtra("name", item.name)
                putExtra("author", item.author)
            }

            is BookGroup -> showDialogFragment(GroupEditDialog(item))
        }
    }

    override fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    fun getItemCount(): Int {
        return if (groupId == BookGroup.IdRoot) {
            groupItems.size + books.size
        } else {
            books.size
        }
    }

    override fun getItems(): List<Any> {
        if (groupId != BookGroup.IdRoot) {
            return books
        }
        return groupItems + books
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            booksAdapter.notification(it)
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            booksAdapter.notifyDataSetChanged()
            upFastScrollerBar()
        }
    }

    override fun onDestroyView() {
        binding.rvBookshelf.canHandleHorizontalSwipe = null
        binding.rvBookshelf.onHorizontalSwipe = null
        binding.fastScroller.detachRecyclerView()
        super.onDestroyView()
    }
}

internal fun adjacentBookshelfGroupId(
    groups: List<BookGroup>,
    currentGroupId: Long,
    offset: Int,
): Long? {
    val currentIndex = groups.indexOfFirst { it.groupId == currentGroupId }
    if (currentIndex < 0) return null
    return groups.getOrNull(currentIndex + offset)?.groupId
}
