package io.legado.app.ui.about

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadRecordAuthors
import io.legado.app.databinding.DialogMultipleEditTextBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadRecordEditDialog : DialogFragment() {
    private val model by viewModels<ReadRecordEditModel>()
    private lateinit var binding: DialogMultipleEditTextBinding
    private val oldName get() = requireArguments().getString("bookName").orEmpty()
    private val oldAuthor get() = requireArguments().getString("author").orEmpty()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogMultipleEditTextBinding.inflate(layoutInflater).apply {
            layout1.hint = getString(R.string.book_name)
            layout2.hint = getString(R.string.author)
            layout2.isVisible = true
            edit1.setText(savedInstanceState?.getString("name") ?: oldName)
            edit2.setText(savedInstanceState?.getString("author") ?: ReadRecordAuthors.display(oldAuthor))
        }
        model.saved.observe(this) {
            if (it) {
                (activity as? ReadRecordActivity)?.onRecordMetadataSaved()
                dismiss()
            }
        }
        model.error.observe(this) { binding.layout1.error = it }
        return AlertDialog.Builder(requireContext()).setTitle(R.string.read_record_edit)
            .setMessage(R.string.read_record_edit_summary).setView(binding.root)
            .setPositiveButton(R.string.action_save, null).setNegativeButton(R.string.cancel, null).create()
    }

    override fun onStart() {
        super.onStart()
        val button = (requireDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE)
        model.saving.observe(this) {
            button.isEnabled = !it
            (requireDialog() as AlertDialog).getButton(DialogInterface.BUTTON_NEGATIVE).isEnabled = !it
            isCancelable = !it
        }
        button.setOnClickListener {
            val name = binding.edit1.text?.toString().orEmpty().trim()
            val author = binding.edit2.text?.toString().orEmpty().trim()
            if (name.isEmpty()) {
                binding.layout1.error = getString(R.string.no_book_name)
            } else {
                model.save(oldName, oldAuthor, name,
                    if (author == ReadRecordAuthors.display(oldAuthor)) oldAuthor else author)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("name", binding.edit1.text?.toString())
        outState.putString("author", binding.edit2.text?.toString())
        super.onSaveInstanceState(outState)
    }
}

class ReadRecordEditModel : ViewModel() {
    val saving = MutableLiveData(false)
    val saved = MutableLiveData(false)
    val error = MutableLiveData<String?>()

    fun save(oldName: String, oldAuthor: String, name: String, author: String) {
        if (saving.value == true) return
        saving.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { appDb.readRecordDao.renameBook(oldName, oldAuthor, name, author) }
                saved.value = true
            } catch (e: Exception) {
                error.value = e.localizedMessage ?: e.toString()
                saving.value = false
            }
        }
    }
}
