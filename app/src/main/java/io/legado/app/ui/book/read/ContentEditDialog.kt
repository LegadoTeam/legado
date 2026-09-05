package io.legado.app.ui.book.read

import android.app.Application
import android.content.DialogInterface
import android.os.Bundle
import android.text.NoCopySpan
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.DialogContentEditBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyTint
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.showSoftInput
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.PatternSyntaxException

internal data class ContentDraftRequest(val generation: Long, val revision: Long)

internal data class ContentEditTarget(
    val bookUrl: String,
    val chapterIndex: Int,
    val chapterPos: Int,
) {
    fun matches(bookUrl: String?, chapterIndex: Int): Boolean {
        return this.bookUrl == bookUrl && this.chapterIndex == chapterIndex
    }
}

internal class ContentDraftState {
    var text: String? = null
        private set
    private var baseline: String? = null
    private var restoredChanges = false
    private var revision = 0L
    private var requestGeneration = 0L

    val hasDraft: Boolean
        get() = text != null

    val hasChanges: Boolean
        get() = restoredChanges || (text != null && text != baseline)

    fun restore(text: String, hasChanges: Boolean = false): Boolean {
        if (this.text != null) return false
        this.text = text
        baseline = text
        restoredChanges = hasChanges
        return true
    }

    fun update(text: String): Boolean {
        if (this.text == text) return false
        this.text = text
        revision++
        return true
    }

    fun newRequest(): ContentDraftRequest {
        return ContentDraftRequest(++requestGeneration, revision)
    }

    fun applyLoaded(request: ContentDraftRequest, text: String): String? {
        if (request.generation != requestGeneration || request.revision != revision) return null
        if (this.text != text) revision++
        this.text = text
        baseline = text
        restoredChanges = false
        return text
    }
}

/**
 * 内容编辑
 */
class ContentEditDialog : BaseDialogFragment(R.layout.dialog_content_edit) {

    companion object {
        private const val ARG_BOOK_URL = "bookUrl"
        private const val ARG_CHAPTER_INDEX = "chapterIndex"
        private const val ARG_CHAPTER_POS = "chapterPos"
        private const val ARG_TITLE = "title"
        private const val STATE_HAS_DRAFT = "hasDraft"
        private const val STATE_HAS_CHANGES = "hasChanges"
        private const val STATE_SEARCH_QUERY = "contentSearchQuery"
        private const val STATE_SEARCH_VISIBLE = "contentSearchVisible"
        private const val STATE_SEARCH_REGEX = "contentSearchRegex"
        private const val STATE_SEARCH_CASE = "contentSearchCase"
        private const val STATE_SEARCH_ANCHOR = "contentSearchAnchor"
        private const val STATE_SCROLL_Y = "contentScrollY"

        fun newInstance(): ContentEditDialog? {
            val book = ReadBook.book ?: return null
            val chapterIndex = ReadBook.durChapterIndex
            val title = ReadBook.curTextChapter
                ?.takeIf {
                    it.chapter.bookUrl == book.bookUrl && it.chapter.index == chapterIndex
                }
                ?.title
                ?: book.durChapterTitle?.takeIf { book.durChapterIndex == chapterIndex }
            return ContentEditDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_BOOK_URL, book.bookUrl)
                    putInt(ARG_CHAPTER_INDEX, chapterIndex)
                    putInt(ARG_CHAPTER_POS, ReadBook.durChapterPos)
                    putString(ARG_TITLE, title)
                }
            }
        }
    }

    val binding by viewBinding(DialogContentEditBinding::bind)
    val viewModel by viewModels<ContentEditViewModel>()
    private var editTitleDialog: AlertDialog? = null
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var searchMatches = emptyList<IntRange>()
    private var searchIndex = -1
    private var searchSpan: BackgroundColorSpan? = null
    private var restoredScrollY: Int? = null
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { updatePositionBar() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { updatePositionBar() }
    private val editTarget by lazy(LazyThreadSafetyMode.NONE) {
        ContentEditTarget(
            bookUrl = arguments?.getString(ARG_BOOK_URL).orEmpty(),
            chapterIndex = arguments?.getInt(ARG_CHAPTER_INDEX) ?: 0,
            chapterPos = arguments?.getInt(ARG_CHAPTER_POS) ?: 0,
        )
    }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val owner = viewLifecycleOwner
        val contentView = binding.contentView
        restoredScrollY = savedInstanceState?.getInt(STATE_SCROLL_Y)
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.title = viewModel.titleLiveData.value
            ?: arguments?.getString(ARG_TITLE)
        viewModel.titleLiveData.observe(owner) {
            binding.toolBar.title = it
        }
        initMenu()
        contentView.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        contentView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        binding.positionBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) contentView.scrollTo(0, (maxScrollY() * progress.toLong() / bar.max).toInt())
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        binding.toolBar.setOnClickListener {
            if (editTitleDialog != null) return@setOnClickListener
            owner.lifecycleScope.launch {
                val chapter = withContext(IO) {
                    appDb.bookChapterDao.getChapter(
                        editTarget.bookUrl,
                        editTarget.chapterIndex,
                    )
                } ?: return@launch
                editTitle(chapter)
            }
        }
        viewModel.loadStateLiveData.observe(owner) {
            if (it) {
                binding.rlLoading.visible()
            } else {
                binding.rlLoading.gone()
            }
        }
        viewModel.contentLiveData.observe(owner) { content ->
            if (contentView.text?.toString() == content) return@observe
            contentView.setText(content)
            contentView.post {
                if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                    return@post
                }
                contentView.apply {
                    val textLayout = layout ?: return@post
                    val lineIndex = textLayout.getLineForOffset(editTarget.chapterPos.coerceIn(0, length()))
                    val target = restoredScrollY ?: textLayout.getLineTop(lineIndex)
                    restoredScrollY = null
                    scrollTo(0, target.coerceIn(0, maxScrollY()))
                }
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        val contentView = binding.contentView
        val owner = viewLifecycleOwner
        if (savedInstanceState?.getBoolean(STATE_HAS_DRAFT) == true) {
            viewModel.restoreDraft(
                contentView.text?.toString().orEmpty(),
                savedInstanceState.getBoolean(STATE_HAS_CHANGES),
            )
        }
        viewModel.draftText?.let { draft ->
            if (contentView.text?.toString() != draft) contentView.setText(draft)
        }
        contentView.doAfterTextChanged {
            viewModel.updateDraft(it?.toString().orEmpty())
            scheduleSearch(scrollToMatch = false)
            contentView.post {
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) updatePositionBar()
            }
        }
        setupSearch(savedInstanceState)
        viewModel.initContent(editTarget)
        contentView.doOnLayout {
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED) && viewModel.hasDraft) {
                restoredScrollY?.let {
                    contentView.scrollTo(0, it.coerceIn(0, maxScrollY()))
                    restoredScrollY = null
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_HAS_DRAFT, viewModel.hasDraft)
        outState.putBoolean(STATE_HAS_CHANGES, viewModel.hasChanges)
        if (view == null) return
        outState.putString(STATE_SEARCH_QUERY, binding.searchInput.text?.toString())
        outState.putBoolean(STATE_SEARCH_VISIBLE, binding.searchBar.isVisible)
        outState.putBoolean(STATE_SEARCH_REGEX, binding.searchRegex.isChecked)
        outState.putBoolean(STATE_SEARCH_CASE, binding.searchMatchCase.isChecked)
        outState.putInt(STATE_SEARCH_ANCHOR, searchMatches.getOrNull(searchIndex)?.first ?: 0)
        outState.putInt(STATE_SCROLL_Y, binding.contentView.scrollY)
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        searchJob = null
        searchGeneration++
        clearSearchHighlight()
        binding.contentView.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        binding.contentView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        editTitleDialog?.dismiss()
        editTitleDialog = null
        super.onDestroyView()
    }

    private fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.content_edit)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_search -> toggleSearch()
                R.id.menu_save -> {
                    save()
                    dismiss()
                }
                R.id.menu_reset -> viewModel.initContent(editTarget, true)
                R.id.menu_copy_all -> requireContext()
                    .sendToClip("${binding.toolBar.title}\n${binding.contentView.text}")
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun setupSearch(savedState: Bundle?) {
        binding.searchInput.setText(savedState?.getString(STATE_SEARCH_QUERY).orEmpty())
        binding.searchRegex.isChecked = savedState?.getBoolean(STATE_SEARCH_REGEX) == true
        binding.searchMatchCase.isChecked = savedState?.getBoolean(STATE_SEARCH_CASE) == true
        binding.searchBar.isVisible = savedState?.getBoolean(STATE_SEARCH_VISIBLE) == true
        binding.searchInput.doAfterTextChanged { scheduleSearch(scrollToMatch = true) }
        binding.searchRegex.setOnCheckedChangeListener { _, _ -> scheduleSearch(scrollToMatch = true) }
        binding.searchMatchCase.setOnCheckedChangeListener { _, _ -> scheduleSearch(scrollToMatch = true) }
        binding.btnSearchPrev.setOnClickListener { moveToMatch(-1) }
        binding.btnSearchNext.setOnClickListener { moveToMatch(1) }
        binding.btnCloseSearch.setOnClickListener { toggleSearch() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                moveToMatch(1)
                true
            } else false
        }
        scheduleSearch(scrollToMatch = false, anchor = savedState?.getInt(STATE_SEARCH_ANCHOR) ?: 0)
    }

    private fun toggleSearch() {
        binding.searchBar.isVisible = !binding.searchBar.isVisible
        if (binding.searchBar.isVisible) {
            binding.searchInput.requestFocus()
            binding.searchInput.showSoftInput()
        } else {
            binding.searchInput.hideSoftInput()
            binding.contentView.requestFocus()
        }
        scheduleSearch(scrollToMatch = true)
    }

    private fun scheduleSearch(
        scrollToMatch: Boolean,
        anchor: Int = binding.contentView.selectionStart.coerceAtLeast(0),
    ) {
        searchJob?.cancel()
        val generation = ++searchGeneration
        clearSearchHighlight()
        searchMatches = emptyList()
        searchIndex = -1
        binding.btnSearchPrev.isEnabled = false
        binding.btnSearchNext.isEnabled = false
        binding.searchInput.error = null
        binding.searchCount.text = "0/0"
        val query = binding.searchInput.text?.toString().orEmpty()
        if (!binding.searchBar.isVisible || query.isEmpty()) return
        val text = binding.contentView.text?.toString().orEmpty()
        val regex = binding.searchRegex.isChecked
        val matchCase = binding.searchMatchCase.isChecked
        val owner = viewLifecycleOwner
        binding.searchCount.text = getString(R.string.loading)
        searchJob = owner.lifecycleScope.launch {
            delay(120)
            val matches = try {
                withContext(Default) { findContentMatches(text, query, regex, matchCase) { ensureActive() } }
            } catch (error: PatternSyntaxException) {
                if (generation == searchGeneration) {
                    binding.searchInput.error = getString(R.string.content_search_invalid_regex)
                    binding.searchCount.text = "0/0"
                }
                return@launch
            }
            if (generation != searchGeneration) return@launch
            searchMatches = matches
            searchIndex = matches.indexOfFirst { it.first >= anchor }.let {
                if (it >= 0) it else if (matches.isEmpty()) -1 else 0
            }
            showCurrentMatch(scrollToMatch)
        }
    }

    private fun moveToMatch(direction: Int) {
        if (searchMatches.isEmpty()) return
        searchIndex = cycleContentMatchIndex(searchIndex, direction, searchMatches.size)
        showCurrentMatch(scrollToMatch = true)
    }

    private fun showCurrentMatch(scrollToMatch: Boolean) {
        clearSearchHighlight()
        val range = searchMatches.getOrNull(searchIndex)
        binding.btnSearchPrev.isEnabled = range != null
        binding.btnSearchNext.isEnabled = range != null
        binding.searchCount.text = "${searchIndex + 1}/${searchMatches.size}"
        if (range == null) return
        val contentView = binding.contentView
        val editable = contentView.text ?: return
        if (!range.isEmpty()) {
            searchSpan = object : BackgroundColorSpan(ColorUtils.adjustAlpha(primaryColor, 0.35f)), NoCopySpan {}.also {
                editable.setSpan(it, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (!scrollToMatch) return
        contentView.setSelection(range.first, range.last + 1)
        val generation = searchGeneration
        contentView.post {
            if (!contentView.isAttachedToWindow || generation != searchGeneration ||
                searchMatches.getOrNull(searchIndex) != range
            ) return@post
            val layout = contentView.layout ?: return@post
            val lineTop = layout.getLineTop(layout.getLineForOffset(range.first))
            contentView.scrollTo(0, (lineTop - contentView.height / 3).coerceIn(0, maxScrollY()))
        }
    }

    private fun clearSearchHighlight() {
        searchSpan?.let { binding.contentView.text?.removeSpan(it) }
        searchSpan = null
    }

    private fun maxScrollY(): Int {
        val content = binding.contentView
        return ((content.layout?.height ?: 0) + content.totalPaddingTop +
            content.totalPaddingBottom - content.height).coerceAtLeast(0)
    }

    private fun updatePositionBar() {
        val max = maxScrollY()
        binding.positionBar.isEnabled = max > 0
        binding.positionBar.progress = if (max == 0) 0 else
            (binding.contentView.scrollY.coerceIn(0, max) * 10000L / max).toInt()
    }

    private fun editTitle(chapter: BookChapter) {
        if (editTitleDialog != null) return
        editTitleDialog = alert {
            setTitle(R.string.edit)
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater)
            alertBinding.editView.setText(chapter.title)
            setCustomView(alertBinding.root)
            okButton {
                val title = alertBinding.editView.text.toString()
                chapter.title = title
                Coroutine.async {
                    chapter.update()
                    val displayTitle = chapter.getDisplayTitle()
                    withContext(Main) {
                        if (editTarget.matches(
                                ReadBook.book?.bookUrl,
                                ReadBook.durChapterIndex,
                            )
                        ) {
                            ReadBook.loadContent(
                                editTarget.chapterIndex,
                                resetPageOffset = false,
                            )
                        }
                    }
                    displayTitle
                }.onSuccess { title ->
                    viewModel.titleLiveData.value = title
                }
            }
            onDismiss { dialog ->
                if (editTitleDialog === dialog) editTitleDialog = null
            }
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        if (viewModel.hasChanges) save()
    }

    private fun save() {
        val content = binding.contentView.text?.toString() ?: return
        Coroutine.async {
            val book = ReadBook.book?.takeIf { it.bookUrl == editTarget.bookUrl }
                ?: appDb.bookDao.getBook(editTarget.bookUrl)
                ?: return@async
            val chapter = appDb.bookChapterDao
                .getChapter(editTarget.bookUrl, editTarget.chapterIndex)
                ?: return@async
            BookHelp.saveText(book, chapter, content)
            withContext(Main) {
                if (editTarget.matches(
                        ReadBook.book?.bookUrl,
                        ReadBook.durChapterIndex,
                    )
                ) {
                    ReadBook.loadContent(editTarget.chapterIndex, resetPageOffset = false)
                }
            }
        }
    }

    class ContentEditViewModel(application: Application) : BaseViewModel(application) {
        val loadStateLiveData = MutableLiveData<Boolean>()
        internal val contentLiveData = MutableLiveData<String>()
        internal val titleLiveData = MutableLiveData<String>()
        private val draftState = ContentDraftState()
        internal val draftText: String?
            get() = draftState.text
        internal val hasDraft: Boolean
            get() = draftState.hasDraft
        internal val hasChanges: Boolean
            get() = draftState.hasChanges
        private var contentTask: Coroutine<String?>? = null
        private var pendingReset: ContentLoadRequest? = null

        private data class ContentLoadRequest(
            val draft: ContentDraftRequest,
            val reset: Boolean,
            val target: ContentEditTarget,
        )

        fun restoreDraft(text: String, hasChanges: Boolean) {
            if (draftState.restore(text, hasChanges)) contentLiveData.value = text
        }

        fun updateDraft(text: String) {
            if (draftState.update(text)) contentLiveData.value = text
        }

        internal fun initContent(target: ContentEditTarget, reset: Boolean = false) {
            if (!reset && (draftState.hasDraft || contentTask?.isActive == true)) return
            val request = ContentLoadRequest(
                draft = draftState.newRequest(),
                reset = reset,
                target = target,
            )
            if (contentTask?.isActive == true) {
                pendingReset = request
                return
            }
            startContent(request)
        }

        private fun startContent(request: ContentLoadRequest) {
            contentTask = execute {
                val book = ReadBook.book?.takeIf {
                    it.bookUrl == request.target.bookUrl
                } ?: appDb.bookDao.getBook(request.target.bookUrl)
                    ?: return@execute null
                val chapter = appDb.bookChapterDao
                    .getChapter(request.target.bookUrl, request.target.chapterIndex)
                    ?: return@execute null
                if (request.reset) {
                    BookHelp.delContent(book, chapter)
                    if (!book.isLocal) {
                        val bookSource = ReadBook.bookSource?.takeIf {
                            it.bookSourceUrl == book.origin
                        } ?: appDb.bookSourceDao.getBookSource(book.origin)
                        bookSource?.let {
                            WebBook.getContentAwait(it, book, chapter)
                        }
                    }
                }
                val contentProcessor = ContentProcessor.get(book.name, book.origin)
                val content = BookHelp.getContent(book, chapter) ?: return@execute null
                contentProcessor.getContent(
                    book,
                    chapter,
                    content,
                    includeTitle = false,
                ).toString()
            }.onStart {
                loadStateLiveData.postValue(true)
            }.onSuccess {
                if (request.reset && request.target.matches(
                        ReadBook.book?.bookUrl,
                        ReadBook.durChapterIndex,
                    )
                ) {
                    ReadBook.loadContent(request.target.chapterIndex, resetPageOffset = false)
                }
                draftState.applyLoaded(request.draft, it.orEmpty())?.let { content ->
                    contentLiveData.value = content
                }
            }.onFinally {
                contentTask = null
                val next = pendingReset
                pendingReset = null
                if (next == null) {
                    loadStateLiveData.postValue(false)
                } else {
                    startContent(next)
                }
            }
        }

    }

}
