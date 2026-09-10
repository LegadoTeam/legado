package io.legado.app.ui.widget.dialog

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.text.method.KeyListener
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.widget.doAfterTextChanged
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogCodeViewBinding
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.findTextRanges
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.code.CodeTextTransfer
import io.legado.app.ui.widget.code.EditSafety
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.utils.applyTint
import io.legado.app.utils.disableEdit
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding

internal fun resolveCodeDialogOriginal(
    showingAlternate: Boolean,
    originalCode: String,
    displayedCode: String,
): String = if (showingAlternate) originalCode else displayedCode

internal fun resolveCodeDialogPositionProgress(
    scrollY: Int,
    maxScrollY: Int,
    progressMax: Int = 10000,
): Int {
    if (maxScrollY <= 0 || progressMax <= 0) return 0
    return (scrollY.coerceIn(0, maxScrollY).toLong() * progressMax / maxScrollY).toInt()
}

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
    private var editorTextPath: String? = null
    private var editorPending = false
    private var editorReadOnly = false
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { updatePositionBar() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { updatePositionBar() }
    val requestId: String?
        get() = arguments?.getString("requestId")
    private val sourcePreview: Boolean
        get() = arguments?.getBoolean("showReplaceRules") == true

    private val editorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val context = requireContext().applicationContext
        val outputPath = result.data?.getStringExtra("textFile")
        try {
            if (result.resultCode != RESULT_OK || editorReadOnly) return@registerForActivityResult
            val code = result.data?.getStringExtra("text") ?: outputPath?.let {
                CodeTextTransfer.read(context, it)
                    ?: error(getString(R.string.code_editor_result_error))
            }
            if (code != null) {
                originalCode = code
                if (!showingAlternate) binding.codeView.setText(code)
                if (sourcePreview) {
                    clearAlternateCode()
                    callback()?.onCodeSave(code, requestId)
                }
            }
            if (!showingAlternate) {
                result.data?.takeIf { it.hasExtra("cursorPosition") }
                    ?.getIntExtra("cursorPosition", 0)?.let {
                        binding.codeView.setSelection(it.coerceIn(0, binding.codeView.length()))
                    }
            }
        } catch (error: Exception) {
            toastOnUi(error.localizedMessage)
        } finally {
            CodeTextTransfer.delete(context, editorTextPath)
            CodeTextTransfer.delete(context, outputPath)
            editorTextPath = null
            editorPending = false
            updateEditorAction()
        }
    }

    override fun onStart() {
        super.onStart()
        originalCodeStateKey?.let { IntentData.get<Any>(it) }
        alternateCodeStateKey?.let { IntentData.get<Any>(it) }
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        editorTextPath = savedInstanceState?.getString("editorTextPath")
        editorPending = savedInstanceState?.getBoolean("editorPending") == true
        editorReadOnly = savedInstanceState?.getBoolean("editorReadOnly") == true
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
        binding.codeView.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        binding.codeView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        binding.positionBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val max = maxScrollY()
                    binding.codeView.scrollTo(
                        0,
                        (max * progress.toLong() / bar.max).toInt(),
                    )
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
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
        binding.codeView.post { updatePositionBar() }
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
        updateEditorAction()
        binding.toolBar.menu.findItem(R.id.menu_save)?.isVisible =
            saveEnabled && (!show || sourcePreview) && searchView.isIconified
        if (!searchView.isIconified) showCurrentMatch()
        binding.codeView.post { updatePositionBar() }
    }

    private fun initMenu(canSave: Boolean) {
        saveEnabled = canSave
        binding.toolBar.inflateMenu(R.menu.code_edit)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.menu.findItem(R.id.menu_replace_rule).isVisible =
            arguments?.getBoolean("showReplaceRules") == true
        binding.toolBar.menu.findItem(R.id.menu_effective_replaces).isVisible = sourcePreview
        binding.toolBar.menu.findItem(R.id.menu_manual_replace_rule).isVisible = sourcePreview && AppConfig.manualSourceReplaceRule
        binding.toolBar.menu.findItem(R.id.menu_fullscreen_edit).isVisible = canSave
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
            if (editorPending) return@setOnMenuItemClickListener true
            when (it.itemId) {
                R.id.menu_search_previous -> moveToMatch(searchIndex - 1)
                R.id.menu_search_next -> moveToMatch(searchIndex + 1)
                R.id.menu_replace_rule -> if (!replaceRuleRefreshPending) {
                    callback()?.onOpenReplaceRules()
                }
                R.id.menu_effective_replaces -> if (!replaceRuleRefreshPending) {
                    callback()?.onShowSourceReplacements(currentOriginalCode(), requestId, false)
                }
                R.id.menu_manual_replace_rule -> if (!replaceRuleRefreshPending) {
                    callback()?.onShowSourceReplacements(currentOriginalCode(), requestId, true)
                }
                R.id.menu_fullscreen_edit -> openEditor()
                R.id.menu_save -> if (!replaceRuleRefreshPending) {
                    callback()?.onCodeSave(currentOriginalCode(), requestId)
                    dismiss()
                }
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun openEditor() {
        if (!saveEnabled || replaceRuleRefreshPending || editorPending) return
        val context = requireContext().applicationContext
        editorReadOnly = showingAlternate && !sourcePreview
        val code = if (editorReadOnly) binding.codeView.text.toString() else currentOriginalCode()
        val cursor = if (showingAlternate && sourcePreview) 0 else binding.codeView.selectionStart.coerceAtLeast(0)
        editorPending = true
        updateEditorAction()
        try {
            val path = if (EditSafety.isTooLongForInline(code)) CodeTextTransfer.write(context, code) else null
            editorTextPath = path
            editorLauncher.launch(Intent(context, CodeEditActivity::class.java).apply {
                putExtra("useTextFile", true)
                if (path != null) putExtra("textFile", path) else putExtra("text", code)
                putExtra("readOnly", editorReadOnly)
                putExtra("cursorPosition", cursor)
            })
        } catch (error: Exception) {
            CodeTextTransfer.delete(context, editorTextPath)
            editorTextPath = null
            editorPending = false
            updateEditorAction()
            toastOnUi(error.localizedMessage)
        }
    }

    private fun updateEditorAction() {
        val pending = replaceRuleRefreshPending || editorPending
        binding.toolBar.menu.findItem(R.id.menu_fullscreen_edit).isEnabled = !pending
        binding.toolBar.menu.findItem(R.id.menu_replace_rule).isEnabled = !pending
        binding.toolBar.menu.findItem(R.id.menu_save).isEnabled = !pending
        binding.toolBar.menu.findItem(R.id.menu_effective_replaces).isEnabled = !pending
        binding.toolBar.menu.findItem(R.id.menu_manual_replace_rule).apply {
            isEnabled = !pending
            isVisible = sourcePreview && AppConfig.manualSourceReplaceRule
        }
        binding.cbSourceReplacementPreview.isEnabled = !pending
        binding.codeView.keyListener = if (pending || showingAlternate) null else editKeyListener
        isCancelable = !pending
    }

    private fun setSearchOpen(open: Boolean) {
        binding.toolBar.menu.findItem(R.id.menu_search_previous).isVisible = open
        binding.toolBar.menu.findItem(R.id.menu_search_next).isVisible = open
        binding.toolBar.menu.findItem(R.id.menu_fullscreen_edit).isVisible = saveEnabled && !open
        binding.toolBar.menu.findItem(R.id.menu_save).isVisible =
            saveEnabled && (!showingAlternate || sourcePreview) && !open
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

    private fun maxScrollY(): Int {
        val codeView = binding.codeView
        return ((codeView.layout?.height ?: 0) + codeView.totalPaddingTop +
            codeView.totalPaddingBottom - codeView.height).coerceAtLeast(0)
    }

    private fun updatePositionBar() {
        val max = maxScrollY()
        binding.positionBar.isEnabled = max > 0
        binding.positionBar.progress = resolveCodeDialogPositionProgress(
            binding.codeView.scrollY,
            max,
            binding.positionBar.max,
        )
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
        binding.cbSourceReplacementPreview.apply {
            if (alternateCode == null) {
                if (showingAlternate) showAlternate(false)
                gone()
            } else {
                visible()
                if (isChecked) showAlternate(true)
            }
        }
        updateEditorAction()
    }

    fun setReplaceRuleRefreshPending(pending: Boolean) {
        replaceRuleRefreshPending = pending
        if (view == null) return
        updateEditorAction()
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

    override fun onDestroyView() {
        binding.codeView.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        binding.codeView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("editorTextPath", editorTextPath)
        outState.putBoolean("editorPending", editorPending)
        outState.putBoolean("editorReadOnly", editorReadOnly)
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

        fun onShowSourceReplacements(code: String, requestId: String?, manual: Boolean) = Unit

        fun getCodeAlternate(requestId: String?): String? = null

        fun isReplaceRuleRefreshPending(): Boolean = false

    }

}
