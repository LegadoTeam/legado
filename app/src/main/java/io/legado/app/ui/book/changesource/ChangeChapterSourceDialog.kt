package io.legado.app.ui.book.changesource

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle.State.RESUMED
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStateAtLeast
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
import io.legado.app.databinding.DialogDownloadChoiceBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
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
    private var searchFinishDialog: AlertDialog? = null
    private var adapterDataObserver: RecyclerView.AdapterDataObserver? = null
    private var targetBook: Book? = null
    private var tocLoading = false
    private var contentLoading = false
    private var batchCaching = false
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
        initLiveData()
        initBatchMode()
        activity?.onBackPressedDispatcher?.addCallback(viewLifecycleOwner) {
            if (viewModel.isAutomationActive) {
                viewModel.stopAutomation()
                return@addCallback
            }
            if (batchCaching) {
                viewModel.cancelCacheContents()
                return@addCallback
            }
            if (batchMode && tocLoading) return@addCallback
            if (binding.clToc.isVisible) {
                binding.clToc.gone()
                viewModel.clearToc()
                return@addCallback
            }
            dismissAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        adapterDataObserver?.let(searchBookAdapter::unregisterAdapterDataObserver)
        adapterDataObserver = null
        binding.recyclerView.adapter = null
        binding.recyclerViewToc.adapter = null
        searchFinishDialog?.dismiss()
        searchFinishDialog = null
        super.onDestroyView()
    }

    private fun showTitle() {
        binding.toolBar.title = viewModel.currentOriginalChapter?.title ?: viewModel.chapterTitle
        if (batchMode) {
            binding.toolBar.subtitle = when (val state = viewModel.automationState.value) {
                is ChapterSourceAutomationState.Ready -> getString(
                    R.string.chapter_source_automation_progress,
                    state.position + 1,
                    state.total,
                )

                is ChapterSourceAutomationState.Caching -> getString(
                    R.string.chapter_source_automation_progress,
                    state.position + 1,
                    state.total,
                )

                is ChapterSourceAutomationState.Paused -> when (val reason = state.reason) {
                    ChapterSourceAutomationPause.Ambiguous -> getString(
                        R.string.chapter_source_automation_paused_ambiguous
                    )

                    ChapterSourceAutomationPause.Missing -> getString(
                        R.string.chapter_source_automation_paused_missing
                    )

                    is ChapterSourceAutomationPause.ContentError -> getString(
                        R.string.chapter_source_automation_paused_error,
                        reason.message,
                    )
                }

                is ChapterSourceAutomationState.Finished -> getString(
                    R.string.chapter_source_finished
                )

                else -> getString(
                    R.string.chapter_source_selected_count,
                    tocAdapter.selectionCount,
                )
            }
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
        binding.toolBar.menu.findItem(R.id.menu_chapter_source_automation)
            ?.isVisible = batchMode
        binding.toolBar.menu.syncChangeSourceResultOptions()
    }

    private fun initView() {
        binding.ivHideToc.setOnClickListener {
            if (batchMode && (tocLoading || batchCaching)) return@setOnClickListener
            binding.clToc.gone()
            viewModel.clearToc()
        }
        binding.flHideToc.elevation = requireContext().elevation
        binding.btnBatchSkip.setOnClickListener {
            if (viewModel.isAutomationActive) {
                if (viewModel.skipAutomationChapter() &&
                    viewModel.automationState.value is ChapterSourceAutomationState.Finished
                ) {
                    toastOnUi(R.string.chapter_source_finished)
                    dismissAllowingStateLoss()
                }
            } else {
                viewModel.currentOriginalChapter?.let(::advanceOriginalChapter)
            }
        }
        binding.btnBatchCacheNext.setOnClickListener {
            cacheSelectedChapters()
        }
        binding.btnBatchFinish.setOnClickListener {
            viewModel.stopAutomation()
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
        viewModel.loadOriginalChapters(oldBook.bookUrl)
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
        viewModel.searchFinishData.observe(owner) { event ->
            owner.lifecycleScope.launch {
                owner.lifecycle.withStateAtLeast(RESUMED) {
                    showEmptySearchGroupDialog(event, owner)
                }
            }
        }
        viewModel.originalChaptersState.observe(owner, ::showOriginalChaptersState)
        viewModel.tocState.observe(owner, ::showTocState)
        viewModel.contentLoading.observe(owner) { loading ->
            contentLoading = loading
            updateLoadingIndicator()
        }
        viewModel.contentResult.observe(owner) { event ->
            owner.lifecycleScope.launch {
                owner.lifecycle.withStateAtLeast(RESUMED) {
                    when (val result = event.take()) {
                        is ChapterContentResult.Success -> {
                            val callback = callBack ?: return@withStateAtLeast
                            callback.replaceContent(result.content)
                            dismissAllowingStateLoss()
                        }

                        is ChapterContentResult.Error -> {
                            binding.clToc.gone()
                            viewModel.clearToc()
                            toastOnUi(result.message)
                        }

                        null -> Unit
                    }
                }
            }
        }
        viewModel.changeSourceResult.observe(owner) { event ->
            owner.lifecycleScope.launch {
                owner.lifecycle.withStateAtLeast(RESUMED) {
                    when (val result = event.take()) {
                        is SourceChangeResult.Success -> {
                            val callback = callBack ?: return@withStateAtLeast
                            val sourceViewModel = viewModel
                            val completion = SourceChangeCompletion(
                                result.deleteAfterChange,
                                sourceViewModel::del,
                            )
                            callback.changeTo(
                                result.source,
                                result.book,
                                result.toc,
                                completion::success,
                            )
                            if (result.dismissDialog) dismissAllowingStateLoss()
                        }

                        is SourceChangeResult.Error -> {
                            AppLog.put(
                                "自动换源失败\n${result.throwable.localizedMessage}",
                                result.throwable,
                                true,
                            )
                        }

                        null -> Unit
                    }
                }
            }
        }
        viewModel.batchCaching.observe(owner, ::setBatchCaching)
        viewModel.automationState.observe(owner) { state ->
            showAutomationState(state)
            if (state is ChapterSourceAutomationState.Ready) {
                owner.lifecycleScope.launch {
                    owner.lifecycle.withStateAtLeast(RESUMED) {
                        if (viewModel.automationState.value == state) {
                            viewModel.runNextAutomationIfReady()
                        }
                    }
                }
            }
        }
        viewModel.batchCacheResult.observe(owner) { event ->
            owner.lifecycleScope.launch {
                owner.lifecycle.withStateAtLeast(RESUMED) {
                    when (val result = event.take()) {
                        is ChapterCacheResult.Success -> showCacheSuccess(result)
                        is ChapterCacheResult.Error -> toastOnUi(result.message)
                        null -> Unit
                    }
                }
            }
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

    private fun showEmptySearchGroupDialog(
        event: PendingEvent<Boolean>,
        owner: LifecycleOwner,
    ) {
        if (event.peek() != true) {
            event.take()
            return
        }
        val searchGroup = AppConfig.searchGroup
        if (searchGroup.isEmpty()) {
            event.take()
            return
        }
        if (searchFinishDialog != null) return
        searchFinishDialog = context?.alert("搜索结果为空") {
            setMessage("${searchGroup}分组搜索结果为空,是否切换到全部分组")
            noButton { event.take() }
            yesButton {
                event.take()
                AppConfig.searchGroup = ""
                viewModel.startSearch()
                owner.lifecycleScope.launch {
                    upGroupMenu()
                }
            }
            onCancelled { event.take() }
            onDismiss { dialog ->
                if (searchFinishDialog === dialog) searchFinishDialog = null
            }
        }
    }

    private fun showOriginalChaptersState(state: OriginalChaptersState) {
        when (state) {
            OriginalChaptersState.Loading -> Unit
            is OriginalChaptersState.Success -> {
                when {
                    viewModel.isBatchFinished -> {
                        updateBatchActions()
                    }

                    viewModel.currentOriginalChapter == null -> {
                        toastOnUi(R.string.chapter_list_empty)
                        dismissAllowingStateLoss()
                    }

                    else -> {
                        showTitle()
                        updateBatchActions()
                    }
                }
            }

            is OriginalChaptersState.Error -> {
                toastOnUi(state.message)
                dismissAllowingStateLoss()
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
            R.id.menu_chapter_source_automation -> {
                if (viewModel.isAutomationActive) {
                    viewModel.stopAutomation()
                } else {
                    showAutomationRangeDialog()
                }
            }

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
        if (viewModel.isAutomationActive || batchCaching || tocLoading) return
        viewModel.loadToc(searchBook.toBook())
    }

    private fun showTocState(state: ChapterTocState) {
        when (state) {
            ChapterTocState.Idle -> {
                targetBook = null
                setTocLoading(false)
            }

            is ChapterTocState.Loading -> {
                targetBook = state.book
                tocAdapter.clearSelection()
                tocAdapter.setItems(null)
                binding.clToc.visible()
                setTocLoading(true)
            }

            is ChapterTocState.Success -> {
                targetBook = state.book
                binding.clToc.visible()
                setTocLoading(false)
                tocAdapter.durChapterIndex = BookHelp.getDurChapter(
                    viewModel.chapterIndex,
                    viewModel.chapterTitle,
                    state.toc,
                    searchAllChapterNumbers = true,
                )
                tocAdapter.setItems(state.toc)
                binding.recyclerViewToc.scrollToPosition(tocAdapter.durChapterIndex - 5)
            }

            is ChapterTocState.Error -> {
                AppLog.put(
                    "单章换源获取目录出错\n${state.throwable.localizedMessage}",
                    state.throwable,
                    true,
                )
                setTocLoading(false)
                binding.clToc.gone()
                viewModel.clearToc()
            }
        }
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
        if (oldBookUrl == searchBook.bookUrl) {
            viewModel.autoChangeSource(callBack?.oldBook?.type, searchBook)
        } else {
            viewModel.del(searchBook)
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
        targetBook?.let {
            viewModel.loadContent(it, bookChapter, nextChapterUrl)
        }
    }

    override fun selectionChanged() {
        viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch {
            showTitle()
            updateBatchActions()
        }
    }

    private fun cacheSelectedChapters() {
        if (tocAdapter.selectionCount == 0 || tocLoading || batchCaching) return
        if (viewModel.isAutomationActive) {
            viewModel.cacheAutomationSelection(tocAdapter.selectedPositions)
            return
        }
        val sourceBook = targetBook ?: return
        val originalBook = callBack?.oldBook ?: return
        val chapter = viewModel.currentOriginalChapter ?: return
        val lastSelectedPosition = tocAdapter.lastSelectedPosition
        viewModel.cacheContents(
            sourceBook,
            tocAdapter.selectedChapters,
            originalBook,
            chapter,
            lastSelectedPosition + 1,
        )
    }

    private fun showCacheSuccess(result: ChapterCacheResult.Success) {
        callBack?.contentCached(result.cachedChapterIndex)
        tocAdapter.clearSelection()
        if (tocAdapter.itemCount > 0) {
            binding.recyclerViewToc.scrollToPosition(
                result.targetPosition.coerceAtMost(tocAdapter.itemCount - 1)
            )
        }
        result.automationSessionId?.let { sessionId ->
            if (!viewModel.acknowledgeAutomationCache(sessionId, result.cachedChapterIndex)) {
                showTitle()
                updateBatchActions()
                return
            }
            if (viewModel.automationState.value is ChapterSourceAutomationState.Finished) {
                toastOnUi(R.string.chapter_source_finished)
                dismissAllowingStateLoss()
            } else {
                showTitle()
                updateBatchActions()
            }
            return
        }
        if (result.nextChapter == null) {
            toastOnUi(R.string.chapter_source_finished)
            dismissAllowingStateLoss()
            return
        }
        showTitle()
        updateBatchActions()
    }

    private fun advanceOriginalChapter(chapter: BookChapter, targetPosition: Int? = null) {
        val nextChapter = viewModel.advanceOriginalChapter(chapter)
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
        showTitle()
        updateBatchActions()
    }

    private fun setTocLoading(loading: Boolean) {
        tocLoading = loading
        updateLoadingIndicator()
        updateBatchActions()
    }

    private fun setBatchCaching(caching: Boolean) {
        batchCaching = caching
        updateLoadingIndicator()
        updateBatchActions()
    }

    private fun updateLoadingIndicator() {
        binding.loadingToc.isVisible = tocLoading || contentLoading || batchCaching
    }

    private fun updateBatchActions() {
        if (!batchMode) return
        val hasOriginalChapter = viewModel.currentOriginalChapter != null
        val busy = tocLoading || batchCaching
        val automationState = viewModel.automationState.value
        val automationActive = viewModel.isAutomationActive
        val canSelect = !automationActive || automationState is ChapterSourceAutomationState.Paused
        tocAdapter.selectionEnabled = !busy && canSelect
        binding.ivHideToc.isEnabled = !busy && !automationActive
        binding.btnBatchSkip.isEnabled = hasOriginalChapter && !busy
        binding.btnBatchCacheNext.isEnabled = hasOriginalChapter &&
                tocAdapter.selectionCount > 0 && !busy && canSelect
        binding.btnBatchFinish.isEnabled = !batchCaching
    }

    private fun showAutomationRangeDialog() {
        val tocState = viewModel.tocState.value as? ChapterTocState.Success
        if (tocState == null) {
            toastOnUi(R.string.chapter_source_automation_select_target)
            return
        }
        val originalBook = callBack?.oldBook ?: return
        val range = viewModel.automationRangeDefaults()
        if (range == null) {
            toastOnUi(R.string.chapter_list_empty)
            return
        }
        alert(titleResource = R.string.chapter_source_automation) {
            val rangeBinding = DialogDownloadChoiceBinding.inflate(layoutInflater).apply {
                editStart.setText(range.first.toString())
                editEnd.setText(range.last.toString())
            }
            customView { rangeBinding.root }
            okButton {
                val start = rangeBinding.editStart.text?.toString()?.trim()?.toIntOrNull()
                val end = rangeBinding.editEnd.text?.toString()?.trim()?.toIntOrNull()
                if (start == null || end == null || !viewModel.startAutomation(
                        originalBook,
                        tocState.book,
                        tocState.toc,
                        start,
                        end,
                    )
                ) {
                    toastOnUi(R.string.chapter_source_automation_invalid_range)
                }
            }
            cancelButton()
        }
    }

    private fun showAutomationState(state: ChapterSourceAutomationState) {
        automationMenuItem?.setTitle(
            if (viewModel.isAutomationActive) {
                R.string.chapter_source_automation_stop
            } else {
                R.string.chapter_source_automation
            }
        )
        when (state) {
            is ChapterSourceAutomationState.Caching -> {
                tocAdapter.selectPositions(state.targetPositions)
            }

            is ChapterSourceAutomationState.Paused -> when (state.reason) {
                is ChapterSourceAutomationPause.ContentError -> {
                    tocAdapter.selectPositions(state.targetPositions)
                }

                else -> {
                    tocAdapter.clearSelection()
                    state.targetPositions.firstOrNull()?.let(binding.recyclerViewToc::scrollToPosition)
                }
            }

            is ChapterSourceAutomationState.Ready,
            is ChapterSourceAutomationState.Finished -> tocAdapter.clearSelection()

            ChapterSourceAutomationState.Idle -> Unit
        }
        showTitle()
        updateBatchActions()
    }

    private val automationMenuItem: MenuItem?
        get() = binding.toolBar.menu.findItem(R.id.menu_chapter_source_automation)

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
        fun changeTo(
            source: BookSource,
            book: Book,
            toc: List<BookChapter>,
            onSuccess: () -> Unit,
        )
        fun replaceContent(content: String)
        fun contentCached(chapterIndex: Int)
    }

}
