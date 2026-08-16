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

internal fun resolveCodeDialogOriginal(
    showingAlternate: Boolean,
    originalCode: String,
    displayedCode: String,
): String = if (showingAlternate) originalCode else displayedCode

class CodeDialog() : BaseDialogFragment(R.layout.dialog_code_view) {

    constructor(
        code: String,
        disableEdit: Boolean = true,
        requestId: String? = null,
        alternateCode: String? = null,
        showAlternate: Boolean = false,
        showReplaceRules: Boolean = false,
    ) : this() {
        arguments = Bundle().apply {
            putBoolean("disableEdit", disableEdit)
            putString("code", IntentData.put(code))
            putString("requestId", requestId)
            alternateCode?.let { putString("alternateCode", IntentData.put(it)) }
            putBoolean("showAlternate", showAlternate)
            putBoolean("showReplaceRules", showReplaceRules)
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
    private var replaceRuleRefreshPending = false
    private var originalCodeStateKey: String? = null
    private var alternateCodeStateKey: String? = null
    val requestId: String?
        get() = arguments?.getString("requestId")

    override fun onStart() {
        super.onStart()
        originalCodeStateKey?.let { IntentData.get<Any>(it) }
        alternateCodeStateKey?.let { IntentData.get<Any>(it) }
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
        originalCodeStateKey = savedInstanceState?.getString("originalCode")
            ?: arguments?.getString("code")
        originalCode = originalCodeStateKey
            ?.let { IntentData.get<String>(it) }
            .orEmpty()
        alternateCodeStateKey = savedInstanceState?.getString("alternateCode")
            ?: arguments?.getString("alternateCode")
        alternateCode = alternateCodeStateKey
            ?.let { IntentData.get<String>(it) }
        if (arguments?.getBoolean("showReplaceRules") == true) {
            callback()?.let { alternateCode = it.getCodeAlternate(requestId) }
        }
        editKeyListener = binding.codeView.keyListener
        binding.codeView.setText(originalCode)
        val canPreviewReplacement = !disableEdit && alternateCode != null
        binding.cbSourceReplacementPreview.apply {
            isChecked = savedInstanceState?.getBoolean("showingAlternate")
                ?: (arguments?.getBoolean("showAlternate") == true)
            if (canPreviewReplacement) {
                visible()
            } else {
                gone()
            }
            setOnCheckedChangeListener { _, checked -> showAlternate(checked) }
        }
        if (canPreviewReplacement) {
            showAlternate(binding.cbSourceReplacementPreview.isChecked)
        }
        setReplaceRuleRefreshPending(callback()?.isReplaceRuleRefreshPending() == true)
        binding.codeView.doAfterTextChanged {
            if (!searchView.isIconified) {
                updateSearch(keepIndex = true, selectMatch = false)
            }
        }
    }

    private fun showAlternate(show: Boolean) {
        if (show) {
            val alternate = alternateCode ?: return
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
        binding.toolBar.menu.findItem(R.id.menu_replace_rule).isVisible =
            arguments?.getBoolean("showReplaceRules") == true
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
                R.id.menu_replace_rule -> if (!replaceRuleRefreshPending) {
                    callback()?.onOpenReplaceRules()
                }
                R.id.menu_save -> if (!replaceRuleRefreshPending) {
                    binding.codeView.text?.toString()?.let { code ->
                        callback()?.onCodeSave(code, requestId)
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

    fun refreshAlternateCode() {
        alternateCode = callback()?.getCodeAlternate(requestId)
        updateAlternatePreview()
    }

    fun clearAlternateCode() {
        alternateCode = null
        updateAlternatePreview()
    }

    private fun updateAlternatePreview() {
        if (view == null) return
        binding.toolBar.menu.findItem(R.id.menu_replace_rule).isEnabled =
            !replaceRuleRefreshPending
        binding.cbSourceReplacementPreview.apply {
            if (alternateCode == null) {
                if (showingAlternate) showAlternate(false)
                gone()
            } else {
                visible()
                if (isChecked) showAlternate(true)
            }
        }
    }

    fun setReplaceRuleRefreshPending(pending: Boolean) {
        replaceRuleRefreshPending = pending
        if (view == null) return
        binding.toolBar.menu.findItem(R.id.menu_replace_rule).isEnabled = !pending
        binding.toolBar.menu.findItem(R.id.menu_save).isEnabled = !pending
        binding.cbSourceReplacementPreview.isEnabled = !pending
        binding.codeView.keyListener = if (pending || showingAlternate) null else editKeyListener
        isCancelable = !pending
    }

    fun currentOriginalCode(): String = if (view == null) {
        originalCode
    } else {
        resolveCodeDialogOriginal(
            showingAlternate,
            originalCode,
            binding.codeView.text?.toString().orEmpty(),
        )
    }

    private fun callback(): Callback? =
        (parentFragment as? Callback) ?: (activity as? Callback)

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        setSearchOpen(!searchView.isIconified)
        searchIndex = savedInstanceState?.getInt("searchIndex", -1) ?: -1
        if (!searchView.isIconified) updateSearch(keepIndex = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("searchIndex", searchIndex)
        originalCodeStateKey = saveStateData(originalCodeStateKey, currentOriginalCode())
            .also { outState.putString("originalCode", it) }
        alternateCode?.let { code ->
            alternateCodeStateKey = saveStateData(alternateCodeStateKey, code)
                .also { outState.putString("alternateCode", it) }
        }
        outState.putBoolean("showingAlternate", showingAlternate)
        super.onSaveInstanceState(outState)
    }

    private fun saveStateData(key: String?, data: String): String =
        key?.also { IntentData.put(it, data) } ?: IntentData.put(data)


    interface Callback {

        fun onCodeSave(code: String, requestId: String?)

        fun onOpenReplaceRules() = Unit

        fun getCodeAlternate(requestId: String?): String? = null

        fun isReplaceRuleRefreshPending(): Boolean = false

    }

}
