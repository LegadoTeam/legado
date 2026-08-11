package io.legado.app.ui.book.audio.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.Book
import io.legado.app.databinding.DialogAudioSkipCreditsBinding
import io.legado.app.help.book.savePreservingCustomCoverUrl
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.lang.ref.WeakReference

class AudioSkipCredits : BaseDialogFragment(R.layout.dialog_audio_skip_credits) {
    private val binding by viewBinding(DialogAudioSkipCreditsBinding::bind)

    companion object {
        private var bookRef: WeakReference<Book>? = null

        fun newInstance(book: Book): AudioSkipCredits {
            return AudioSkipCredits().apply {
                bookRef = WeakReference(book)
            }
        }
    }

    private val book: Book by lazy {
        bookRef?.get() ?: throw IllegalStateException("Book reference lost")
    }
 
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initData()
        initView()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun initData() {
        binding.run {
            rgScope.check(if (book.isAudioSkipUsingGlobal()) R.id.rb_global else R.id.rb_book)
            updateValues()
        }
    }

    private fun initView() {
        binding.run {
            rgScope.setOnCheckedChangeListener { _, checkedId ->
                book.setAudioSkipUsingGlobal(checkedId == R.id.rb_global)
                updateValues()
            }
            openCredits.onChanged = {
                if (rbGlobal.isChecked) AppConfig.audioSkipOpenCredits = it
                else book.setOpenCredits(it)
            }
            closeCredits.onChanged = {
                if (rbGlobal.isChecked) AppConfig.audioSkipCloseCredits = it
                else book.setCloseCredits(it)
            }
        }
    }

    private fun updateValues() = binding.run {
        openCredits.progress = book.getOpenCredits()
        closeCredits.progress = book.getCloseCredits()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        book.savePreservingCustomCoverUrl()
    }
}
