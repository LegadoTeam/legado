package io.legado.app.ui.widget.dialog

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookMemo
import io.legado.app.databinding.DialogBookMemoBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.setLayout
import io.legado.app.utils.showSoftInput
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class BookMemoDialog : BaseDialogFragment(R.layout.dialog_book_memo) {
    private val binding by viewBinding(DialogBookMemoBinding::bind)
    private val bookUrl get() = requireArguments().getString("bookUrl")!!
    private var memo: BookMemo? = null
    private var editing = false
    private var loaded = false
    private var saving = false
    private var renderJob: Job? = null
    private var restoredScroll = 0
    private var readerActivity: ReadBookActivity? = null
    private lateinit var markwon: Markwon

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.5f)
        dialog?.window?.apply {
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        readerActivity?.let { it.bottomDialog-- }
        readerActivity = null
        super.onDismiss(dialog)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        readerActivity = activity as? ReadBookActivity
        readerActivity?.let { it.bottomDialog++ }
        val textColor = ThemeStore.textColorPrimary(requireContext())
        binding.memoTitle.setTextColor(textColor)
        binding.memoContent.setTextColor(textColor)
        binding.memoEditor.setTextColor(textColor)
        binding.memoUpdated.setTextColor(textColor)
        markwon = Markwon.builder(requireContext())
            .usePlugin(GlideImagesPlugin.create(Glide.with(this)))
            .usePlugin(TablePlugin.create(HelpMarkwonTheme.tableTheme(requireContext())))
            .usePlugin(HelpMarkwonTheme.plugin(requireContext()))
            .build()
        editing = savedInstanceState?.getBoolean("editing") ?: false
        restoredScroll = savedInstanceState?.getInt("scroll") ?: 0
        binding.memoEditor.setText(savedInstanceState?.getString("draft").orEmpty())
        binding.memoClose.setOnClickListener {
            if (editing) {
                requireContext().alert(R.string.book_memo_discard) {
                    okButton { dismiss() }
                    cancelButton()
                }
            } else dismiss()
        }
        binding.memoEditSave.setOnClickListener {
            if (editing) save(binding.memoEditor.text.toString()) else {
                editing = true
                binding.memoEditor.setText(memo?.content.orEmpty())
                updateControls()
                binding.memoEditor.requestFocus()
                binding.memoEditor.showSoftInput()
            }
        }
        binding.memoClearCancel.setOnClickListener {
            if (editing) {
                editing = false
                binding.memoEditor.hideSoftInput()
                updateControls()
                renderMemo()
            } else {
                requireContext().alert(R.string.book_memo_clear_confirm) {
                    okButton { save("") }
                    cancelButton()
                }
            }
        }
        updateControls()
        viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookMemoDao.flow(bookUrl).collect {
                memo = it
                loaded = true
                updateControls()
                if (!editing) renderMemo()
            }
        }
    }

    private fun updateControls() = binding.run {
        isCancelable = !editing && !saving
        memoScroll.isVisible = !editing
        memoEditor.isVisible = editing
        memoEditor.isEnabled = !saving
        memoClose.isEnabled = !saving
        memoEditSave.isEnabled = loaded && !saving
        memoClearCancel.isEnabled = loaded && !saving && (editing || !memo?.content.isNullOrEmpty())
        memoEditSave.setText(if (editing) R.string.book_memo_save else R.string.edit)
        memoClearCancel.setText(if (editing) R.string.cancel else R.string.clear)
        memoUpdated.text = memo?.let {
            getString(R.string.book_memo_updated, DateFormat.getDateTimeInstance().format(Date(it.updatedAt)))
        }.orEmpty()
        memoUpdated.isVisible = memo != null
    }

    private fun renderMemo() {
        renderJob?.cancel()
        val content = memo?.content.orEmpty().ifEmpty { getString(R.string.book_memo_empty) }
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val rendered = withContext(IO) { markwon.toMarkdown(content) }
            markwon.setParsedMarkdown(binding.memoContent, rendered)
            if (restoredScroll > 0) {
                val scroll = restoredScroll
                restoredScroll = 0
                binding.memoScroll.post { binding.memoScroll.scrollTo(0, scroll) }
            }
        }
    }

    private fun save(content: String) {
        saving = true
        updateControls()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                memo = withContext(IO) {
                    appDb.bookMemoDao.save(bookUrl, content)
                    appDb.bookMemoDao.get(bookUrl)
                }
                editing = false
                binding.memoEditor.hideSoftInput()
                renderMemo()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                toastOnUi(getString(R.string.book_memo_save_failed, error.localizedMessage.orEmpty()))
            } finally {
                saving = false
                if (view != null) updateControls()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("editing", editing)
        outState.putString("draft", binding.memoEditor.text.toString())
        outState.putInt("scroll", binding.memoScroll.scrollY)
        super.onSaveInstanceState(outState)
    }
}
