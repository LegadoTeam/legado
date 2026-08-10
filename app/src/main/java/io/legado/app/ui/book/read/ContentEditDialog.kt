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
import androidx.lifecycle.withStateAtLeast
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
import io.legado.app.utils.applyTint
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PendingContent(
    private val content: String,
    private val revision: Long,
) {
    private var pending = true

    fun take(currentRevision: Long): String? {
        if (!pending) return null
        pending = false
        if (revision != currentRevision) return null
        return content
    }
}

internal class ContentDraftState {
    var text: String? = null
        private set
    private var revision = 0L

    fun initialize(text: String) {
        if (this.text == null) this.text = text
    }

    fun update(text: String) {
        if (this.text == text) return
        this.text = text
        revision++
    }

    fun snapshot(): Long = revision

    fun applyLoaded(snapshot: Long, text: String): String? {
        if (snapshot != revision) return null
        this.text = text
        return text
    }
}

/**
 * 内容编辑
 */
class ContentEditDialog : BaseDialogFragment(R.layout.dialog_content_edit) {

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
        viewModel.draftText?.let(contentView::setText)
        viewModel.initializeDraft(contentView.text?.toString().orEmpty())
        contentView.doAfterTextChanged {
            viewModel.updateDraft(it?.toString().orEmpty())
        }
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
                val chapter = withContext(IO) {
                    appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
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
        viewModel.contentLiveData.observe(owner) { event ->
            owner.lifecycleScope.launch {
                owner.lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
                    val content = event.take(viewModel.draftRevision)
                        ?: return@withStateAtLeast
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
        }
        if (savedInstanceState == null) viewModel.initContent()
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
                        }
                    }
                    chapter.getDisplayTitle()
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
        save()
    }

    private fun save() {
        val content = binding.contentView.text?.toString() ?: return
        Coroutine.async {
            val book = ReadBook.book ?: return@async
            val chapter = appDb.bookChapterDao
                .getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?: return@async
            BookHelp.saveText(book, chapter, content)
            ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
        }
    }

    class ContentEditViewModel(application: Application) : BaseViewModel(application) {
        val loadStateLiveData = MutableLiveData<Boolean>()
        internal val contentLiveData = MutableLiveData<PendingContent>()
        internal val titleLiveData = MutableLiveData<String>()
        private val draftState = ContentDraftState()
        internal val draftText: String?
            get() = draftState.text
        internal val draftRevision: Long
            get() = draftState.snapshot()
        var content: String? = null
        private var contentTask: Coroutine<String?>? = null
        private var contentTaskIsReset = false
        private var resetPending = false

        fun initializeDraft(text: String) = draftState.initialize(text)

        fun updateDraft(text: String) = draftState.update(text)

        fun initContent(reset: Boolean = false) {
            if (!reset && contentLiveData.value != null) return
            if (contentTask?.isActive == true) {
                if (reset && !contentTaskIsReset) resetPending = true
                return
            }
            val draftRevision = draftState.snapshot()
            contentTaskIsReset = reset
            contentTask = execute {
                val book = ReadBook.book ?: return@execute null
                val chapter = appDb.bookChapterDao
                    .getChapter(book.bookUrl, ReadBook.durChapterIndex)
                    ?: return@execute null
                if (reset) {
                    content = null
                    BookHelp.delContent(book, chapter)
                    if (!book.isLocal) ReadBook.bookSource?.let { bookSource ->
                        WebBook.getContentAwait(bookSource, book, chapter)
                    }
                }
                return@execute content ?: let {
                    val contentProcessor = ContentProcessor.get(book.name, book.origin)
                    val content = BookHelp.getContent(book, chapter) ?: return@let null
                    contentProcessor.getContent(book, chapter, content, includeTitle = false)
                        .toString()
                }
            }.onStart {
                loadStateLiveData.postValue(true)
            }.onSuccess {
                content = it
                if (reset) {
                    ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
                }
                draftState.applyLoaded(draftRevision, it.orEmpty())?.let { content ->
                    contentLiveData.value = PendingContent(content, draftState.snapshot())
                }
            }.onFinally {
                contentTask = null
                contentTaskIsReset = false
                loadStateLiveData.postValue(false)
                if (resetPending) {
                    resetPending = false
                    initContent(reset = true)
                }
            }
        }

    }

}
