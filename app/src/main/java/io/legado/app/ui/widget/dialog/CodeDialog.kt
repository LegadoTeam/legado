package io.legado.app.ui.widget.dialog

import android.os.Bundle
import android.text.method.KeyListener
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.core.widget.doAfterTextChanged
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogCodeViewBinding
import io.legado.app.help.IntentData
import io.legado.app.help.findTextRanges
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.utils.applyTint
import io.legado.app.utils.disableEdit
import io.legado.app.utils.dpToPx
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
    private var saveEnabled = false
    private lateinit var searchView: SearchView
    private var searchRanges: List<IntRange> = emptyList()
    private var searchIndex = -1

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        val disableEdit = arguments?.getBoolean("disableEdit") == true
        if (disableEdit) {
            binding.toolBar.title = "code view"
            binding.codeView.disableEdit()
        }
        initMenu(!disableEdit)
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
        binding.codeView.doAfterTextChanged {
            if (!searchView.isIconified) {
                updateSearch(keepIndex = true, selectMatch = false)
            }
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
        binding.toolBar.menu.findItem(R.id.menu_save)?.isVisible =
            saveEnabled && !show && searchView.isIconified
        if (!searchView.isIconified) showCurrentMatch()
    }

    private fun initMenu(canSave: Boolean) {
        saveEnabled = canSave
        binding.toolBar.inflateMenu(R.menu.code_edit)
        binding.toolBar.menu.applyTint(requireContext())
        searchView = binding.toolBar.menu.findItem(R.id.menu_search).actionView as SearchView
        val navigationWidth = 96.dpToPx()
        val minimumWidth = 48.dpToPx()
        binding.toolBar.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val availableWidth = (right - left - navigationWidth -
                    binding.toolBar.contentInsetStart - binding.toolBar.contentInsetEnd -
                    binding.toolBar.paddingStart - binding.toolBar.paddingEnd)
                .coerceAtLeast(minimumWidth)
            if (searchView.maxWidth != availableWidth) searchView.maxWidth = availableWidth
        }
        searchView.apply {
            maxWidth = (resources.displayMetrics.widthPixels - navigationWidth -
                    binding.toolBar.contentInsetStart - binding.toolBar.contentInsetEnd -
                    binding.toolBar.paddingStart - binding.toolBar.paddingEnd)
                .coerceAtLeast(minimumWidth)
            queryHint = getString(R.string.search)
            setOnSearchClickListener {
                setSearchOpen(true)
                updateSearch(keepIndex = true)
            }
            setOnCloseListener {
                setSearchOpen(false)
                false
            }
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    moveToMatch(searchIndex + 1)
                    return true
                }

                override fun onQueryTextChange(newText: String): Boolean {
                    updateSearch()
                    return true
                }
            })
        }
        setSearchOpen(false)
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_search_previous -> moveToMatch(searchIndex - 1)
                R.id.menu_search_next -> moveToMatch(searchIndex + 1)
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

    private fun setSearchOpen(open: Boolean) {
        binding.toolBar.menu.findItem(R.id.menu_search_previous).isVisible = open
        binding.toolBar.menu.findItem(R.id.menu_search_next).isVisible = open
        binding.toolBar.menu.findItem(R.id.menu_save).isVisible =
            saveEnabled && !showingAlternate && !open
        if (!open) setSearchActionsEnabled(false)
    }

    private fun updateSearch(
        keepIndex: Boolean = false,
        selectMatch: Boolean = true,
    ) {
        searchRanges = findTextRanges(
            binding.codeView.text?.toString().orEmpty(),
            searchView.query?.toString().orEmpty(),
        )
        if (searchRanges.isEmpty()) {
            searchIndex = -1
            setSearchActionsEnabled(false)
            return
        }
        searchIndex = if (keepIndex) {
            searchIndex.coerceIn(0, searchRanges.lastIndex)
        } else {
            0
        }
        setSearchActionsEnabled(true)
        if (selectMatch) showCurrentMatch()
    }

    private fun moveToMatch(index: Int) {
        if (searchRanges.isEmpty()) return
        searchIndex = ((index % searchRanges.size) + searchRanges.size) % searchRanges.size
        showCurrentMatch()
    }

    private fun showCurrentMatch() {
        val range = searchRanges.getOrNull(searchIndex) ?: return
        val codeView = binding.codeView
        codeView.setSelection(range.first, range.last + 1)
        codeView.post {
            if (!codeView.isAttachedToWindow ||
                searchRanges.getOrNull(searchIndex) != range
            ) return@post
            codeView.bringPointIntoView(range.first)
        }
    }

    private fun setSearchActionsEnabled(enabled: Boolean) {
        binding.toolBar.menu.findItem(R.id.menu_search_previous).isEnabled = enabled
        binding.toolBar.menu.findItem(R.id.menu_search_next).isEnabled = enabled
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        setSearchOpen(!searchView.isIconified)
        searchIndex = savedInstanceState?.getInt("searchIndex", -1) ?: -1
        if (!searchView.isIconified) updateSearch(keepIndex = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("searchIndex", searchIndex)
        super.onSaveInstanceState(outState)
    }


    interface Callback {

        fun onCodeSave(code: String, requestId: String?)

    }

}
