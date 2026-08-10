package io.legado.app.ui.book.changesource

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.DialogChapterChangeSourceBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.transaction
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


class ChangeChapterSourceDialog() : BaseDialogFragment(R.layout.dialog_chapter_change_source),
    Toolbar.OnMenuItemClickListener,
    ChangeChapterSourceAdapter.CallBack,
    ChangeChapterTocAdapter.Callback {

    constructor(
        name: String,
        author: String,
        chapterIndex: Int,
        chapterTitle: String,
        batchMode: Boolean = false,
    ) : this() {
        arguments = Bundle().apply {
            putString("name", name)
            putString("author", author)
            putInt("chapterIndex", chapterIndex)
            putString("chapterTitle", chapterTitle)
            putBoolean("batchMode", batchMode)
        }
    }

    private val binding by viewBinding(DialogChapterChangeSourceBinding::bind)
    private val batchMode: Boolean get() = arguments?.getBoolean("batchMode") == true
    private val groups = linkedSetOf<String>()
    private val callBack: CallBack? get() = activity as? CallBack
    private val viewModel: ChangeChapterSourceViewModel by viewModels()
    private val editSourceResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            viewModel.startSearch()
        }
    private val searchBookAdapter by lazy {
        ChangeChapterSourceAdapter(requireContext(), viewModel, this)
    }
    private val tocAdapter by lazy {
        ChangeChapterTocAdapter(requireContext(), this)
    }
    private var searchFinishCallback: ((isEmpty: Boolean) -> Unit)? = null
    private var adapterDataObserver: RecyclerView.AdapterDataObserver? = null
    private var targetBook: Book? = null
    private var originalChapters = emptyList<BookChapter>()
    private var originalChapter: BookChapter? = null
    private var tocLoading = false
    private var batchCaching = false
    private var cacheTask: Coroutine<*>? = null
    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        viewModel.initData(arguments, callBack?.oldBook, activity is ReadBookActivity)
        showTitle()
        initMenu()
        initView()
        initRecyclerView()
        initSearchView()
        initBottomBar()
        initBatchMode()
        initLiveData()
        bindSearchFinishCallback()
        activity?.onBackPressedDispatcher?.addCallback(viewLifecycleOwner) {
            if (batchCaching) {
                cacheTask?.cancel()
                cacheTask = null
                setBatchCaching(false)
                return@addCallback
            }
            if (batchMode && tocLoading) return@addCallback
            if (binding.clToc.isVisible) {
                binding.clToc.gone()
                return@addCallback
            }
            dismissAllowingStateLoss()
        }
    }

    override fun onDestroy() {
        cacheTask?.cancel()
        super.onDestroy()
    }

    override fun onDestroyView() {
        searchFinishCallback?.let { callback ->
            if (viewModel.searchFinishCallback === callback) {
                viewModel.searchFinishCallback = null
            }
        }
        searchFinishCallback = null
        adapterDataObserver?.let(searchBookAdapter::unregisterAdapterDataObserver)
        adapterDataObserver = null
        binding.recyclerView.adapter = null
        binding.recyclerViewToc.adapter = null
        super.onDestroyView()
    }

    private fun bindSearchFinishCallback() {
        val owner = viewLifecycleOwner
        val callback: (Boolean) -> Unit = { isEmpty ->
            if (isEmpty) {
                val searchGroup = AppConfig.searchGroup
                if (searchGroup.isNotEmpty()) {
                    owner.lifecycleScope.launch {
                        context?.alert("搜索结果为空") {
                            setMessage("${searchGroup}分组搜索结果为空,是否切换到全部分组")
                            noButton()
                            yesButton {
                                AppConfig.searchGroup = ""
                                viewModel.startSearch()
                                owner.lifecycleScope.launch {
                                    upGroupMenu()
                                }
                            }
                        }
                    }
                }
            }
        }
        searchFinishCallback = callback
        viewModel.searchFinishCallback = callback
    }

    private fun showTitle() {
        binding.toolBar.title = originalChapter?.title ?: viewModel.chapterTitle
        if (batchMode) {
            binding.toolBar.subtitle = getString(
                R.string.chapter_source_selected_count,
                tocAdapter.selectionCount,
            )
        }
    }

    private fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.change_source)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
        binding.toolBar.menu.findItem(R.id.menu_check_author)
            ?.isChecked = AppConfig.changeSourceCheckAuthor
        binding.toolBar.menu.findItem(R.id.menu_load_info)
            ?.isChecked = AppConfig.changeSourceLoadInfo
        binding.toolBar.menu.findItem(R.id.menu_load_toc)
            ?.isChecked = AppConfig.changeSourceLoadToc
        binding.toolBar.menu.syncChangeSourceResultOptions()
    }

    private fun initView() {
        binding.ivHideToc.setOnClickListener {
            if (batchMode && (tocLoading || batchCaching)) return@setOnClickListener
            binding.clToc.gone()
        }
        binding.flHideToc.elevation = requireContext().elevation
        binding.btnBatchSkip.setOnClickListener {
            originalChapter?.let(::advanceOriginalChapter)
        }
        binding.btnBatchCacheNext.setOnClickListener {
            cacheSelectedChapters()
        }
        binding.btnBatchFinish.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    private fun initRecyclerView() {
        val recyclerView = binding.recyclerView
        recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        recyclerView.adapter = searchBookAdapter
        adapterDataObserver = object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (positionStart == 0) {
                    recyclerView.scrollToPosition(0)
                }
            }

            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                if (toPosition == 0) {
                    recyclerView.scrollToPosition(0)
                }
            }
        }.also(searchBookAdapter::registerAdapterDataObserver)
        binding.recyclerViewToc.adapter = tocAdapter
    }

    private fun initSearchView() {
        val searchView = binding.toolBar.menu.findItem(R.id.menu_screen).actionView as SearchView
        searchView.setOnCloseListener {
            showTitle()
            false
        }
        searchView.setOnSearchClickListener {
            binding.toolBar.title = ""
            binding.toolBar.subtitle = ""
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.screen(newText)
                return false
            }

        })
    }

    private fun initBottomBar() {
        binding.tvDur.text = callBack?.oldBook?.originName
        binding.tvDur.setOnClickListener {
            scrollToDurSource()
        }
        binding.ivTop.setOnClickListener {
            binding.recyclerView.scrollToPosition(0)
        }
        binding.ivBottom.setOnClickListener {
            binding.recyclerView.scrollToPosition(searchBookAdapter.itemCount - 1)
        }
    }

    private fun initBatchMode() {
        tocAdapter.batchMode = batchMode
        binding.llBatchActions.isVisible = batchMode
        if (!batchMode) return
        binding.toolBar.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        updateBatchActions()
        val oldBook = callBack?.oldBook ?: return
        val owner = viewLifecycleOwner
        viewModel.getOriginalChapters(oldBook.bookUrl, { chapters ->
            originalChapters = chapters
            originalChapter = chapters.firstOrNull { it.index == viewModel.chapterIndex }
            if (originalChapter == null) {
                owner.lifecycleScope.launch {
                    toastOnUi(R.string.chapter_list_empty)
                    dismissAllowingStateLoss()
                }
                return@getOriginalChapters
            }
            owner.lifecycleScope.launch {
                showTitle()
                updateBatchActions()
            }
        }, { message ->
            owner.lifecycleScope.launch {
                toastOnUi(message)
                dismissAllowingStateLoss()
            }
        })
    }

    private fun initLiveData() {
        val owner = viewLifecycleOwner
        viewModel.searchStateData.observe(viewLifecycleOwner) {
            binding.refreshProgressBar.isAutoLoading = it
            if (it) {
                startStopMenuItem?.let { item ->
                    item.setIcon(R.drawable.ic_stop_black_24dp)
                    item.setTitle(R.string.stop)
                }
            } else {
                startStopMenuItem?.let { item ->
                    item.setIcon(R.drawable.ic_refresh_black_24dp)
                    item.setTitle(R.string.refresh)
                }
            }
            binding.toolBar.menu.applyTint(requireContext())
        }
        owner.lifecycleScope.launch {
            owner.lifecycle.currentStateFlow.first { it.isAtLeast(STARTED) }
            viewModel.searchDataFlow.conflate().collect {
                searchBookAdapter.setItems(it)
                delay(1000)
            }
        }
        owner.lifecycleScope.launch {
            appDb.bookSourceDao.flowEnabledGroups().conflate().collect {
                groups.clear()
                groups.addAll(it)
                upGroupMenu()
            }
        }
    }

    private val startStopMenuItem: MenuItem?
        get() = binding.toolBar.menu.findItem(R.id.menu_start_stop)

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_check_author -> {
                AppConfig.changeSourceCheckAuthor = !item.isChecked
                item.isChecked = !item.isChecked
                viewModel.refresh()
            }

            R.id.menu_load_info -> {
                AppConfig.changeSourceLoadInfo = !item.isChecked
                item.isChecked = !item.isChecked
            }

            R.id.menu_load_toc -> {
                AppConfig.changeSourceLoadToc = !item.isChecked
                item.isChecked = !item.isChecked
            }

            R.id.menu_load_word_count -> {
                AppConfig.changeSourceLoadWordCount = !item.isChecked
                binding.toolBar.menu.syncChangeSourceResultOptions()
                viewModel.onLoadWordCountChecked()
            }

            R.id.menu_sort_respond_time -> {
                val enabled = !item.isChecked
                AppConfig.changeSourceSortRespondTime = enabled
                binding.toolBar.menu.syncChangeSourceResultOptions()
                viewModel.onResultOptionsChanged(enabled)
            }

            R.id.menu_word_count_filter -> showChangeSourceWordCountFilter { reload ->
                binding.toolBar.menu.syncChangeSourceResultOptions()
                viewModel.onResultOptionsChanged(reload)
            }

            R.id.menu_start_stop -> viewModel.startOrStopSearch()
            R.id.menu_source_manage -> startActivity<BookSourceActivity>()
            else -> if (item?.groupId == R.id.source_group && !item.isChecked) {
                item.isChecked = true
                if (item.title.toString() == getString(R.string.all_source)) {
                    AppConfig.searchGroup = ""
                } else {
                    AppConfig.searchGroup = item.title.toString()
                }
                lifecycleScope.launch(IO) {
                    viewModel.stopSearch()
                    if (viewModel.refresh()) {
                        viewModel.startSearch()
                    }
                }
            }
        }
        return false
    }

    private fun scrollToDurSource() {
        searchBookAdapter.getItems().forEachIndexed { index, searchBook ->
            if (searchBook.bookUrl == oldBookUrl) {
                (binding.recyclerView.layoutManager as LinearLayoutManager)
                    .scrollToPositionWithOffset(index, 60.dpToPx())
                return
            }
        }
    }

    override fun openToc(searchBook: SearchBook) {
        val owner = viewLifecycleOwner
        tocAdapter.clearSelection()
        tocAdapter.setItems(null)
        binding.clToc.visible()
        if (batchMode) {
            setTocLoading(true)
        } else {
            binding.loadingToc.visible()
        }
        val book = searchBook.toBook()
        targetBook = book
        viewModel.getToc(book, { toc: List<BookChapter>, _: BookSource ->
            owner.lifecycleScope.launch {
                tocAdapter.durChapterIndex =
                    BookHelp.getDurChapter(
                        viewModel.chapterIndex,
                        viewModel.chapterTitle,
                        toc,
                        searchAllChapterNumbers = true,
                    )
                if (batchMode) {
                    setTocLoading(false)
                } else {
                    binding.loadingToc.gone()
                }
                tocAdapter.setItems(toc)
                binding.recyclerViewToc.scrollToPosition(tocAdapter.durChapterIndex - 5)
            }
        }, {
            AppLog.put("单章换源获取目录出错\n$it", it, true)
            owner.lifecycleScope.launch {
                if (batchMode) setTocLoading(false)
                binding.clToc.gone()
            }
        })
    }

    override val oldBookUrl: String?
        get() = callBack?.oldBook?.bookUrl

    override fun topSource(searchBook: SearchBook) {
        viewModel.topSource(searchBook)
    }

    override fun bottomSource(searchBook: SearchBook) {
        viewModel.bottomSource(searchBook)
    }

    override fun editSource(searchBook: SearchBook) {
        editSourceResult.launch {
            putExtra("sourceUrl", searchBook.origin)
        }
    }

    override fun disableSource(searchBook: SearchBook) {
        viewModel.disableSource(searchBook)
    }

    override fun deleteSource(searchBook: SearchBook) {
        viewModel.del(searchBook)
        if (oldBookUrl == searchBook.bookUrl) {
            viewModel.autoChangeSource(callBack?.oldBook?.type) { book, toc, source ->
                callBack?.changeTo(source, book, toc)
            }
        }
    }

    override fun setBookScore(searchBook: SearchBook, score: Int) {
        viewModel.setBookScore(searchBook, score)
    }

    override fun getBookScore(searchBook: SearchBook): Int {
        return viewModel.getBookScore(searchBook)
    }

    override fun clickChapter(bookChapter: BookChapter, nextChapterUrl: String?) {
        if (batchMode) return
        val owner = viewLifecycleOwner
        targetBook?.let {
            binding.loadingToc.visible()
            viewModel.getContent(it, bookChapter, nextChapterUrl, { content ->
                callBack?.replaceContent(content)
                owner.lifecycleScope.launch {
                    binding.loadingToc.gone()
                    dismissAllowingStateLoss()
                }
            }, { msg ->
                owner.lifecycleScope.launch {
                    binding.loadingToc.gone()
                    binding.clToc.gone()
                    toastOnUi(msg)
                }
            })
        }
    }

    override fun selectionChanged() {
        showTitle()
        updateBatchActions()
    }

    private fun cacheSelectedChapters() {
        val sourceBook = targetBook ?: return
        val sourceChapters = tocAdapter.selectedChapters
        val originalBook = callBack?.oldBook ?: return
        val chapter = originalChapter ?: return
        if (sourceChapters.isEmpty() || tocLoading || batchCaching) return
        val lastSelectedPosition = tocAdapter.lastSelectedPosition
        setBatchCaching(true)
        cacheTask = viewModel.cacheContents(
            sourceBook,
            sourceChapters,
            originalBook,
            chapter,
            success = {
                cacheTask = null
                batchCaching = false
                callBack?.contentCached(chapter.index)
                val owner = viewLifecycleOwnerLiveData.value
                owner?.lifecycleScope?.launch {
                    binding.loadingToc.isVisible = false
                    updateBatchActions()
                    advanceOriginalChapter(chapter, lastSelectedPosition + 1)
                }
            },
            error = { message ->
                cacheTask = null
                batchCaching = false
                val owner = viewLifecycleOwnerLiveData.value
                owner?.lifecycleScope?.launch {
                    binding.loadingToc.isVisible = false
                    updateBatchActions()
                    toastOnUi(message)
                }
            },
        )
    }

    private fun advanceOriginalChapter(chapter: BookChapter, targetPosition: Int? = null) {
        val nextChapter = nextChapterSourceOriginal(originalChapters, chapter.index)
        tocAdapter.clearSelection()
        if (targetPosition != null && tocAdapter.itemCount > 0) {
            binding.recyclerViewToc.scrollToPosition(
                targetPosition.coerceAtMost(tocAdapter.itemCount - 1)
            )
        }
        if (nextChapter == null) {
            toastOnUi(R.string.chapter_source_finished)
            dismissAllowingStateLoss()
            return
        }
        originalChapter = nextChapter
        viewModel.chapterIndex = nextChapter.index
        viewModel.chapterTitle = nextChapter.title
        showTitle()
        updateBatchActions()
    }

    private fun setTocLoading(loading: Boolean) {
        tocLoading = loading
        binding.loadingToc.isVisible = loading
        updateBatchActions()
    }

    private fun setBatchCaching(caching: Boolean) {
        batchCaching = caching
        binding.loadingToc.isVisible = caching
        updateBatchActions()
    }

    private fun updateBatchActions() {
        if (!batchMode) return
        val hasOriginalChapter = originalChapter != null
        val busy = tocLoading || batchCaching
        tocAdapter.selectionEnabled = !busy
        binding.ivHideToc.isEnabled = !busy
        binding.btnBatchSkip.isEnabled = hasOriginalChapter && !busy
        binding.btnBatchCacheNext.isEnabled = hasOriginalChapter &&
                tocAdapter.selectionCount > 0 && !busy
        binding.btnBatchFinish.isEnabled = !batchCaching
    }

    /**
     * 更新分组菜单
     */
    private fun upGroupMenu() {
        binding.toolBar.menu.findItem(R.id.menu_group)?.subMenu?.transaction { menu ->
            val selectedGroup = AppConfig.searchGroup
            menu.removeGroup(R.id.source_group)
            val allItem = menu.add(R.id.source_group, Menu.NONE, Menu.NONE, R.string.all_source)
            var hasSelectedGroup = false
            groups.forEach { group ->
                menu.add(R.id.source_group, Menu.NONE, Menu.NONE, group)?.let {
                    if (group == selectedGroup) {
                        it.isChecked = true
                        hasSelectedGroup = true
                    }
                }
            }
            menu.setGroupCheckable(R.id.source_group, true, true)
            if (!hasSelectedGroup) {
                allItem.isChecked = true
            }
        }
    }

    override fun observeLiveBus() {
        observeEvent<String>(EventBus.SOURCE_CHANGED) {
            searchBookAdapter.notifyItemRangeChanged(
                0,
                searchBookAdapter.itemCount,
                Bundle().apply {
                    putString("upCurSource", oldBookUrl)
                }
            )
        }
    }

    interface CallBack {
        val oldBook: Book?
        fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>)
        fun replaceContent(content: String)
        fun contentCached(chapterIndex: Int)
    }

}
