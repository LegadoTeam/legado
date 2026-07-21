package io.legado.app.ui.book.toc

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.FragmentChapterListBinding
import io.legado.app.help.audio.AudioCacheManager
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.AudioCacheKey
import io.legado.app.model.AudioCacheStateChanged
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.observeEvent
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChapterListFragment : VMBaseFragment<TocViewModel>(R.layout.fragment_chapter_list),
    ChapterListAdapter.Callback,
    TocViewModel.ChapterListCallBack {

    override val viewModel by activityViewModels<TocViewModel>()
    private val binding by viewBinding(FragmentChapterListBinding::bind)
    private val layoutManager by lazy { UpLinearLayoutManager(requireContext()) }
    private val adapter by lazy { ChapterListAdapter(requireContext(), this) }
    private val tocListState = TocListState()
    private var durChapterIndex = 0
    private var chapterList: List<BookChapter> = emptyList()
    private var currentSearchKey: String? = null
    private var chapterListJob: Job? = null
    private var cacheFileJob: Job? = null
    private var audioCacheStateReady = false
    private val pendingAudioCacheChanges = linkedMapOf<AudioCacheKey, Boolean>()
    private var pendingScrollItemKey: String? = null
    private var pendingChapterScroll: Int? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        viewModel.chapterListCallBack = this@ChapterListFragment
        val background = bottomBackground
        val foreground = requireContext().getPrimaryTextColor(ColorUtils.isColorLight(background))
        llChapterBaseInfo.setBackgroundColor(background)
        tvCurrentChapterInfo.setTextColor(foreground)
        ivChapterTop.setColorFilter(foreground, PorterDuff.Mode.SRC_IN)
        ivChapterBottom.setColorFilter(foreground, PorterDuff.Mode.SRC_IN)
        initRecyclerView()
        initView()
        viewModel.bookData.observe(this@ChapterListFragment) {
            initBook(it)
        }
    }

    override fun onDestroyView() {
        chapterListJob?.cancel()
        cacheFileJob?.cancel()
        pendingScrollItemKey = null
        pendingChapterScroll = null
        binding.recyclerView.adapter = null
        adapter.release()
        viewModel.chapterListCallBack = clearCallbackIfOwned(
            viewModel.chapterListCallBack,
            this,
        )
        super.onDestroyView()
    }

    private fun initRecyclerView() {
        adapter.attach()
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
    }

    private fun initView() = binding.run {
        ivChapterTop.setOnClickListener {
            layoutManager.scrollToPositionWithOffset(0, 0)
        }
        ivChapterBottom.setOnClickListener {
            if (adapter.itemCount > 0) {
                layoutManager.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
            }
        }
        tvCurrentChapterInfo.setOnClickListener {
            scrollToChapterIndex(durChapterIndex, expandVolume = true)
        }
        llChapterBaseInfo.applyNavigationBarPadding()
    }

    @SuppressLint("SetTextI18n")
    private fun initBook(book: Book) {
        chapterListJob?.cancel()
        cacheFileJob?.cancel()
        durChapterIndex = book.durChapterIndex
        binding.tvCurrentChapterInfo.text =
            "${book.durChapterTitle}(${book.durChapterIndex + 1}/${book.simulatedTotalChapterNum()})"
        adapter.cacheFileNames.clear()
        adapter.audioCacheKeys.clear()
        audioCacheStateReady = !book.isAudio
        pendingAudioCacheChanges.clear()
        tocListState.clear()
        chapterList = emptyList()
        adapter.setItems(emptyList())
        currentSearchKey = null
        pendingScrollItemKey = null
        pendingChapterScroll = null
        chapterListJob = viewLifecycleOwner.lifecycleScope.launch {
            val chapters = queryChapterList(book)
            chapterList = chapters
            tocListState.setFullChapters(
                chapters = chapters,
                reverseOrder = book.getReverseToc(),
                resetCollapse = true,
            )
            adapter.setItems(tocListState.showNormal(durChapterIndex))
        }
        cacheFileJob = viewLifecycleOwner.lifecycleScope.launch {
            if (book.isAudio) {
                var treeUri = AppConfig.audioCacheTreeUri
                var cachedKeys: Set<AudioCacheKey>
                while (true) {
                    cachedKeys = withContext(IO) {
                        runCatching {
                            AudioCacheManager.listCachedChapterKeys(
                                treeUri,
                                book.bookUrl,
                            )
                        }.getOrDefault(emptySet())
                    }
                    if (viewModel.bookData.value?.bookUrl != book.bookUrl) return@launch
                    val currentTreeUri = AppConfig.audioCacheTreeUri
                    if (treeUri == currentTreeUri) break
                    pendingAudioCacheChanges.clear()
                    treeUri = currentTreeUri
                }
                adapter.audioCacheKeys.addAll(cachedKeys)
                pendingAudioCacheChanges.forEach { (key, cached) ->
                    if (cached) adapter.audioCacheKeys.add(key)
                    else adapter.audioCacheKeys.remove(key)
                }
                pendingAudioCacheChanges.clear()
                audioCacheStateReady = true
            } else {
                adapter.cacheFileNames.addAll(withContext(IO) { BookHelp.getChapterFiles(book) })
            }
            adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.bookData.value?.bookUrl?.let { bookUrl ->
                if (viewModel.bookData.value?.isAudio != true && book.bookUrl == bookUrl) {
                    adapter.cacheFileNames.add(chapter.getFileName())
                    notifyVisibleChapterChanged(chapter.index)
                }
            }
        }
        observeEvent<AudioCacheStateChanged>(EventBus.AUDIO_CACHE_CHANGED) { event ->
            val currentBook = viewModel.bookData.value ?: return@observeEvent
            if (!currentBook.isAudio || currentBook.bookUrl != event.bookUrl) return@observeEvent
            if (event.treeUri != AppConfig.audioCacheTreeUri) return@observeEvent
            if (!audioCacheStateReady) {
                pendingAudioCacheChanges[event.key] = event.cached
            } else {
                if (event.cached) adapter.audioCacheKeys.add(event.key)
                else adapter.audioCacheKeys.remove(event.key)
                val position = adapter.findVisiblePositionByAudioCacheKey(event.key)
                if (position >= 0) adapter.notifyItemChanged(position, true)
            }
        }
    }

    override fun upChapterList(
        searchKey: String?,
        resetCollapse: Boolean,
        replaceAll: Boolean,
    ) {
        chapterListJob?.cancel()
        chapterListJob = viewLifecycleOwner.lifecycleScope.launch {
            val normalizedSearchKey = searchKey?.takeIf { it.isNotBlank() }
            currentSearchKey = normalizedSearchKey
            pendingScrollItemKey = null
            pendingChapterScroll = null
            val currentBook = book ?: return@launch
            val reverseOrder = currentBook.getReverseToc()
            if (normalizedSearchKey == null) {
                if (resetCollapse || !tocListState.hasFullChapters()) {
                    val chapters = queryChapterList(currentBook)
                    chapterList = chapters
                    tocListState.setFullChapters(
                        chapters = chapters,
                        reverseOrder = reverseOrder,
                        resetCollapse = resetCollapse,
                    )
                }
                submitChapterItems(
                    tocListState.showNormal(durChapterIndex),
                    replaceAll,
                )
            } else {
                delay(150)
                if (resetCollapse || !tocListState.hasFullChapters()) {
                    val chapters = queryChapterList(currentBook)
                    chapterList = chapters
                    tocListState.setFullChapters(
                        chapters = chapters,
                        reverseOrder = reverseOrder,
                        resetCollapse = resetCollapse,
                    )
                }
                submitChapterItems(
                    tocListState.showSearch(
                        searchResultIndexes = queryChapterIndexes(
                            currentBook,
                            normalizedSearchKey,
                        ),
                        currentChapterIndex = durChapterIndex,
                    ),
                    replaceAll,
                )
            }
        }
    }

    private fun submitChapterItems(items: List<TocListItem>, replaceAll: Boolean) {
        if (replaceAll) {
            adapter.setItemsNoDiff(items)
        } else {
            adapter.setItems(items)
        }
    }

    private suspend fun queryChapterList(book: Book): List<BookChapter> {
        return withContext(IO) {
            val end = book.simulatedTotalChapterNum() - 1
            appDb.bookChapterDao.getChapterList(book.bookUrl, 0, end)
        }
    }

    private suspend fun queryChapterIndexes(book: Book, searchKey: String): List<Int> {
        return withContext(IO) {
            val end = book.simulatedTotalChapterNum() - 1
            appDb.bookChapterDao.searchIndexes(book.bookUrl, searchKey, 0, end)
        }
    }

    private fun notifyVisibleChapterChanged(chapterIndex: Int) {
        val position = adapter.findVisiblePositionByChapterIndex(chapterIndex)
        if (position >= 0) {
            adapter.notifyItemChanged(position, true)
        }
    }

    private fun scrollToChapterIndex(chapterIndex: Int, expandVolume: Boolean) {
        if (expandVolume && currentSearchKey == null &&
            tocListState.expandVolumeContainingChapter(chapterIndex)
        ) {
            pendingChapterScroll = chapterIndex
            adapter.setItems(tocListState.showNormal(durChapterIndex))
            return
        }
        scrollToResolvedChapterPosition(chapterIndex)
    }

    private fun scrollToResolvedChapterPosition(chapterIndex: Int) {
        binding.recyclerView.post {
            val position = tocListState.findFallbackVisiblePositionForChapterIndex(chapterIndex)
            if (position >= 0) {
                layoutManager.scrollToPositionWithOffset(position, 0)
                adapter.upDisplayTitles(position)
            }
        }
    }

    override fun onListChanged() {
        if (pendingScrollItemKey != null || pendingChapterScroll != null) return
        viewLifecycleOwner.lifecycleScope.launch(Main) {
            val scrollPosition = if (currentSearchKey == null) {
                tocListState.findFallbackVisiblePositionForChapterIndex(durChapterIndex)
                    .coerceAtLeast(0)
            } else {
                0
            }
            layoutManager.scrollToPositionWithOffset(scrollPosition, 0)
            adapter.upDisplayTitles(scrollPosition)
        }
    }

    override fun onVolumeToggled(volumeIndex: Int) {
        if (currentSearchKey != null) return
        val firstVisibleItem = adapter.getItem(layoutManager.findFirstVisibleItemPosition())
        pendingScrollItemKey = if (
            firstVisibleItem is TocListItem.Chapter &&
            firstVisibleItem.parentVolumeIndex == volumeIndex &&
            !tocListState.isVolumeCollapsed(volumeIndex)
        ) {
            "volume:$volumeIndex"
        } else {
            firstVisibleItem?.key
        }
        if (tocListState.toggleVolume(volumeIndex)) {
            adapter.setItems(tocListState.showNormal(durChapterIndex))
        } else {
            pendingScrollItemKey = null
        }
    }

    override fun onItemsUpdated() {
        pendingChapterScroll?.let { chapterIndex ->
            pendingChapterScroll = null
            scrollToResolvedChapterPosition(chapterIndex)
            return
        }
        val anchorKey = pendingScrollItemKey ?: return
        pendingScrollItemKey = null
        binding.recyclerView.post {
            val position = adapter.findVisiblePositionByItemKey(anchorKey)
            if (position >= 0) {
                layoutManager.scrollToPositionWithOffset(position, 0)
                adapter.upDisplayTitles(position)
            } else {
                adapter.upDisplayTitles(layoutManager.findFirstVisibleItemPosition())
            }
        }
    }

    override fun clearDisplayTitle() {
        adapter.clearDisplayTitle()
        adapter.upDisplayTitles(layoutManager.findFirstVisibleItemPosition())
    }

    override fun upAdapter() {
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    override val scope: CoroutineScope
        get() = viewLifecycleOwner.lifecycleScope

    override val book: Book?
        get() = viewModel.bookData.value

    override val isLocalBook: Boolean
        get() = viewModel.bookData.value?.isLocal == true

    override val isAudioBook: Boolean
        get() = viewModel.bookData.value?.isAudio == true

    override val isAudioCacheStateReady: Boolean
        get() = audioCacheStateReady

    override fun durChapterIndex(): Int {
        return durChapterIndex
    }

    override fun openChapter(bookChapter: BookChapter) {
        activity?.run {
            if (book?.isVideo == true) {
                val volumes = chapterList.filterTo(arrayListOf()) { it.isVolume }
                var chapterInVolumeIndex = 0
                var durVolumeIndex = 0
                if (volumes.isNotEmpty()) {
                    for ((index, volume) in volumes.reversed().withIndex()) {
                        val chapterIndex = bookChapter.index
                        if (volume.index < chapterIndex) {
                            chapterInVolumeIndex = chapterIndex - volume.index - 1
                            durVolumeIndex = volumes.size - index - 1
                            break
                        } else if (volume.index == chapterIndex) {
                            durVolumeIndex = volumes.size - index - 1
                            break
                        }
                    }
                } else {
                    chapterInVolumeIndex = bookChapter.index
                }
                setResult(
                    RESULT_OK,
                    Intent()
                        .putExtra("index", bookChapter.index)
                        .putExtra("chapterChanged", bookChapter.index != durChapterIndex)
                        .putExtra("durVolumeIndex", durVolumeIndex)
                        .putExtra("chapterInVolumeIndex", chapterInVolumeIndex),
                )
                finish()
                return@run
            }
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra("index", bookChapter.index)
                    .putExtra("chapterChanged", bookChapter.index != durChapterIndex),
            )
            finish()
        }
    }
}
