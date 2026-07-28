package io.legado.app.ui.code

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogCurlAnalyzeUrlBinding
import io.legado.app.help.IntentData
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.code.CurlAnalyzeUrlConverter.ConversionException
import io.legado.app.ui.code.CurlAnalyzeUrlConverter.ErrorReason
import io.legado.app.utils.applyTint
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class CurlAnalyzeUrlDialog() : BaseDialogFragment(R.layout.dialog_curl_analyze_url) {

    companion object {
        private const val ARG_INPUT = "input"
        private const val ARG_CAN_INSERT = "canInsert"
    }

    constructor(input: String, canInsert: Boolean) : this() {
        arguments = Bundle().apply {
            putString(ARG_INPUT, IntentData.put(input))
            putBoolean(ARG_CAN_INSERT, canInsert)
        }
    }

    private val binding by viewBinding(DialogCurlAnalyzeUrlBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.setTitle(R.string.curl_analyze_url_converter)
        binding.editOutput.keyListener = null
        binding.editOutput.setTextIsSelectable(true)
        initMenu()

        if (savedInstanceState == null) {
            val input = IntentData.get<String>(arguments?.getString(ARG_INPUT)).orEmpty()
            binding.editInput.setText(input)
            binding.editInput.setSelection(input.length)
            if (input.isNotBlank() && !CurlAnalyzeUrlConverter.looksLikeCurl(input)) {
                binding.rbAnalyzeToCurl.isChecked = true
            }
        }
        updateDirection(clearOutput = false)
        setOutput(binding.editOutput.text?.toString().orEmpty())
        binding.rgDirection.setOnCheckedChangeListener { _, _ ->
            updateDirection(clearOutput = true)
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        updateDirection(clearOutput = false)
        setOutput(binding.editOutput.text?.toString().orEmpty())
    }

    private fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.curl_analyze_url)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.menu.findItem(R.id.menu_copy).isEnabled = false
        binding.toolBar.menu.findItem(R.id.menu_insert).apply {
            isVisible = arguments?.getBoolean(ARG_CAN_INSERT) == true
            isEnabled = false
        }
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_convert -> convert()
                R.id.menu_copy -> copyOutput()
                R.id.menu_insert -> insertOutput()
            }
            true
        }
    }

    private fun updateDirection(clearOutput: Boolean) {
        val curlToAnalyze = binding.rbCurlToAnalyze.isChecked
        binding.inputLayout.setHint(
            if (curlToAnalyze) R.string.curl_input_hint else R.string.analyze_url_input_hint
        )
        binding.outputLayout.setHint(
            if (curlToAnalyze) R.string.analyze_url_output_hint else R.string.curl_output_hint
        )
        if (clearOutput) setOutput("")
    }

    private fun convert() {
        val input = binding.editInput.text?.toString().orEmpty()
        try {
            val output = if (binding.rbCurlToAnalyze.isChecked) {
                CurlAnalyzeUrlConverter.curlToAnalyzeUrl(input)
            } else {
                CurlAnalyzeUrlConverter.analyzeUrlToCurl(input)
            }
            setOutput(output)
        } catch (error: ConversionException) {
            setOutput("")
            toastOnUi(errorMessage(error))
        } catch (_: Exception) {
            setOutput("")
            toastOnUi(R.string.curl_converter_failed)
        }
    }

    private fun setOutput(value: String) {
        binding.editOutput.setText(value)
        val hasOutput = value.isNotEmpty()
        binding.toolBar.menu.findItem(R.id.menu_copy).isEnabled = hasOutput
        binding.toolBar.menu.findItem(R.id.menu_insert).isEnabled = hasOutput
    }

    private fun copyOutput() {
        val output = binding.editOutput.text?.toString().orEmpty()
        if (output.isEmpty()) {
            toastOnUi(R.string.curl_converter_no_output)
        } else {
            requireContext().sendToClip(output)
        }
    }

    private fun insertOutput() {
        val output = binding.editOutput.text?.toString().orEmpty()
        if (output.isEmpty()) {
            toastOnUi(R.string.curl_converter_no_output)
            return
        }
        val callback = parentFragment as? Callback ?: activity as? Callback
        if (callback == null) {
            toastOnUi(R.string.curl_converter_insert_failed)
            return
        }
        val insertItem = binding.toolBar.menu.findItem(R.id.menu_insert)
        insertItem.isEnabled = false
        callback.onCurlAnalyzeUrlInsert(output) { success ->
            if (!isAdded) return@onCurlAnalyzeUrlInsert
            if (success) {
                dismiss()
            } else {
                insertItem.isEnabled = true
                toastOnUi(R.string.curl_converter_insert_failed)
            }
        }
    }

    private fun errorMessage(error: ConversionException): String {
        return when (error.reason) {
            ErrorReason.EMPTY_INPUT -> getString(R.string.curl_converter_empty_input)
            ErrorReason.INVALID_CURL -> getString(R.string.curl_converter_invalid_curl)
            ErrorReason.MISSING_URL -> getString(R.string.curl_converter_missing_url)
            ErrorReason.INVALID_ANALYZE_URL ->
                getString(R.string.curl_converter_invalid_analyze_url)
            ErrorReason.UNSUPPORTED_METHOD ->
                getString(R.string.curl_converter_unsupported_method, error.detail)
            ErrorReason.UNSUPPORTED_OPTION ->
                getString(R.string.curl_converter_unsupported_option, error.detail)
        }
    }

    interface Callback {
        fun onCurlAnalyzeUrlInsert(text: String, onResult: (Boolean) -> Unit)
    }
}
