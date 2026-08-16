package io.legado.app.ui.widget.dialog

import android.os.Bundle
import android.text.method.KeyListener
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogCodeViewBinding
import io.legado.app.help.IntentData
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.utils.applyTint
import io.legado.app.utils.disableEdit
import io.legado.app.utils.gone
import io.legado.app.utils.setLayout
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding

class CodeDialog() : BaseDialogFragment(R.layout.dialog_code_view) {

    constructor(
        code: String,
        disableEdit: Boolean = true,
        requestId: String? = null,
        alternateCode: String? = null,
        showAlternate: Boolean = false,
    ) : this() {
        arguments = Bundle().apply {
            putBoolean("disableEdit", disableEdit)
            putString("code", IntentData.put(code))
            putString("requestId", requestId)
            alternateCode?.let { putString("alternateCode", IntentData.put(it)) }
            putBoolean("showAlternate", showAlternate)
        }
    }

    val binding by viewBinding(DialogCodeViewBinding::bind)
    private var editKeyListener: KeyListener? = null
    private var originalCode = ""
    private var alternateCode: String? = null
    private var showingAlternate = false

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        if (arguments?.getBoolean("disableEdit") == true) {
            binding.toolBar.title = "code view"
            binding.codeView.disableEdit()
        } else {
            initMenu()
        }
        binding.codeView.addLegadoPattern()
        binding.codeView.addJsonPattern()
        binding.codeView.addJsPattern()
        originalCode = arguments?.getString("code")
            ?.let { IntentData.get<String>(it) }
            .orEmpty()
        alternateCode = arguments?.getString("alternateCode")
            ?.let { IntentData.get<String>(it) }
        editKeyListener = binding.codeView.keyListener
        binding.codeView.setText(originalCode)
        if (arguments?.getBoolean("disableEdit") != true && alternateCode != null) {
            binding.cbSourceReplacementPreview.apply {
                visible()
                isChecked = arguments?.getBoolean("showAlternate") == true
                setOnCheckedChangeListener { _, checked -> showAlternate(checked) }
            }
            showAlternate(binding.cbSourceReplacementPreview.isChecked)
        } else {
            binding.cbSourceReplacementPreview.gone()
        }
    }

    private fun showAlternate(show: Boolean) {
        val alternate = alternateCode ?: return
        if (show) {
            if (!showingAlternate) {
                originalCode = binding.codeView.text?.toString().orEmpty()
            }
            binding.codeView.setText(alternate)
            binding.codeView.keyListener = null
        } else {
            binding.codeView.setText(originalCode)
            binding.codeView.keyListener = editKeyListener
        }
        showingAlternate = show
        binding.toolBar.menu.findItem(R.id.menu_save)?.isVisible = !show
    }

    private fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.code_edit)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_save -> {
                    binding.codeView.text?.toString()?.let { code ->
                        val requestId = arguments?.getString("requestId")
                        (parentFragment as? Callback)?.onCodeSave(code, requestId)
                            ?: (activity as? Callback)?.onCodeSave(code, requestId)
                    }
                    dismiss()
                }
            }
            return@setOnMenuItemClickListener true
        }
    }


    interface Callback {

        fun onCodeSave(code: String, requestId: String?)

    }

}
