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
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.FragmentChapterListBinding
import io.legado.app.help.audio.AudioCacheManager
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.AudioCacheKey
import io.legado.app.model.AudioCacheStateChanged
import io.legado.app.model.localBook.PdfFile
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.EpubTocNode
import io.legado.app.model.localBook.PdfOutline
import io.legado.app.model.localBook.PdfOutlineNode
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
import kotlinx.coroutines.CancellationException
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
    private var pdfOutlineState: PdfOutlineListState? = null
    private var pdfOutlineLoading = false
    private var epubToc: List<EpubTocNode>? = null
    private var epubTocLoading = false
    private val pdfOutlineAdapter by lazy {
        PdfOutlineAdapter(requireContext(), ::openPdfOutline) { id ->
            pdfOutlineState?.toggle(id)
            showPdfOutline()
        }
    }
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
        viewModel.bookData.observe(viewLifecycleOwner) {
            initBook(it)
        }
    }

    override fun onDestroyView() {
        chapterListJob?.cancel()
        cacheFileJob?.cancel()
        pdfOutlineLoading = false
        epubTocLoading = false
        pendingScrollItemKey = null
        pendingChapterScroll = null
        binding.recyclerView.adapter = null
        binding.recyclerView.layoutManager = null
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
            val count = binding.recyclerView.adapter?.itemCount ?: 0
            if (count > 0) {
                layoutManager.scrollToPositionWithOffset(count - 1, 0)
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
        pdfOutlineState = null
        pdfOutlineLoading = book.isPdf
        epubToc = null
        epubTocLoading = book.isEpub
        binding.recyclerView.adapter = adapter
        durChapterIndex = book.durChapterIndex
        binding.tvCurrentChapterInfo.text =
            "${book.durChapterTitle}(${book.durChapterIndex + 1}/${book.simulatedTotalChapterNum()})"
        adapter.cacheFileNames.clear()
        adapter.audioCacheKeys.clear()
        audioCacheStateReady = !book.isAudio
        pendingAudioCacheChanges.clear()
        tocListState.clear()
        chapterList = emptyList()
        adapter.clearDisplayTitle()
        adapter.setItems(emptyList())
        val normalizedSearchKey = viewModel.searchKey?.takeIf { it.isNotBlank() }
        currentSearchKey = normalizedSearchKey
        pendingScrollItemKey = null
        pendingChapterScroll = null
        chapterListJob = viewLifecycleOwner.lifecycleScope.launch {
            if (book.isEpub) {
                epubToc = try {
                    withContext(IO) { EpubFile.getToc(book) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.put("读取 EPUB 目录失败", e)
                    null
                }
                epubTocLoading = false
            }
            if (book.isPdf) {
                val outline = try {
                    withContext(IO) { PdfOutline.read(book) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.put("读取 PDF 目录失败", e)
                    emptyList()
                }
                pdfOutlineLoading = false
                if (outline.any { it.pageIndex != null }) {
                    pdfOutlineState = PdfOutlineListState(outline, book.getTocExpanded())
                    binding.recyclerView.adapter = pdfOutlineAdapter
                    showPdfOutline()
                    return@launch
                }
            }
            val chapters = queryChapterList(book)
            chapterList = chapters
            tocListState.setFullChapters(
                chapters = chapters,
                reverseOrder = book.getReverseToc(),
                reverseDisplay = !book.isPdf && !book.isEpub && book.getReverseTocDisplay(),
                resetCollapse = true,
                defaultExpanded = book.getTocExpanded(),
                currentChapterIndex = durChapterIndex,
                epubToc = epubToc,
            )
            val searchKey = currentSearchKey
            adapter.setItems(
                if (searchKey == null) {
                    tocListState.showNormal(durChapterIndex)
                } else {
                    tocListState.showSearch(
                        searchResultIndexes = queryChapterIndexes(book, searchKey),
                        currentChapterIndex = durChapterIndex,
                    )
                }
            )
        }
        cacheFileJob = viewLifecycleOwner.lifecycleScope.launch {
            if (book.isPdf) return@launch
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
        currentSearchKey = searchKey?.takeIf { it.isNotBlank() }
        // Keep parsing the document when the user searches before the outline is ready.
        if (pdfOutlineLoading || epubTocLoading) return
        pdfOutlineState?.let { state ->
            if (resetCollapse) state.setExpanded(book?.getTocExpanded() != false)
            showPdfOutline()
            return
        }
        chapterListJob?.cancel()
        if (replaceAll) adapter.clearDisplayTitle()
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
                        reverseDisplay = !currentBook.isPdf && !currentBook.isEpub && currentBook.getReverseTocDisplay(),
                        resetCollapse = resetCollapse,
                        defaultExpanded = currentBook.getTocExpanded(),
                        currentChapterIndex = durChapterIndex,
                        epubToc = epubToc,
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
                        reverseDisplay = !currentBook.isPdf && !currentBook.isEpub && currentBook.getReverseTocDisplay(),
                        resetCollapse = resetCollapse,
                        defaultExpanded = currentBook.getTocExpanded(),
                        currentChapterIndex = durChapterIndex,
                        epubToc = epubToc,
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
            appDb.bookChapterDao.getChapterList(book.bookUrl, 0, end).let { chapters ->
                if (book.isPdf && book.getReverseToc()) chapters.asReversed() else chapters
            }
        }
    }

    private suspend fun queryChapterIndexes(book: Book, searchKey: String): List<Int> {
        if (!epubToc.isNullOrEmpty()) return tocListState.searchIndexes(searchKey)
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
        pdfOutlineState?.let { state ->
            if (expandVolume) state.setExpanded(true)
            showPdfOutline()
            val rows = state.items(currentSearchKey, book?.getReverseToc() == true)
            val closest = rows.withIndex().filter { it.value.node.pageIndex != null }
                .minByOrNull { kotlin.math.abs(it.value.node.pageIndex!! - chapterIndex * PdfFile.PAGE_SIZE) }
            binding.recyclerView.post {
                layoutManager.scrollToPositionWithOffset(closest?.index ?: 0, 0)
            }
            return
        }
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
        if (pdfOutlineState != null) return
        if (pendingScrollItemKey != null || pendingChapterScroll != null) return
        viewLifecycleOwner.lifecycleScope.launch(Main) {
            if (pdfOutlineState != null) return@launch
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
            firstVisibleItem != null &&
            tocListState.isDescendantOf(firstVisibleItem.chapter.index, volumeIndex) &&
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
        if (pdfOutlineState != null) return
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
        if (pdfOutlineState != null) showPdfOutline()
        else adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    private fun showPdfOutline() {
        pdfOutlineState?.let { state ->
            pdfOutlineAdapter.setItems(state.items(currentSearchKey, book?.getReverseToc() == true))
        }
    }

    private fun openPdfOutline(node: PdfOutlineNode) {
        val page = node.pageIndex ?: return
        val currentBook = book ?: return
        // The outline is navigation only; retain the persisted ten-page reading segments.
        val index = page / PdfFile.PAGE_SIZE
        if (index !in 0 until currentBook.totalChapterNum) return
        activity?.run {
            setResult(RESULT_OK, Intent()
                .putExtra("index", index)
                .putExtra(TocActivityResult.EXTRA_PDF_PAGE_INDEX, page)
                .putExtra("chapterChanged", index != durChapterIndex))
            finish()
        }
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
