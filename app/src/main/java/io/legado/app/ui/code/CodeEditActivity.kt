package io.legado.app.ui.code

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ActivityCodeEditBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.code.config.ChangeThemeDialog
import io.legado.app.ui.code.config.SettingsDialog
import io.legado.app.ui.widget.code.EditSafety
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.imeHeight
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class CodeEditActivity :
    VMBaseActivity<ActivityCodeEditBinding, CodeEditViewModel>(),
    KeyboardToolPop.CallBack, ChangeThemeDialog.CallBack, SettingsDialog.CallBack {
    companion object {
        const val EXTRA_SHOW_DEBUG_SOURCE = "showDebugSourceAction"
        const val EXTRA_RESULT_ACTION = "resultAction"
        const val RESULT_ACTION_DEBUG_SOURCE = "debugSource"

        private var isInitialized = false
        private var findText = ""
        private var replaceText = ""
        private var isRegex = true
        private const val SAFE_EDITOR_LOAD_TIMEOUT_MILLIS = 15_000L
        private const val SAFE_EDITOR_READ_TIMEOUT_MILLIS = 5_000L
    }
    override val binding by viewBinding(ActivityCodeEditBinding::inflate)
    override val viewModel by viewModels<CodeEditViewModel>()
    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }
    private val editor: CodeEditor by lazy { binding.editText }
    private val editorSearcher: EditorSearcher by lazy { editor.searcher }
    private var searchOptions: SearchOptions? = null
    private var menuSaveBtn: MenuItem? = null
    private var menuDebugSourceBtn: MenuItem? = null
    private var useSafeEditor = false
    private var safeEditor: WebView? = null
    private var safeEditorStatus = SafeEditorStatus.IDLE
    private var safeEditorReadPending = false
    private var safeEditorReadGeneration = 0
    private var safeEditorLoadTimeout: Runnable? = null
    private var safeEditorReadTimeout: Runnable? = null

    private enum class SafeEditorStatus {
        IDLE,
        LOADING,
        READY,
        FAILED
    }

    private val isDark
        get() = AppConfig.editTemeAuto && ThemeConfig.isDarkTheme()
    private var themeIndex = -1

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        editor.colorScheme = TextMateColorScheme2.create(ThemeRegistry.getInstance()) //先设置颜色,避免一开始的白屏
        viewModel.initData(intent) {
            viewModel.title?.let {
                binding.titleBar.title = it
            }
            useSafeEditor = EditSafety.isCombiningHeavy(viewModel.initialText)
            if (useSafeEditor) {
                setupSafeEditor(viewModel.initialText)
            } else {
                setupCodeEditor(viewModel.initialText)
            }
            invalidateOptionsMenu()
        }
        initView()
    }

    private fun initView() {
        binding.root.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            softKeyboardTool.initialPadding = windowInsets.imeHeight
            windowInsets
        }
    }

    private fun setupCodeEditor(text: String) {
        safeEditor?.visibility = View.GONE
        editor.visibility = View.VISIBLE
        editor.apply {
            nonPrintablePaintingFlags = AppConfig.editNonPrintable
            setEditorLanguage(viewModel.language)
            upEdit(AppConfig.editFontScale, null, AppConfig.editAutoWrap)
            setText(text)
            editable = viewModel.writable
            requestFocus()
            postDelayed({
                val pos = cursor.indexer.getCharPosition(viewModel.cursorPosition)
                setSelection(pos.line, pos.column, true)
            }, 360) // 延时等待长文本完成布局，再恢复光标位置
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupSafeEditor(text: String) {
        safeEditorStatus = SafeEditorStatus.LOADING
        binding.searchGroup.visibility = View.GONE
        editorSearcher.stopSearch()
        editor.visibility = View.GONE
        val webView = getOrCreateSafeEditor()
        webView.apply {
            visibility = View.VISIBLE
            setBackgroundColor(backgroundColor)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                blockNetworkLoads = true
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (safeEditor !== view) return
                    safeEditorLoadTimeout?.let(view::removeCallbacks)
                    safeEditorLoadTimeout = null
                    safeEditorStatus = SafeEditorStatus.READY
                    updateSafeEditorActionButtons()
                    view.requestFocus()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (safeEditor === view && request.isForMainFrame) {
                        markSafeEditorLoadFailed(view)
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail
                ): Boolean {
                    if (safeEditor !== view) return false
                    handleSafeEditorRenderProcessGone(view)
                    return true
                }
            }
            scheduleSafeEditorLoadTimeout(this)
            loadDataWithBaseURL(
                null,
                buildSafeEditorHtml(text),
                "text/html",
                "utf-8",
                null
            )
        }
        updateSafeEditorActionButtons()
    }

    private fun getOrCreateSafeEditor(): WebView {
        return safeEditor ?: WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            contentDescription = getString(R.string.safe_code_editor)
            visibility = View.GONE
        }.also {
            binding.editorContainer.addView(it)
            safeEditor = it
        }
    }

    private fun scheduleSafeEditorLoadTimeout(webView: WebView) {
        safeEditorLoadTimeout?.let(webView::removeCallbacks)
        safeEditorLoadTimeout = Runnable {
            if (safeEditor === webView && safeEditorStatus == SafeEditorStatus.LOADING) {
                safeEditorStatus = SafeEditorStatus.FAILED
                updateSafeEditorActionButtons()
                toastOnUi(R.string.safe_code_editor_load_failed)
            }
        }.also {
            webView.postDelayed(it, SAFE_EDITOR_LOAD_TIMEOUT_MILLIS)
        }
    }

    private fun markSafeEditorLoadFailed(webView: WebView) {
        if (safeEditorStatus == SafeEditorStatus.FAILED) return
        safeEditorLoadTimeout?.let(webView::removeCallbacks)
        safeEditorLoadTimeout = null
        safeEditorStatus = SafeEditorStatus.FAILED
        updateSafeEditorActionButtons()
        toastOnUi(R.string.safe_code_editor_load_failed)
    }

    private fun handleSafeEditorRenderProcessGone(webView: WebView) {
        safeEditorStatus = SafeEditorStatus.FAILED
        cancelSafeEditorRead(restoreEditing = false)
        safeEditorLoadTimeout?.let(webView::removeCallbacks)
        safeEditorLoadTimeout = null
        binding.editorContainer.removeView(webView)
        safeEditor = null
        webView.destroy()
        updateSafeEditorActionButtons()
        toastOnUi(R.string.safe_code_editor_load_failed)
    }

    private fun updateSafeEditorActionButtons() {
        val enabled = viewModel.writable &&
            safeEditorStatus == SafeEditorStatus.READY &&
            !safeEditorReadPending
        menuSaveBtn?.isEnabled = enabled
        menuDebugSourceBtn?.isEnabled = enabled && isDebugSourceActionEnabled()
    }

    private fun buildSafeEditorHtml(text: String): String {
        val encodedText = Base64.encodeToString(
            text.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        val background = ColorUtils.intToString(backgroundColor)
        val foreground = ColorUtils.intToString(primaryTextColor)
        val readOnly = if (viewModel.writable) "" else " readonly"
        val writable = viewModel.writable
        val wrap = if (AppConfig.editAutoWrap) "soft" else "off"
        val cursorPosition = viewModel.cursorPosition.coerceAtLeast(0)
        return """
            <!doctype html>
            <html>
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <meta http-equiv="Content-Security-Policy"
                    content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'" />
                <style>
                    html, body {
                        width: 100%;
                        height: 100%;
                        margin: 0;
                        overflow: hidden;
                        background: $background;
                    }
                    textarea {
                        box-sizing: border-box;
                        width: 100%;
                        height: 100%;
                        border: 0;
                        outline: 0;
                        resize: none;
                        padding: 12px;
                        color: $foreground;
                        background: $background;
                        font-family: monospace;
                        font-size: ${AppConfig.editFontScale}px;
                        line-height: 1.4;
                    }
                </style>
            </head>
            <body>
                <textarea id="code" wrap="$wrap" spellcheck="false" autocomplete="off"
                    autocorrect="off" autocapitalize="off"$readOnly></textarea>
                <script>
                    function decodeBase64(value) {
                        var binary = atob(value);
                        var bytes = new Uint8Array(binary.length);
                        for (var i = 0; i < binary.length; i++) {
                            bytes[i] = binary.charCodeAt(i);
                        }
                        if (window.TextDecoder) {
                            return new TextDecoder("utf-8", { ignoreBOM: true }).decode(bytes);
                        }
                        var escaped = "";
                        for (var j = 0; j < bytes.length; j++) {
                            escaped += "%" + ("00" + bytes[j].toString(16)).slice(-2);
                        }
                        return decodeURIComponent(escaped);
                    }

                    var editor = document.getElementById("code");
                    editor.value = decodeBase64("$encodedText");
                    var initialValue = editor.value;
                    var editorWritable = $writable;
                    var initialCursor = Math.min(editor.value.length, $cursorPosition);
                    editor.setSelectionRange(initialCursor, initialCursor);
                    editor.focus();

                    window.__setEditorReadOnly = function(readOnly) {
                        if (readOnly) {
                            editor.blur();
                            editor.readOnly = true;
                        } else if (editorWritable) {
                            editor.readOnly = false;
                            editor.focus();
                        } else {
                            editor.readOnly = true;
                        }
                    };

                    window.__getEditorState = function() {
                        window.__setEditorReadOnly(true);
                        return JSON.stringify({
                            text: editor.value,
                            cursorPosition: editor.selectionStart || 0,
                            dirty: editor.value !== initialValue
                        });
                    };

                    window.__insertEditorText = function(encodedValue) {
                        if (editor.readOnly) return;
                        var value = decodeBase64(encodedValue);
                        var start = editor.selectionStart || 0;
                        var end = editor.selectionEnd || start;
                        if (editor.setRangeText) {
                            editor.setRangeText(value, start, end, "end");
                        } else {
                            editor.value = editor.value.slice(0, start) + value + editor.value.slice(end);
                            var cursor = start + value.length;
                            editor.setSelectionRange(cursor, cursor);
                        }
                    };
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun readSafeEditorState(onResult: (SafeEditorContent) -> Unit) {
        if (safeEditorReadPending) return
        val webView = safeEditor ?: run {
            toastOnUi(R.string.safe_code_editor_read_failed)
            return
        }
        val generation = ++safeEditorReadGeneration
        safeEditorReadPending = true
        updateSafeEditorActionButtons()
        safeEditorReadTimeout = Runnable {
            if (safeEditorReadGeneration == generation) {
                safeEditorReadGeneration++
                safeEditorReadPending = false
                safeEditorReadTimeout = null
                restoreSafeEditorEditing()
                updateSafeEditorActionButtons()
                toastOnUi(R.string.safe_code_editor_read_failed)
            }
        }.also {
            webView.postDelayed(it, SAFE_EDITOR_READ_TIMEOUT_MILLIS)
        }
        webView.evaluateJavascript(
            "window.__getEditorState && window.__getEditorState();"
        ) { value ->
            if (safeEditorReadGeneration != generation) return@evaluateJavascript
            safeEditorReadTimeout?.let(webView::removeCallbacks)
            safeEditorReadTimeout = null
            safeEditorReadPending = false
            updateSafeEditorActionButtons()
            val state = SafeEditorResultCodec.decode(value)
            if (state == null) {
                restoreSafeEditorEditing()
                toastOnUi(R.string.safe_code_editor_read_failed)
                return@evaluateJavascript
            }
            onResult(state)
        }
    }

    private fun cancelSafeEditorRead(restoreEditing: Boolean = true) {
        safeEditorReadGeneration++
        safeEditorReadTimeout?.let { timeout ->
            safeEditor?.removeCallbacks(timeout)
        }
        safeEditorReadTimeout = null
        safeEditorReadPending = false
        if (restoreEditing) restoreSafeEditorEditing()
        updateSafeEditorActionButtons()
    }

    private fun restoreSafeEditorEditing() {
        if (safeEditorStatus != SafeEditorStatus.READY || !viewModel.writable) return
        safeEditor?.evaluateJavascript(
            "window.__setEditorReadOnly && window.__setEditorReadOnly(false);",
            null
        )
    }

    private fun insertSafeEditorText(text: String) {
        val webView = safeEditor ?: return
        if (
            safeEditorStatus != SafeEditorStatus.READY ||
            !viewModel.writable ||
            safeEditorReadPending
        ) return
        val encodedText = Base64.encodeToString(
            text.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        webView.evaluateJavascript(
            "window.__insertEditorText && window.__insertEditorText('$encodedText');",
            null
        )
    }

    override fun onDestroy() {
        editorSearcher.stopSearch()
        editor.release()
        cancelSafeEditorRead(restoreEditing = false)
        safeEditorStatus = SafeEditorStatus.IDLE
        safeEditor?.apply {
            safeEditorLoadTimeout?.let(::removeCallbacks)
            safeEditorLoadTimeout = null
            webViewClient = WebViewClient()
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            binding.editorContainer.removeView(this)
            destroy()
        }
        safeEditor = null
        super.onDestroy()
    }

    /**
     * 使用super.finish(),防止循环回调
     * */
    private fun save(check: Boolean) {
        if (!viewModel.writable) return super.finish()
        if (useSafeEditor) {
            if (safeEditorReadPending) {
                cancelSafeEditorRead()
                if (check) confirmSafeEditorExit()
                return
            }
            if (safeEditorStatus != SafeEditorStatus.READY) {
                if (check) {
                    confirmSafeEditorExit()
                } else {
                    toastOnUi(R.string.safe_code_editor_load_failed)
                }
                return
            }
            readSafeEditorState { state ->
                val resolvedState = state.resolveAgainst(viewModel.initialText)
                if (check && resolvedState.text != viewModel.initialText) {
                    restoreSafeEditorEditing()
                }
                saveText(check, resolvedState.text, resolvedState.cursorPosition)
            }
            return
        }
        saveText(check, editor.text.toString(), editor.cursor?.left ?: 0)
    }

    private fun confirmSafeEditorExit() {
        alert(R.string.exit) {
            setMessage(R.string.exit_no_save)
            positiveButton(R.string.yes)
            negativeButton(R.string.no) {
                cancelSafeEditorRead()
                super.finish()
            }
        }
    }

    private fun saveText(check: Boolean, text: String, cursorPos: Int) {
        when {
            text == viewModel.initialText -> {
                val returnText = !check &&
                    intent.getBooleanExtra("returnUnchangedText", false)
                if (returnText || cursorPos > 0) {
                    val result = Intent().apply {
                        if (returnText) {
                            putExtra("text", text)
                        }
                        putExtra("cursorPosition", cursorPos)
                    }
                    setResult(RESULT_OK, result)
                }
                super.finish()
            }
            check -> {
                alert(R.string.exit) {
                    setMessage(R.string.exit_no_save)
                    positiveButton(R.string.yes)
                    negativeButton(R.string.no) {
                        if (cursorPos > 0) {
                            val result = Intent().apply {
                                putExtra("cursorPosition", cursorPos)
                            }
                            setResult(RESULT_OK, result)
                        }
                        super.finish()
                    }
                }
            }
            else -> {
                val result = Intent().apply {
                    putExtra("text", text)
                    putExtra("cursorPosition", cursorPos)
                }
                setResult(RESULT_OK, result)
                super.finish()
            }
        }
    }

    private fun returnText(action: String) {
        if (!viewModel.writable) return
        if (useSafeEditor) {
            if (safeEditorReadPending) return
            if (safeEditorStatus != SafeEditorStatus.READY) {
                toastOnUi(R.string.safe_code_editor_load_failed)
                return
            }
            readSafeEditorState { state ->
                val resolvedState = state.resolveAgainst(viewModel.initialText)
                returnText(action, resolvedState.text, resolvedState.cursorPosition)
            }
            return
        }
        returnText(action, editor.text.toString(), editor.cursor?.left ?: 0)
    }

    private fun returnText(action: String, text: String, cursorPosition: Int) {
        val result = Intent().apply {
            putExtra("text", text)
            putExtra("cursorPosition", cursorPosition)
            putExtra(EXTRA_RESULT_ACTION, action)
        }
        setResult(RESULT_OK, result)
        super.finish()
    }

    private fun isDebugSourceActionEnabled(): Boolean {
        return intent.getBooleanExtra(EXTRA_SHOW_DEBUG_SOURCE, false)
    }

    override fun upEdit(fontSize: Int?, autoComplete: Boolean?, autoWarp: Boolean?, editNonPrintable: Int?) {
        if (useSafeEditor) return
        if (fontSize != null) {
            editor.setTextSize(fontSize.toFloat())
        }
        if (autoComplete != null) {
            viewModel.language?.isAutoCompleteEnabled = autoComplete
            editor.setEditorLanguage(viewModel.language)
        }
        if (autoWarp != null) {
            editor.isWordwrap = autoWarp
        }
        if (editNonPrintable != null) {
            editor.nonPrintablePaintingFlags = editNonPrintable
        }
    }

    override fun initTheme() {
        super.initTheme()
        if (!isInitialized) {
            viewModel.initSora()
            isInitialized = true
        }
        val index = if (isDark) {
            AppConfig.editThemeDark
        } else {
            AppConfig.editTheme
        }
        upTheme(index)
        themeIndex = index
    }

    override fun upTheme(index: Int) {
        if (useSafeEditor) return
        if (themeIndex != index) {
            viewModel.loadTextMateThemes(index)
            editor.setEditorLanguage(viewModel.language) //每次更改颜色后需要再执行一次语言设置,防止切换主题后高亮颜色不正确
            themeIndex = index
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.code_edit_activity, menu)
        menuSaveBtn = menu.findItem(R.id.menu_save)
        menuDebugSourceBtn = menu.findItem(R.id.menu_debug_source)
        updateEditorMenu(menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        updateEditorMenu(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    private fun updateEditorMenu(menu: Menu) {
        val showSoraActions = !useSafeEditor
        menu.findItem(R.id.menu_search)?.isVisible = showSoraActions
        menu.findItem(R.id.menu_change_theme)?.isVisible = showSoraActions
        menu.findItem(R.id.menu_format_code)?.isVisible = showSoraActions
        menu.findItem(R.id.menu_config_settings)?.isVisible = showSoraActions
        menu.findItem(R.id.menu_auto_wrap)?.apply {
            isVisible = showSoraActions
            isChecked = AppConfig.editAutoWrap
        }
        menu.findItem(R.id.menu_save)?.apply {
            isVisible = viewModel.writable
            isEnabled = viewModel.writable &&
                (!useSafeEditor ||
                    (safeEditorStatus == SafeEditorStatus.READY && !safeEditorReadPending))
        }
        menu.findItem(R.id.menu_debug_source)?.apply {
            isVisible = shouldShowDebugSourceAction(
                viewModel.writable,
                isDebugSourceActionEnabled()
            )
            isEnabled = viewModel.writable &&
                (!useSafeEditor ||
                    (safeEditorStatus == SafeEditorStatus.READY && !safeEditorReadPending))
        }
    }

    private fun setSearchOptions() {
        searchOptions =  SearchOptions(
            if (isRegex) SearchOptions.TYPE_REGULAR_EXPRESSION else SearchOptions.TYPE_NORMAL,
            !isRegex,
            RegexBackrefGrammar.DEFAULT
        )
    }

    private fun search() {
        if (useSafeEditor) return
        if (binding.searchGroup.isVisible) return
        binding.switchRegex.run {
            isChecked = isRegex
            setSearchOptions()
            setOnCheckedChangeListener { _, isChecked ->
                isRegex = isChecked
                setSearchOptions()
                searchTxt(binding.etFind.text.toString())
            }
        }
        val receiptSearch =
            editor.subscribeEvent(PublishSearchResultEvent::class.java) { event, _ ->
                if (event.editor == editor) {
                    updateSearchResults()
                }
            }
        val receiptChange = editor.subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
            if (event.cause == SelectionChangeEvent.CAUSE_SEARCH) {
                updateSearchResults()
            }
        }
        binding.searchGroup.visibility = View.VISIBLE
        binding.btnCloseFind.setOnClickListener {
            binding.searchGroup.visibility = View.GONE
            editorSearcher.stopSearch()
            receiptSearch.unsubscribe()
            receiptChange.unsubscribe()
            editor.requestFocus()
            editor.invalidate()
        }
        searchTxt(findText)
        binding.etFind.run {
            requestFocus()
            setText(findText)
            addTextChangedListener { text ->
                if (!text.isNullOrEmpty()) {
                    findText = text.toString()
                    searchTxt(findText)
                } else {
                    editorSearcher.stopSearch()
                    editor.invalidate()
                }
            }

        }
        binding.etReplace.run {
            setText(replaceText)
            addTextChangedListener { text ->
                if (!text.isNullOrEmpty()) {
                    replaceText = text.toString()
                }
            }
        }
        binding.btnPrevious.setOnClickListener {
            if (editorSearcher.hasQuery()) {
                editorSearcher.gotoPrevious()
            }
        }
        binding.btnNext.setOnClickListener {
            if (editorSearcher.hasQuery()) {
                editorSearcher.gotoNext()
            }
        }
        binding.btnReplace.setOnClickListener {
            if (binding.replaceGroup.isGone) {
                binding.replaceGroup.visibility = View.VISIBLE
                binding.btnReplaceAll.isEnabled = true
                binding.etReplace.requestFocus()
            } else {
                if (editorSearcher.hasQuery()) {
                    editorSearcher.replaceCurrentMatch(binding.etReplace.text.toString())
                }
            }
        }
        binding.btnCloseReplace.setOnClickListener {
            binding.replaceGroup.visibility = View.GONE
            binding.btnReplaceAll.isEnabled = false
            binding.etFind.requestFocus()
        }
        binding.btnReplaceAll.setOnClickListener {
            if (editorSearcher.hasQuery()) {
                editorSearcher.replaceAll(binding.etReplace.text.toString())
            }
        }
    }

    private fun searchTxt(txt: String) {
        if (txt.isNotEmpty()) {
            try {
                searchOptions?.let {
                    editorSearcher.search(txt, it)
                }
            } catch (_: java.util.regex.PatternSyntaxException) {
                // 忽略正则表达式语法错误
                editorSearcher.stopSearch()
                editor.invalidate()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateSearchResults() {
        if (editorSearcher.hasQuery()) {
            val totalResults = editorSearcher.matchedPositionCount
            val currentPosition = editorSearcher.currentMatchedPositionIndex + 1
            binding.tvSearchResult.text =
                "${if (currentPosition > 0) "$currentPosition/" else ""}$totalResults"
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_search -> if (!useSafeEditor) search()
            R.id.menu_save -> save(false)
            R.id.menu_debug_source -> returnText(RESULT_ACTION_DEBUG_SOURCE)
            R.id.menu_format_code -> if (!useSafeEditor) viewModel.formatCode(editor)
            R.id.menu_change_theme -> if (!useSafeEditor) showDialogFragment(ChangeThemeDialog())
            R.id.menu_config_settings -> if (!useSafeEditor) {
                showDialogFragment(SettingsDialog(this, this))
            }
            R.id.menu_auto_wrap -> {
                if (useSafeEditor) return super.onCompatOptionsItemSelected(item)
                item.isChecked = !AppConfig.editAutoWrap
                upEdit(autoWarp = !AppConfig.editAutoWrap)
                putPrefBoolean(PreferKey.editAutoWrap, !AppConfig.editAutoWrap)
            }
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun finish() {
        save(true)
    }

    override fun helpActions(): List<SelectItem<String>> {
        return arrayListOf(
            SelectItem("书源教程", "ruleHelp"),
            SelectItem("订阅源教程", "rssRuleHelp"),
            SelectItem("js教程", "jsHelp"),
            SelectItem("正则教程", "regexHelp")
        )
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "ruleHelp" -> showHelp("ruleHelp")
            "rssRuleHelp" -> showHelp("rssRuleHelp")
            "jsHelp" -> showHelp("jsHelp")
            "regexHelp" -> showHelp("regexHelp")
        }
    }

    override fun sendText(text: String) {
        if (useSafeEditor) {
            insertSafeEditorText(text)
            return
        }
        val view = window.decorView.findFocus()
        if (view is TextInputEditText) {
            var start = view.selectionStart
            var end = view.selectionEnd
            if (start > end) {
                val temp = start
                start = end
                end = temp
            }
            if (text.isNotEmpty()) {
                val edit = view.editableText//获取EditText的文字
                if (start < 0 || start >= edit.length) {
                    edit.append(text)
                } else {
                    edit.replace(start, end, text)//光标所在位置插入文字
                }
            }
        }
        else {
            editor.insertText(text, text.length)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onUndoClicked() {
        if (useSafeEditor) {
            if (
                safeEditorStatus == SafeEditorStatus.READY &&
                viewModel.writable &&
                !safeEditorReadPending
            ) {
                safeEditor?.evaluateJavascript("document.execCommand('undo');", null)
            }
        } else {
            editor.undo()
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRedoClicked() {
        if (useSafeEditor) {
            if (
                safeEditorStatus == SafeEditorStatus.READY &&
                viewModel.writable &&
                !safeEditorReadPending
            ) {
                safeEditor?.evaluateJavascript("document.execCommand('redo');", null)
            }
        } else {
            editor.redo()
        }
    }
}

internal fun shouldShowDebugSourceAction(writable: Boolean, requested: Boolean): Boolean {
    return writable && requested
}
