package io.legado.app.ui.association

import android.app.Dialog
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import io.legado.app.R
import io.legado.app.utils.FileDoc

/** Confirmation for shared book lists and backups, with the existing import/restore operations. */
class ImportDataDialog() : DialogFragment() {
    constructor(type: String, source: String) : this() {
        arguments = Bundle().apply {
            putString("type", type)
            putString("source", source)
        }
    }

    private val viewModel by activityViewModels<FileAssociationViewModel>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val backup = requireArguments().getString("type") == "backup"
        val uri = Uri.parse(requireArguments().getString("source"))
        val name = FileDoc.fromUri(uri, false).name
        return AlertDialog.Builder(requireContext())
            .setTitle(if (backup) R.string.restore_confirmation else R.string.import_bookshelf)
            .setMessage(if (backup) "$name\n${getString(R.string.restore_message)}" else name)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        val alert = requireDialog() as AlertDialog
        alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            viewModel.importData(
                requireArguments().getString("type")!!,
                requireArguments().getString("source")!!,
            )
        }
        viewModel.importingData.observe(this) { importing ->
            isCancelable = !importing
            alert.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !importing
            alert.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = !importing
            if (importing) alert.setMessage(getString(R.string.importing))
        }
        viewModel.importedData.observe(this) { imported ->
            if (imported) dismissAllowingStateLoss()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (activity?.isChangingConfigurations != true) activity?.finish()
    }
}
