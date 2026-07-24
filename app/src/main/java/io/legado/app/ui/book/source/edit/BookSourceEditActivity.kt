package io.legado.app.ui.book.source.edit

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.databinding.ActivityBookSourceEditBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.code.EditSafety
import io.legado.app.ui.widget.dialog.UrlOptionDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.ui.widget.recycler.NoChildScrollLinearLayoutManager
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.GSON
import io.legado.app.utils.imeHeight
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.share
import io.legado.app.utils.shareWithQr
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.bottomPadding

class BookSourceEditActivity :
    VMBaseActivity<ActivityBookSourceEditBinding, BookSourceEditViewModel>(),
    KeyboardToolPop.CallBack,
    VariableDialog.Callback {

    override val binding by viewBinding(ActivityBookSourceEditBinding::inflate)
    override val viewModel by viewModels<BookSourceEditViewModel>()

    private val adapter by lazy { BookSourceEditAdapter(::openUnsafeTextEditor) }
    private val sourceEntities: ArrayList<EditEntity> = ArrayList()
    private val searchEntities: ArrayList<EditEntity> = ArrayList()
    private val exploreEntities: ArrayList<EditEntity> = ArrayList()
    private val infoEntities: ArrayList<EditEntity> = ArrayList()
    private val tocEntities: ArrayList<EditEntity> = ArrayList()
    private val contentEntities: ArrayList<EditEntity> = ArrayList()
    private val reviewEntities: ArrayList<EditEntity> = ArrayList()
    private var redirectJsSourceUrl: String? = null
    private var pendingEditKey: String? = null
    private var pendingEditTabPosition = 0
    private var pendingResultAvailable = false
    private var pendingResultText: String? = null
    private var pendingResultCursor = -1

    private val jsSourceEdit = registerForActivityResult(
        StartActivityContract(JsSourceEditActivity::class.java)
    ) { result ->
        setResult(result.resultCode, result.data)
        super.finish()
    }

    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        viewModel.importSource(it) { source ->
            upSourceView(source)
        }
    }
    private val selectDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                sendText(uri.toString())
            } else {
                sendText(uri.path.toString())
            }
        }
    }

    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        pendingEditKey = savedInstanceState?.getString(STATE_PENDING_EDIT_KEY)
        pendingEditTabPosition = savedInstanceState?.getInt(STATE_PENDING_EDIT_TAB) ?: 0
        redirectJsSourceUrl = intent.getStringExtra("sourceUrl")
            ?.takeIf { appDb.bookSourceDao.hasJsSource(it) }
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_EDIT_KEY, pendingEditKey)
        outState.putInt(STATE_PENDING_EDIT_TAB, pendingEditTabPosition)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        redirectJsSourceUrl?.let { sourceUrl ->
            if (savedInstanceState == null) {
                jsSourceEdit.launch {
                    putExtra("sourceUrl", sourceUrl)
                }
            }
            return
        }
        softKeyboardTool.attachToWindow(window)
        initView()
        viewModel.initData(intent) {
            viewModel.bookSource?.takeIf { it.isJsSource() }?.let { source ->
                jsSourceEdit.launch {
                    putExtra("sourceUrl", source.bookSourceUrl)
                }
                return@initData
            }
            upSourceView(viewModel.bookSource)
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (redirectJsSourceUrl != null) return
        if (!LocalConfig.ruleHelpVersionIsLast) {
            showHelp("ruleHelp")
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.source_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible = getSource().hasLogin()
        menu.findItem(R.id.menu_auto_complete)?.isChecked = viewModel.autoComplete
        return super.onMenuOpened(featureId, menu)
    }

    private val textEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingResultAvailable = true
            pendingResultText = result.data?.getStringExtra("text")
            pendingResultCursor = result.data?.getIntExtra("cursorPosition", -1) ?: -1
            applyPendingEditResult()
        } else {
            clearPendingEditResult()
        }
    }

    private fun onFullEditClicked() {
        val view = window.decorView.findFocus()
        if (view is EditText) {
            val key = view.getTag(R.id.tag) as? String
            val editEntity = key?.let {
                findEditEntity(binding.tabLayout.selectedTabPosition, it)
            }
            if (editEntity != null) {
                openTextEditor(editEntity, view.selectionStart)
                return
            }
        }
        toastOnUi(R.string.please_focus_cursor_on_textbox)
    }

    private fun openUnsafeTextEditor(editEntity: EditEntity) {
        openTextEditor(editEntity, 0)
    }

    private fun openTextEditor(editEntity: EditEntity, cursorPosition: Int) {
        pendingEditTabPosition = binding.tabLayout.selectedTabPosition
        pendingEditKey = editEntity.key
        val intent = Intent(this, CodeEditActivity::class.java).apply {
            putExtra("text", editEntity.value.orEmpty())
            putExtra("title", editEntity.hint)
            putExtra("cursorPosition", cursorPosition)
        }
        textEditLauncher.launch(intent)
    }

    private fun findEditEntity(tabPosition: Int, key: String): EditEntity? {
        val entities = when (tabPosition) {
            1 -> searchEntities
            2 -> exploreEntities
            3 -> infoEntities
            4 -> tocEntities
            5 -> contentEntities
            6 -> reviewEntities
            else -> sourceEntities
        }
        return entities.find { it.key == key }
    }

    private fun refreshEditedEntity(editEntity: EditEntity, cursorPosition: Int) {
        val index = adapter.editEntities.indexOf(editEntity)
        if (index < 0) return

        adapter.notifyItemChanged(index)
        if (
            cursorPosition < 0 ||
            EditSafety.isCombiningHeavy(editEntity.value.orEmpty()) ||
            EditSafety.isTooLongForInline(editEntity.value.orEmpty())
        ) return

        binding.recyclerView.post {
            val holder = binding.recyclerView.findViewHolderForAdapterPosition(index)
                as? BookSourceEditAdapter.MyViewHolder
            holder?.binding?.editText?.run {
                requestFocus()
                setSelection(cursorPosition.coerceIn(0, text.length))
            }
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_fullscreen_edit -> onFullEditClicked()

            R.id.menu_save -> viewModel.save(getSource()) {
                setResult(RESULT_OK, Intent().putExtra("origin", it.bookSourceUrl))
                finish()
            }

            R.id.menu_debug_source -> viewModel.save(getSource()) { source ->
                startActivity<BookSourceDebugActivity> {
                    putExtra("key", source.bookSourceUrl)
                }
            }

            R.id.menu_clear_cookie -> viewModel.clearCookie(getSource().bookSourceUrl)
            R.id.menu_auto_complete -> viewModel.autoComplete = !viewModel.autoComplete
            R.id.menu_copy_source -> sendToClip(GSON.toJson(getSource()))
            R.id.menu_paste_source -> viewModel.pasteSource { upSourceView(it) }
            R.id.menu_qr_code_camera -> qrCodeResult.launch()
            R.id.menu_share_str -> share(GSON.toJson(getSource()))
            R.id.menu_share_qr -> shareWithQr(
                GSON.toJson(getSource()),
                getString(R.string.share_book_source),
                ErrorCorrectionLevel.L
            )

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_help -> showHelp("ruleHelp")
            R.id.menu_login -> viewModel.save(getSource()) { source ->
                startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", source.bookSourceUrl)
                }
            }

            R.id.menu_set_source_variable -> setSourceVariable()
            R.id.menu_search -> viewModel.save(getSource()) { source ->
                SearchActivity.start(this, source)
            }

        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        initOptionPanel()
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_base)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_search)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_find)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_info)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_toc)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_content)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_review)
        })
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        if (adapter.editEntityMaxLine < 999) {
            binding.recyclerView.layoutManager = NoChildScrollLinearLayoutManager(this) //启用后会阻止RecyclerView跟随光标滚动,行数少时,用的TextView跟随
        }
        binding.recyclerView.adapter = adapter
        binding.recyclerView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus is EditText) {
                newFocus.postDelayed({ sendText("") }, 120)
            }
        }
        val transparentBar = transparentNavBar && !AppConfig.isEInkMode
        binding.tabLayout.setBackgroundColor(
            if (transparentBar) Color.TRANSPARENT else backgroundColor
        )
        if (transparentBar) binding.tabLayout.elevation = 0f
        binding.tabLayout.setSelectedTabIndicatorColor(accentColor)
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabReselected(tab: TabLayout.Tab?) {

            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {

            }

            override fun onTabSelected(tab: TabLayout.Tab?) {
                setEditEntities(tab?.position)
            }
        })
        binding.recyclerView.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val navigationBarHeight = windowInsets.navigationBarHeight
            val imeHeight = windowInsets.imeHeight
            view.bottomPadding = if (imeHeight == 0) navigationBarHeight else 0
            softKeyboardTool.initialPadding = imeHeight
            windowInsets
        }
    }

    private fun initOptionPanel() {
        binding.optionsHeader.setOnClickListener {
            updateOptionPanel(binding.optionsContent.visibility != View.VISIBLE)
        }
        listOf(
            binding.cbIsEnable,
            binding.cbIsEnableExplore,
            binding.cbIsEnableCookie,
            binding.cbIsEnableReview,
            binding.cbIsEventListener,
            binding.cbIsCustomButton
        ).forEach { checkBox ->
            checkBox.setOnCheckedChangeListener { _, _ -> updateOptionPanel() }
        }
        binding.spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) = updateOptionPanel()

            override fun onNothingSelected(parent: AdapterView<*>?) = updateOptionPanel()
        }
        updateOptionPanel(false)
    }

    private fun updateOptionPanel(expanded: Boolean = binding.optionsContent.visibility == View.VISIBLE) {
        binding.optionsContent.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.ivOptionsExpand.setImageResource(
            if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        )
        val summary = mutableListOf(
            binding.spType.selectedItem?.toString() ?: getString(R.string.book_type)
        )
        listOf(
            getString(R.string.is_enable) to binding.cbIsEnable.isChecked,
            getString(R.string.discovery) to binding.cbIsEnableExplore.isChecked,
            getString(R.string.auto_save_cookie) to binding.cbIsEnableCookie.isChecked,
            getString(R.string.review) to binding.cbIsEnableReview.isChecked,
            getString(R.string.is_event_listener) to binding.cbIsEventListener.isChecked,
            getString(R.string.custom_button) to binding.cbIsCustomButton.isChecked
        ).forEach { (label, checked) ->
            if (checked) summary.add(label)
        }
        binding.tvOptionsSummary.text = summary.joinToString(" | ")
        val action = getString(
            if (expanded) R.string.book_intro_collapse else R.string.book_intro_expand
        )
        binding.optionsHeader.contentDescription =
            "${getString(R.string.setting)}, ${binding.tvOptionsSummary.text}, $action"
    }

    override fun finish() {
        val source = getSource()
        if (!source.equal(viewModel.bookSource ?: BookSource())) {
            alert(R.string.exit) {
                setMessage(R.string.exit_no_save)
                positiveButton(R.string.yes)
                negativeButton(R.string.no) {
                    super.finish()
                }
            }
        } else {
            super.finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        softKeyboardTool.dismiss()
    }

    private fun setEditEntities(tabPosition: Int?) {
        adapter.editEntities = when (tabPosition) {
            1 -> searchEntities
            2 -> exploreEntities
            3 -> infoEntities
            4 -> tocEntities
            5 -> contentEntities
            6 -> reviewEntities
            else -> sourceEntities
        }
        binding.recyclerView.scrollToPosition(0)
        window.decorView.rootView.clearFocus()
    }

    private fun upSourceView(bookSource: BookSource?) {
        val bs = bookSource ?: BookSource()
        bs.let {
            binding.cbIsEnable.isChecked = it.enabled
            binding.cbIsEnableExplore.isChecked = it.enabledExplore
            binding.cbIsEnableCookie.isChecked = it.enabledCookieJar ?: false
            binding.cbIsEnableReview.isChecked = it.ruleReview?.enabled ?: false
            binding.spType.setSelection(
                when (it.bookSourceType) {
                    BookSourceType.video -> 4
                    BookSourceType.file -> 3
                    BookSourceType.image -> 2
                    BookSourceType.audio -> 1
                    else -> 0
                }
            )
            binding.cbIsEventListener.isChecked = it.eventListener
            binding.cbIsCustomButton.isChecked = it.customButton
        }
        updateOptionPanel()
        // 基本信息
        sourceEntities.clear()
        sourceEntities.apply {
            add(EditEntity("bookSourceUrl", bs.bookSourceUrl, R.string.source_url))
            add(EditEntity("bookSourceName", bs.bookSourceName, R.string.source_name))
            add(EditEntity("bookSourceGroup", bs.bookSourceGroup, R.string.source_group))
            add(EditEntity("bookSourceComment", bs.bookSourceComment, R.string.comment))
            add(EditEntity("loginUrl", bs.loginUrl, R.string.login_url))
            add(EditEntity("loginUi", bs.loginUi, R.string.login_ui))
            add(EditEntity("loginCheckJs", bs.loginCheckJs, R.string.login_check_js))
            add(EditEntity("coverDecodeJs", bs.coverDecodeJs, R.string.cover_decode_js))
            add(EditEntity("bookUrlPattern", bs.bookUrlPattern, R.string.book_url_pattern))
            add(EditEntity("header", bs.header, R.string.source_http_header))
            add(EditEntity("variableComment", bs.variableComment, R.string.variable_comment))
            add(EditEntity("concurrentRate", bs.concurrentRate, R.string.concurrent_rate))
            add(EditEntity("jsLib", bs.jsLib, "jsLib"))
        }
        // 搜索
        val sr = bs.getSearchRule()
        searchEntities.clear()
        searchEntities.apply {
            add(EditEntity("searchUrl", bs.searchUrl, R.string.r_search_url))
            add(EditEntity("checkKeyWord", sr.checkKeyWord, R.string.check_key_word))
            add(EditEntity("bookList", sr.bookList, R.string.r_book_list))
            add(EditEntity("name", sr.name, R.string.r_book_name))
            add(EditEntity("author", sr.author, R.string.r_author))
            add(EditEntity("kind", sr.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", sr.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", sr.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", sr.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", sr.coverUrl, R.string.rule_cover_url))
            add(EditEntity("bookUrl", sr.bookUrl, R.string.r_book_url))
        }
        // 发现
        val er = bs.getExploreRule()
        exploreEntities.clear()
        exploreEntities.apply {
            add(EditEntity("exploreUrl", bs.exploreUrl, R.string.r_find_url))
            add(EditEntity("bookList", er.bookList, R.string.r_book_list))
            add(EditEntity("name", er.name, R.string.r_book_name))
            add(EditEntity("author", er.author, R.string.r_author))
            add(EditEntity("kind", er.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", er.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", er.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", er.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", er.coverUrl, R.string.rule_cover_url))
            add(EditEntity("bookUrl", er.bookUrl, R.string.r_book_url))
        }
        // 详情页
        val ir = bs.getBookInfoRule()
        infoEntities.clear()
        infoEntities.apply {
            add(EditEntity("init", ir.init, R.string.rule_book_info_init))
            add(EditEntity("name", ir.name, R.string.r_book_name))
            add(EditEntity("author", ir.author, R.string.r_author))
            add(EditEntity("kind", ir.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", ir.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", ir.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", ir.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", ir.coverUrl, R.string.rule_cover_url))
            add(EditEntity("tocUrl", ir.tocUrl, R.string.rule_toc_url))
            add(EditEntity("canReName", ir.canReName, R.string.rule_can_re_name))
            add(EditEntity("downloadUrls", ir.downloadUrls, R.string.download_url_rule))
        }
        // 目录页
        val tr = bs.getTocRule()
        tocEntities.clear()
        tocEntities.apply {
            add(EditEntity("preUpdateJs", tr.preUpdateJs, R.string.pre_update_js))
            add(EditEntity("chapterList", tr.chapterList, R.string.rule_chapter_list))
            add(EditEntity("chapterName", tr.chapterName, R.string.rule_chapter_name))
            add(EditEntity("chapterUrl", tr.chapterUrl, R.string.rule_chapter_url))
            add(EditEntity("formatJs", tr.formatJs, R.string.format_js_rule))
            add(EditEntity("isVolume", tr.isVolume, R.string.rule_is_volume))
            add(EditEntity("updateTime", tr.updateTime, R.string.rule_update_time))
            add(EditEntity("isVip", tr.isVip, R.string.rule_is_vip))
            add(EditEntity("isPay", tr.isPay, R.string.rule_is_pay))
            add(EditEntity("nextTocUrl", tr.nextTocUrl, R.string.rule_next_toc_url))
        }
        // 正文页
        val cr = bs.getContentRule()
        contentEntities.clear()
        contentEntities.apply {
            add(EditEntity("content", cr.content, R.string.rule_book_content))
            add(EditEntity("nextContentUrl", cr.nextContentUrl, R.string.rule_next_content))
            add(EditEntity("subContent", cr.subContent, R.string.rule_sub_content))
            add(EditEntity("replaceRegex", cr.replaceRegex, R.string.rule_replace_regex))
            add(EditEntity("title", cr.title, R.string.rule_chapter_name))
            add(EditEntity("sourceRegex", cr.sourceRegex, R.string.rule_source_regex))
            add(EditEntity("imageStyle", cr.imageStyle, R.string.rule_image_style))
            add(EditEntity("imageDecode", cr.imageDecode, R.string.rule_image_decode))
            add(EditEntity("webJs", cr.webJs, R.string.rule_web_js))
            add(EditEntity("payAction", cr.payAction, R.string.rule_pay_action))
            add(EditEntity("callBackJs", cr.callBackJs, R.string.rule_call_back))
        }
        // 段评
        val rr = bs.ruleReview ?: ReviewRule()
        reviewEntities.clear()
        reviewEntities.apply {
            add(EditEntity("reviewSummaryUrl", rr.reviewSummaryUrl, R.string.rule_review_summary_url))
            add(EditEntity("summaryListRule", rr.summaryListRule, R.string.rule_review_summary_list))
            add(EditEntity("summaryParagraphIndexRule", rr.summaryParagraphIndexRule, R.string.rule_review_summary_id))
            add(EditEntity("summaryCountRule", rr.summaryCountRule, R.string.rule_review_summary_count))
            add(EditEntity("summaryParagraphDataRule", rr.summaryParagraphDataRule, R.string.rule_review_summary_key))

            add(EditEntity("reviewDetailUrl", rr.reviewDetailUrl, R.string.rule_review_detail_url))
            add(EditEntity("reviewDetailNextPageUrl", rr.reviewDetailNextPageUrl, R.string.rule_review_detail_next_url))
            add(EditEntity("detailListRule", rr.detailListRule, R.string.rule_review_detail_list))
            add(EditEntity("detailIdRule", rr.detailIdRule, R.string.rule_review_detail_id))
            add(EditEntity("detailAvatarRule", rr.detailAvatarRule, R.string.rule_review_detail_avatar))
            add(EditEntity("detailNameRule", rr.detailNameRule, R.string.rule_review_detail_name))
            add(EditEntity("detailBadgeRule", rr.detailBadgeRule, R.string.rule_review_detail_badge))
            add(EditEntity("detailContentRule", rr.detailContentRule, R.string.rule_review_detail_content))

            add(EditEntity("replyListRule", rr.replyListRule, R.string.rule_review_reply_list))
            add(EditEntity("replyIdRule", rr.replyIdRule, R.string.rule_review_reply_id))
            add(EditEntity("replyAvatarRule", rr.replyAvatarRule, R.string.rule_review_reply_avatar))
            add(EditEntity("replyNameRule", rr.replyNameRule, R.string.rule_review_reply_name))
            add(EditEntity("replyBadgeRule", rr.replyBadgeRule, R.string.rule_review_reply_badge))
            add(EditEntity("replyContentRule", rr.replyContentRule, R.string.rule_review_reply_content))
        }
        binding.tabLayout.selectTab(binding.tabLayout.getTabAt(0))
        setEditEntities(0)
        applyPendingEditResult()
    }

    private fun applyPendingEditResult() {
        if (!pendingResultAvailable) return
        val editEntity = pendingEditKey?.let {
            findEditEntity(pendingEditTabPosition, it)
        } ?: return
        pendingResultText?.let { editEntity.value = it }
        refreshEditedEntity(editEntity, pendingResultCursor)
        clearPendingEditResult()
    }

    private fun clearPendingEditResult() {
        pendingEditKey = null
        pendingResultAvailable = false
        pendingResultText = null
        pendingResultCursor = -1
    }

    private fun getSource(): BookSource {
        val source = viewModel.bookSource?.copy() ?: BookSource()
        source.enabled = binding.cbIsEnable.isChecked
        source.enabledExplore = binding.cbIsEnableExplore.isChecked
        source.enabledCookieJar = binding.cbIsEnableCookie.isChecked
        source.bookSourceType = when (binding.spType.selectedItemPosition) {
            4 -> BookSourceType.video
            3 -> BookSourceType.file
            2 -> BookSourceType.image
            1 -> BookSourceType.audio
            else -> BookSourceType.default
        }
        source.eventListener = binding.cbIsEventListener.isChecked
        source.customButton = binding.cbIsCustomButton.isChecked
        val searchRule = SearchRule()
        val exploreRule = ExploreRule()
        val bookInfoRule = BookInfoRule()
        val tocRule = TocRule()
        val contentRule = ContentRule()
        val reviewRule = source.ruleReview?.copy() ?: ReviewRule()
        reviewRule.enabled = binding.cbIsEnableReview.isChecked
        sourceEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "bookSourceUrl" -> source.bookSourceUrl = it.value ?: ""
                "bookSourceName" -> source.bookSourceName = it.value ?: ""
                "bookSourceGroup" -> source.bookSourceGroup = it.value
                "loginUrl" -> source.loginUrl = it.value
                "loginUi" -> source.loginUi = it.value
                "loginCheckJs" -> source.loginCheckJs = it.value
                "coverDecodeJs" -> source.coverDecodeJs = it.value
                "bookUrlPattern" -> source.bookUrlPattern = it.value
                "header" -> source.header = it.value
                "bookSourceComment" -> source.bookSourceComment = it.value
                "concurrentRate" -> source.concurrentRate = it.value
                "variableComment" -> source.variableComment = it.value
                "jsLib" -> source.jsLib = it.value
            }
        }
        searchEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "searchUrl" -> source.searchUrl = it.value
                "checkKeyWord" -> searchRule.checkKeyWord = it.value
                "bookList" -> searchRule.bookList = it.value
                "name" -> searchRule.name =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "author" -> searchRule.author =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "kind" -> searchRule.kind =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "intro" -> searchRule.intro =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

//                "updateTime" -> searchRule.updateTime =
//                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "wordCount" -> searchRule.wordCount =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "lastChapter" -> searchRule.lastChapter =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "coverUrl" -> searchRule.coverUrl =
                    viewModel.ruleComplete(it.value, searchRule.bookList, 3)

                "bookUrl" -> searchRule.bookUrl =
                    viewModel.ruleComplete(it.value, searchRule.bookList, 2)
            }
        }
        exploreEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "exploreUrl" -> source.exploreUrl = it.value
                "bookList" -> exploreRule.bookList = it.value
                "name" -> exploreRule.name =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "author" -> exploreRule.author =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "kind" -> exploreRule.kind =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "intro" -> exploreRule.intro =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

//                "updateTime" -> exploreRule.updateTime =
//                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "wordCount" -> exploreRule.wordCount =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "lastChapter" -> exploreRule.lastChapter =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "coverUrl" -> exploreRule.coverUrl =
                    viewModel.ruleComplete(it.value, exploreRule.bookList, 3)

                "bookUrl" -> exploreRule.bookUrl =
                    viewModel.ruleComplete(it.value, exploreRule.bookList, 2)
            }
        }
        infoEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "init" -> bookInfoRule.init = it.value
                "name" -> bookInfoRule.name = viewModel.ruleComplete(it.value, bookInfoRule.init)
                "author" -> bookInfoRule.author =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "kind" -> bookInfoRule.kind =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "intro" -> bookInfoRule.intro =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

//                "updateTime" -> bookInfoRule.updateTime =
//                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "wordCount" -> bookInfoRule.wordCount =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "lastChapter" -> bookInfoRule.lastChapter =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "coverUrl" -> bookInfoRule.coverUrl =
                    viewModel.ruleComplete(it.value, bookInfoRule.init, 3)

                "tocUrl" -> bookInfoRule.tocUrl =
                    viewModel.ruleComplete(it.value, bookInfoRule.init, 2)

                "canReName" -> bookInfoRule.canReName = it.value
                "downloadUrls" -> bookInfoRule.downloadUrls =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)
            }
        }
        tocEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "preUpdateJs" -> tocRule.preUpdateJs = it.value
                "chapterList" -> tocRule.chapterList = it.value
                "chapterName" -> tocRule.chapterName =
                    viewModel.ruleComplete(it.value, tocRule.chapterList)

                "chapterUrl" -> tocRule.chapterUrl =
                    viewModel.ruleComplete(it.value, tocRule.chapterList, 2)

                "formatJs" -> tocRule.formatJs = it.value
                "isVolume" -> tocRule.isVolume = it.value
                "updateTime" -> tocRule.updateTime = it.value
                "isVip" -> tocRule.isVip = it.value
                "isPay" -> tocRule.isPay = it.value
                "nextTocUrl" -> tocRule.nextTocUrl =
                    viewModel.ruleComplete(it.value, tocRule.chapterList, 2)
            }
        }
        contentEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "content" -> contentRule.content = viewModel.ruleComplete(it.value)
                "nextContentUrl" -> contentRule.nextContentUrl =
                    viewModel.ruleComplete(it.value, type = 2)
                "subContent" -> contentRule.subContent = viewModel.ruleComplete(it.value)
                "title" -> contentRule.title = viewModel.ruleComplete(it.value)

                "webJs" -> contentRule.webJs = it.value
                "sourceRegex" -> contentRule.sourceRegex = it.value
                "replaceRegex" -> contentRule.replaceRegex = it.value
                "imageStyle" -> contentRule.imageStyle = it.value
                "imageDecode" -> contentRule.imageDecode = it.value
                "payAction" -> contentRule.payAction = it.value
                "callBackJs" -> contentRule.callBackJs = it.value
            }
        }
        reviewEntities.forEach {
            it.value = it.value?.takeIf { value -> value.isNotBlank() }
            when (it.key) {
                "reviewSummaryUrl" -> reviewRule.reviewSummaryUrl = it.value
                "summaryListRule" -> reviewRule.summaryListRule = it.value
                "summaryParagraphIndexRule" -> reviewRule.summaryParagraphIndexRule = it.value
                "summaryCountRule" -> reviewRule.summaryCountRule = it.value
                "summaryParagraphDataRule" -> reviewRule.summaryParagraphDataRule = it.value
                "reviewDetailUrl" -> reviewRule.reviewDetailUrl = it.value
                "reviewDetailNextPageUrl" -> reviewRule.reviewDetailNextPageUrl = it.value
                "detailListRule" -> reviewRule.detailListRule = it.value
                "detailIdRule" -> reviewRule.detailIdRule = it.value
                "detailAvatarRule" -> reviewRule.detailAvatarRule = it.value
                "detailNameRule" -> reviewRule.detailNameRule = it.value
                "detailBadgeRule" -> reviewRule.detailBadgeRule = it.value
                "detailContentRule" -> reviewRule.detailContentRule = it.value
                "replyListRule" -> reviewRule.replyListRule = it.value
                "replyIdRule" -> reviewRule.replyIdRule = it.value
                "replyAvatarRule" -> reviewRule.replyAvatarRule = it.value
                "replyNameRule" -> reviewRule.replyNameRule = it.value
                "replyBadgeRule" -> reviewRule.replyBadgeRule = it.value
                "replyContentRule" -> reviewRule.replyContentRule = it.value
            }
        }
        source.ruleSearch = searchRule
        source.ruleExplore = exploreRule
        source.ruleBookInfo = bookInfoRule
        source.ruleToc = tocRule
        source.ruleContent = contentRule
        source.ruleReview = reviewRule.takeIf {
            source.ruleReview != null || it.enabled || reviewEntities.any { entity ->
                !entity.value.isNullOrBlank()
            }
        }
        return source
    }

    private fun alertGroups() {
        lifecycleScope.launch {
            val groups = withContext(IO) {
                appDb.bookSourceDao.allGroups()
            }
            selector(groups) { _, s, _ ->
                sendText(s)
            }
        }
    }

    override fun helpActions(): List<SelectItem<String>> {
        val helpActions = arrayListOf(
            SelectItem("插入URL参数", "urlOption"),
            SelectItem("书源教程", "ruleHelp"),
            SelectItem("js教程", "jsHelp"),
            SelectItem("正则教程", "regexHelp"),
        )
        val view = window.decorView.findFocus()
        if (view is EditText) {
            when (view.getTag(R.id.tag)) {
                "bookSourceGroup" -> {
                    helpActions.add(
                        SelectItem("插入分组", "addGroup")
                    )
                }

                else -> {
                    helpActions.add(
                        SelectItem("选择文件", "selectFile")
                    )
                }
            }
        }
        return helpActions
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "addGroup" -> alertGroups()
            "urlOption" -> UrlOptionDialog(this) { sendText(it) }.show()
            "ruleHelp" -> showHelp("ruleHelp")
            "jsHelp" -> showHelp("jsHelp")
            "regexHelp" -> showHelp("regexHelp")
            "selectFile" -> selectDoc.launch {
                mode = HandleFileContract.FILE
            }
        }
    }

    override fun sendText(text: String) {
        val view = window.decorView.findFocus()
        if (view is EditText) {
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
            if (adapter.editEntityMaxLine >= 999) {
                view.post {
                    val editTextLocation = IntArray(2)
                    view.getLocationOnScreen(editTextLocation)
                    val recyclerViewLocation = IntArray(2)
                    binding.recyclerView.getLocationOnScreen(recyclerViewLocation)
                    val layout = view.layout
                    if (layout != null) {
                        val line = layout.getLineForOffset(end)
                        val cursorYInEditText = layout.getLineTop(line)
                        // 光标相对于屏幕的位置
                        val cursorYOnScreen = editTextLocation[1] + cursorYInEditText
                        // 光标相对于RecyclerView的位置
                        val cursorYInRecyclerView = cursorYOnScreen - recyclerViewLocation[1]
                        val recyclerViewBottom = binding.recyclerView.height - 120 //考虑键盘的经验值
                        // 如果光标不在可见范围内，则滚动到光标位置
                        if (cursorYInRecyclerView !in 0..recyclerViewBottom) {
                            val scrollDistance = cursorYInRecyclerView - recyclerViewBottom / 3
                            if (scrollDistance > 0 && binding.recyclerView.canScrollVertically(1) || scrollDistance < 0 && binding.recyclerView.canScrollVertically(-1)) {
                                binding.recyclerView.smoothScrollBy(0, scrollDistance)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setSourceVariable() {
        viewModel.save(getSource()) { source ->
            lifecycleScope.launch {
                val comment =
                    source.getDisplayVariableComment("源变量可在js中通过source.getVariable()获取")
                val variable = withContext(IO) { source.getVariable() }
                showDialogFragment(
                    VariableDialog(
                        getString(R.string.set_source_variable),
                        source.getKey(),
                        variable,
                        comment
                    )
                )
            }
        }
    }

    override fun setVariable(key: String, variable: String?) {
        viewModel.bookSource?.setVariable(variable)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onUndoClicked() {
        val editText = window.decorView.findFocus()
        if (editText is EditText) {
            editText.onTextContextMenuItem(android.R.id.undo)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRedoClicked() {
        val editText = window.decorView.findFocus()
        if (editText is EditText) {
            editText.onTextContextMenuItem(android.R.id.redo)
        }
    }

    private companion object {
        const val STATE_PENDING_EDIT_KEY = "pendingEditKey"
        const val STATE_PENDING_EDIT_TAB = "pendingEditTab"
    }

}
