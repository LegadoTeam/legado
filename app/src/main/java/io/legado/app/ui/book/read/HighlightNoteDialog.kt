package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.BookHighlight
import io.legado.app.databinding.DialogHighlightNoteBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

class HighlightNoteDialog() : BaseDialogFragment(R.layout.dialog_highlight_note, true) {

    constructor(highlight: BookHighlight) : this() {
        arguments = Bundle().apply {
            putParcelable(ARG_HIGHLIGHT, highlight)
        }
    }

    private val binding by viewBinding(DialogHighlightNoteBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        @Suppress("DEPRECATION")
        val highlight = arguments?.getParcelable<BookHighlight>(ARG_HIGHLIGHT) ?: run {
            dismiss()
            return
        }
        binding.run {
            tvChapterName.text = highlight.chapterName
            editBookText.setText(highlight.bookText)
            editNote.setText(highlight.note)
            btnCancel.setOnClickListener { dismiss() }
            btnOk.setOnClickListener {
                highlight.bookText = editBookText.text?.toString().orEmpty()
                highlight.note = editNote.text?.toString().orEmpty()
                ReadBook.updateHighlight(highlight)
                dismiss()
            }
            btnDelete.setOnClickListener {
                ReadBook.removeHighlight(highlight)
                dismiss()
            }
        }
    }

    companion object {
        private const val ARG_HIGHLIGHT = "highlight"
    }
}
