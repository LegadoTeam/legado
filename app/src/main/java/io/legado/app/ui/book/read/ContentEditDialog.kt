package io.legado.app.ui.book.read

import android.app.Application
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
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
import io.legado.app.utils.applyTint
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ContentDraftRequest(val generation: Long, val revision: Long)

internal class ContentDraftState {
    var text: String? = null
        private set
    private var revision = 0L
    private var requestGeneration = 0L

    val hasDraft: Boolean
        get() = text != null

    fun restore(text: String): Boolean {
        if (this.text != null) return false
        this.text = text
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
        return text
    }
}

/**
 * 内容编辑
 */
class ContentEditDialog : BaseDialogFragment(R.layout.dialog_content_edit) {

    companion object {
        private const val STATE_HAS_DRAFT = "hasDraft"
    }

    val binding by viewBinding(DialogContentEditBinding::bind)
    val viewModel by viewModels<ContentEditViewModel>()
    private var editTitleDialog: AlertDialog? = null

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val owner = viewLifecycleOwner
        val contentView = binding.contentView
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.title = viewModel.titleLiveData.value ?: ReadBook.curTextChapter?.title
        viewModel.titleLiveData.observe(owner) {
            binding.toolBar.title = it
        }
        initMenu()
        binding.toolBar.setOnClickListener {
            if (editTitleDialog != null) return@setOnClickListener
            owner.lifecycleScope.launch {
                val book = ReadBook.book ?: return@launch
                val chapterIndex = ReadBook.durChapterIndex
                val chapter = withContext(IO) {
                    appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
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
                    val lineIndex = layout.getLineForOffset(ReadBook.durChapterPos)
                    val lineHeight = layout.getLineTop(lineIndex)
                    scrollTo(0, lineHeight)
                }
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        val contentView = binding.contentView
        if (savedInstanceState?.getBoolean(STATE_HAS_DRAFT) == true) {
            viewModel.restoreDraft(contentView.text?.toString().orEmpty())
        }
        viewModel.draftText?.let { draft ->
            if (contentView.text?.toString() != draft) contentView.setText(draft)
        }
        contentView.doAfterTextChanged {
            viewModel.updateDraft(it?.toString().orEmpty())
        }
        viewModel.initContent()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_HAS_DRAFT, viewModel.hasDraft)
    }

    override fun onDestroyView() {
        editTitleDialog?.dismiss()
        editTitleDialog = null
        super.onDestroyView()
    }

    private fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.content_edit)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_save -> {
                    save()
                    dismiss()
                }
                R.id.menu_reset -> viewModel.initContent(true)
                R.id.menu_copy_all -> requireContext()
                    .sendToClip("${binding.toolBar.title}\n${binding.contentView.text}")
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun editTitle(chapter: BookChapter) {
        if (editTitleDialog != null) return
        val bookUrl = chapter.bookUrl
        val chapterIndex = chapter.index
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
                    withContext(Main) {
                        if (ReadBook.book?.bookUrl == bookUrl &&
                            ReadBook.durChapterIndex == chapterIndex
                        ) {
                            ReadBook.loadContent(chapterIndex, resetPageOffset = false)
                            chapter.getDisplayTitle()
                        } else {
                            null
                        }
                    }
                }.onSuccess { title ->
                    title?.let { viewModel.titleLiveData.value = it }
                }
            }
            onDismiss { dialog ->
                if (editTitleDialog === dialog) editTitleDialog = null
            }
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        save()
    }

    private fun save() {
        val content = binding.contentView.text?.toString() ?: return
        val book = ReadBook.book ?: return
        val chapterIndex = ReadBook.durChapterIndex
        Coroutine.async {
            val chapter = appDb.bookChapterDao
                .getChapter(book.bookUrl, chapterIndex)
                ?: return@async
            BookHelp.saveText(book, chapter, content)
            withContext(Main) {
                if (ReadBook.book?.bookUrl == book.bookUrl &&
                    ReadBook.durChapterIndex == chapterIndex
                ) {
                    ReadBook.loadContent(chapterIndex, resetPageOffset = false)
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
        var content: String? = null
        private var contentTask: Coroutine<String?>? = null
        private var pendingReset: ContentLoadRequest? = null

        private data class ContentLoadRequest(
            val draft: ContentDraftRequest,
            val reset: Boolean,
            val book: Book,
            val chapterIndex: Int,
            val bookSource: BookSource?,
        )

        fun restoreDraft(text: String) {
            if (draftState.restore(text)) contentLiveData.value = text
        }

        fun updateDraft(text: String) {
            if (draftState.update(text)) contentLiveData.value = text
        }

        fun initContent(reset: Boolean = false) {
            if (!reset && (draftState.hasDraft || contentTask?.isActive == true)) return
            val book = ReadBook.book ?: return
            val request = ContentLoadRequest(
                draft = draftState.newRequest(),
                reset = reset,
                book = book,
                chapterIndex = ReadBook.durChapterIndex,
                bookSource = ReadBook.bookSource,
            )
            if (contentTask?.isActive == true) {
                pendingReset = request
                return
            }
            startContent(request)
        }

        private fun startContent(request: ContentLoadRequest) {
            contentTask = execute {
                val chapter = appDb.bookChapterDao
                    .getChapter(request.book.bookUrl, request.chapterIndex)
                    ?: return@execute null
                if (request.reset) {
                    content = null
                    BookHelp.delContent(request.book, chapter)
                    if (!request.book.isLocal) request.bookSource?.let { bookSource ->
                        WebBook.getContentAwait(bookSource, request.book, chapter)
                    }
                }
                return@execute content ?: let {
                    val contentProcessor = ContentProcessor.get(
                        request.book.name,
                        request.book.origin
                    )
                    val content = BookHelp.getContent(request.book, chapter) ?: return@let null
                    contentProcessor.getContent(
                        request.book,
                        chapter,
                        content,
                        includeTitle = false
                    )
                        .toString()
                }
            }.onStart {
                loadStateLiveData.postValue(true)
            }.onSuccess {
                content = it
                if (ReadBook.book?.bookUrl == request.book.bookUrl &&
                    ReadBook.durChapterIndex == request.chapterIndex
                ) {
                    if (request.reset) {
                        ReadBook.loadContent(request.chapterIndex, resetPageOffset = false)
                    }
                    draftState.applyLoaded(request.draft, it.orEmpty())?.let { content ->
                        contentLiveData.value = content
                    }
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
