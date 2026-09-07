package io.legado.app.ui.book.changesource

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle.State.RESUMED
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import io.legado.app.databinding.DialogBookChangeSourceBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatDrawable
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.startActivity
import io.legado.app.utils.transaction
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 换源界面
 */
class ChangeBookSourceDialog() : BaseDialogFragment(R.layout.dialog_book_change_source),
    Toolbar.OnMenuItemClickListener,
    ChangeBookSourceAdapter.CallBack {

    constructor(name: String, author: String) : this() {
        arguments = Bundle().apply {
            putString("name", name)
            putString("author", author)
        }
    }

    private val binding by viewBinding(DialogBookChangeSourceBinding::bind)
    private val groups = linkedSetOf<String>()
    private val callBack: CallBack? get() = activity as? CallBack
    private val viewModel: ChangeBookSourceViewModel by viewModels()
    private var waitDialog: WaitDialog? = null
    private var searchFinishDialog: AlertDialog? = null
    private var typeMismatchDialog: AlertDialog? = null
    private var adapterDataObserver: RecyclerView.AdapterDataObserver? = null
    private var autoScrollCurrentSource = true
    private val adapter by lazy { ChangeBookSourceAdapter(requireContext(), viewModel, this) }
    private val editSourceResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            val origin = it.data?.getStringExtra("origin") ?: return@registerForActivityResult
            viewModel.startSearch(origin)
        }
    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        autoScrollCurrentSource = true
        binding.toolBar.setBackgroundColor(primaryColor)
        viewModel.initData(arguments, callBack?.oldBook, activity is ReadBookActivity)
        showTitle()
        initMenu()
        initRecyclerView()
        initNavigationView()
        initSearchView()
        initBottomBar()
        initLiveData()
    }

    override fun onDestroyView() {
        adapterDataObserver?.let(adapter::unregisterAdapterDataObserver)
        adapterDataObserver = null
        binding.recyclerView.adapter = null
        searchFinishDialog?.dismiss()
        searchFinishDialog = null
        typeMismatchDialog?.dismiss()
        typeMismatchDialog = null
        waitDialog?.dismiss()
        waitDialog = null
        super.onDestroyView()
    }

    private fun showTitle() {
        binding.toolBar.title = viewModel.name
        binding.toolBar.subtitle = viewModel.author
        binding.toolBar.navigationIcon =
            getCompatDrawable(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolBar.elevation = requireContext().elevation
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

    private fun initRecyclerView() {
        val recyclerView = binding.recyclerView
        recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        recyclerView.adapter = adapter
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
        }.also(adapter::registerAdapterDataObserver)
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
            binding.toolBar.navigationIcon = null
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

    private fun initNavigationView() {
        binding.toolBar.navigationIcon =
            getCompatDrawable(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolBar.setNavigationContentDescription(
            R.string.back
        )
        binding.toolBar.setNavigationOnClickListener {
            dismissAllowingStateLoss()
        }
        kotlin.runCatching {
            val mNavButtonViewField = Toolbar::class.java.getDeclaredField("mNavButtonView")
            mNavButtonViewField.isAccessible = true
            val navigationView = mNavButtonViewField.get(binding.toolBar) as ImageButton
            val isLight = ColorUtils.isColorLight(primaryColor)
            val textColor = requireContext().getPrimaryTextColor(isLight)
            navigationView.setColorFilter(textColor)
        }
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
            binding.recyclerView.scrollToPosition(adapter.itemCount - 1)
        }
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
        viewModel.changeSourceLoading.observe(owner, ::showChangeSourceLoading)
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
                                "换源获取目录出错\n${result.throwable.localizedMessage}",
                                result.throwable,
                                true,
                            )
                        }

                        null -> Unit
                    }
                }
            }
        }
        owner.lifecycleScope.launch {
            owner.lifecycle.currentStateFlow.first { it.isAtLeast(STARTED) }
            viewModel.searchDataFlow.conflate().collect {
                adapter.setItems(it)
                if (autoScrollCurrentSource && it.isNotEmpty()) {
                    binding.recyclerView.post {
                        if (scrollToDurSource()) autoScrollCurrentSource = false
                    }
                }
                delay(1000)
            }
        }

        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(STARTED) {
                viewModel.changeSourceProgress
                    .collect { (count, name) ->
                        binding.tvDur.text = if (count == 0 && name.isEmpty()) {
                            callBack?.oldBook?.originName
                        } else {
                            getString(
                                R.string.change_source_progress,
                                adapter.itemCount,
                                count,
                                viewModel.totalSourceCount,
                                name
                            )
                        }
                        delay(500)
                    }
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
            cancelButton { event.take() }
            okButton {
                event.take()
                AppConfig.searchGroup = ""
                viewModel.startSearch()
                owner.lifecycleScope.launch {
                    upGroupMenuName()
                }
            }
            onCancelled { event.take() }
            onDismiss { dialog ->
                if (searchFinishDialog === dialog) searchFinishDialog = null
            }
        }
    }

    private fun showChangeSourceLoading(loading: Boolean) {
        if (!loading) {
            waitDialog?.dismiss()
            waitDialog = null
            return
        }
        if (waitDialog != null) return
        waitDialog = WaitDialog(requireContext()).also { dialog ->
            val cancelable = viewModel.changeSourceCancelable.value != false
            dialog.setText(R.string.load_toc)
            dialog.setCancelable(cancelable)
            if (cancelable) {
                dialog.setOnCancelListener {
                    viewModel.cancelChangeSource()
                }
            }
            dialog.show()
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
            R.id.menu_close -> dismissAllowingStateLoss()
            R.id.menu_refresh_list -> viewModel.startRefreshList()
            else -> if (item?.groupId == R.id.source_group && !item.isChecked) {
                item.isChecked = true
                if (item.title.toString() == getString(R.string.all_source)) {
                    AppConfig.searchGroup = ""
                } else {
                    AppConfig.searchGroup = item.title.toString()
                }
                upGroupMenuName()
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

    private fun scrollToDurSource(): Boolean {
        adapter.getItems().forEachIndexed { index, searchBook ->
            if (searchBook.bookUrl == oldBookUrl) {
                (binding.recyclerView.layoutManager as LinearLayoutManager)
                    .scrollToPositionWithOffset(index, 60.dpToPx())
                return true
            }
        }
        return false
    }

    override fun changeTo(searchBook: SearchBook) {
        val oldBookType = callBack?.oldBook?.type ?: 0
        if (searchBook.sameBookTypeLocal(oldBookType)) {
            changeSource(searchBook)
        } else {
            if (typeMismatchDialog != null) return
            typeMismatchDialog = alert(
                titleResource = R.string.book_type_different,
                messageResource = R.string.soure_change_source
            ) {
                okButton {
                    changeSource(searchBook)
                }
                cancelButton()
                onDismiss { dialog ->
                    if (typeMismatchDialog === dialog) typeMismatchDialog = null
                }
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

    private fun changeSource(searchBook: SearchBook) {
        val book = viewModel.bookMap[searchBook.primaryStr()] ?: searchBook.toBook()
        viewModel.changeSource(book)
    }

    /**
     * 更新分组菜单
     */
    private fun upGroupMenu() {
        binding.toolBar.menu.findItem(R.id.menu_group)?.run {
            subMenu?.transaction { menu ->
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
                if (hasSelectedGroup) {
                    title = getString(R.string.group) + "(" + AppConfig.searchGroup + ")"
                } else {
                    allItem.isChecked = true
                    title = getString(R.string.group)
                }
            }
        }
    }

    /**
     * 更新分组菜单名
     */
    private fun upGroupMenuName() {
        val menuGroup = binding.toolBar.menu.findItem(R.id.menu_group)
        val searchGroup = AppConfig.searchGroup
        if (searchGroup.isEmpty()) {
            menuGroup?.title = getString(R.string.group)
        } else {
            menuGroup?.title = getString(R.string.group) + "($searchGroup)"
        }
    }

    override fun observeLiveBus() {
        observeEvent<String>(EventBus.SOURCE_CHANGED) {
            adapter.notifyItemRangeChanged(
                0,
                adapter.itemCount,
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
    }

}
