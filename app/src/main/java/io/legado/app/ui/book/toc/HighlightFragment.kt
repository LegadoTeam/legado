package io.legado.app.ui.book.toc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookHighlight
import io.legado.app.databinding.FragmentBookmarkBinding
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.read.HighlightNoteDialog
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun resolveHighlightChapterIndex(
    highlight: BookHighlight,
    chapterIndexes: Map<String, Int>
): Int? {
    return if (highlight.chapterUrl.isBlank()) {
        highlight.chapterIndex
    } else {
        chapterIndexes[highlight.chapterUrl]
    }
}

internal fun highlightBodyPosition(highlight: BookHighlight): Int {
    return (highlight.chapterPos - highlight.layoutTitleLength.coerceAtLeast(0))
        .coerceAtLeast(0)
}

class HighlightFragment : VMBaseFragment<TocViewModel>(R.layout.fragment_bookmark),
    HighlightAdapter.Callback,
    TocViewModel.HighlightCallBack {

    override val viewModel by activityViewModels<TocViewModel>()
    private val binding by viewBinding(FragmentBookmarkBinding::bind)
    private var layoutManager: UpLinearLayoutManager? = null
    private var adapter: HighlightAdapter? = null
    private var highlightJob: Job? = null
    private var chapterIndexes = emptyMap<String, Int>()
    private var durChapterIndex = 0

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.highlightCallBack = this
        initRecyclerView()
        viewModel.bookData.observe(viewLifecycleOwner) {
            durChapterIndex = it.durChapterIndex
            upHighlight(viewModel.searchKey)
        }
    }

    override fun onDestroyView() {
        highlightJob?.cancel()
        highlightJob = null
        binding.recyclerView.adapter = null
        adapter = null
        layoutManager = null
        chapterIndexes = emptyMap()
        viewModel.highlightCallBack = clearCallbackIfOwned(
            viewModel.highlightCallBack,
            this
        )
        super.onDestroyView()
    }

    private fun initRecyclerView() {
        val layoutManager = UpLinearLayoutManager(requireContext())
        val adapter = HighlightAdapter(requireContext(), this)
        this.layoutManager = layoutManager
        this.adapter = adapter
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
    }

    override fun upHighlight(searchKey: String?) {
        highlightJob?.cancel()
        val book = viewModel.bookData.value ?: return
        if (!supportsHighlightPosition(book)) {
            chapterIndexes = emptyMap()
            adapter?.setItems(emptyList())
            return
        }
        highlightJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrBlank() ->
                    appDb.bookHighlightDao.flowByBook(book.bookUrl)

                else -> appDb.bookHighlightDao.flowSearch(book.bookUrl, searchKey)
            }.flowOn(IO).catch {
                AppLog.put("目录界面获取标注数据失败\n${it.localizedMessage}", it)
            }.collect { highlights ->
                chapterIndexes = withContext(IO) {
                    appDb.bookChapterDao.getChapterList(book.bookUrl)
                        .associate { it.url to it.index }
                }
                val orderedHighlights = highlights.sortedWith(
                    compareBy(
                        { resolveHighlightChapterIndex(it, chapterIndexes) ?: Int.MAX_VALUE },
                        ::highlightBodyPosition,
                        BookHighlight::time
                    )
                )
                adapter?.setItems(orderedHighlights)
                val scrollPosition = orderedHighlights
                    .indexOfLast {
                        resolveHighlightChapterIndex(it, chapterIndexes)
                            ?.let { chapterIndex -> chapterIndex < durChapterIndex } == true
                    }
                    .coerceAtLeast(0)
                layoutManager?.scrollToPositionWithOffset(scrollPosition, 0)
            }
        }
    }

    override fun onClick(highlight: BookHighlight) {
        val book = viewModel.bookData.value ?: return
        if (!supportsHighlightPosition(book)) return
        val chapterIndex = resolveHighlightChapterIndex(highlight, chapterIndexes) ?: return
        activity?.run {
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra("index", chapterIndex)
                putExtra("chapterPos", highlight.chapterPos)
                putExtra(
                    TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH,
                    highlight.layoutTitleLength
                )
                putExtra(
                    TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT,
                    highlight.bookText.takeIf {
                        highlight.chapterPosEnd - highlight.chapterPos == it.length
                    }.orEmpty()
                )
            })
            finish()
        }
    }

    override fun onLongClick(highlight: BookHighlight) {
        showDialogFragment(HighlightNoteDialog(highlight))
    }

    private fun supportsHighlightPosition(book: Book): Boolean {
        return !book.isAudio && !book.isVideo &&
            (book.isLocal || !book.isImage || !AppConfig.showMangaUi)
    }
}
