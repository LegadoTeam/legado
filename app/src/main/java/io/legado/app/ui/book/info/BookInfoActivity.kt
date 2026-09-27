package io.legado.app.ui.book.info

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.LeadingMarginSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.textclassifier.TextClassifier
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityBookInfoBinding
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.GlideImageGetter
import io.legado.app.help.TextViewTagHandler
import io.legado.app.help.WebCacheManager
import io.legado.app.help.book.addType
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.readProgress
import io.legado.app.help.book.removeType
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.getInjectionString
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebViewPool
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.isWebDavOverwriteConflict
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.AutoTask
import io.legado.app.model.BookCover
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.autoTask.AutoTaskEditActivity
import io.legado.app.ui.autoTask.ImportAutoTaskDialog
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.edit.BookInfoEditActivity
import io.legado.app.ui.book.manga.ReadMangaActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.text.ScrollTextView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.observeEvent
import io.legado.app.utils.openFileUri
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setHtml
import io.legado.app.utils.setMarkdown
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class BookInfoActivity :
    VMBaseActivity<ActivityBookInfoBinding, BookInfoViewModel>(toolBarTheme = Theme.Dark, showOpenMenuIcon = false),
    GroupSelectDialog.CallBack,
    ChangeBookSourceDialog.CallBack,
    ChangeCoverDialog.CallBack,
    VariableDialog.Callback {

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let {
            readFromChapter(
                index = it[0] as Int,
                pos = it[1] as Int,
                changed = it[2] as Boolean,
                volumeIndex = it[3] as Int,
                chapterInVolumeIndex = it[4] as Int,
                highlightLayoutTitleLength =
                    (it[TocActivityResult.HIGHLIGHT_LAYOUT_TITLE_LENGTH_INDEX] as Int)
                        .takeUnless { titleLength ->
                            titleLength == TocActivityResult.NO_HIGHLIGHT_LAYOUT_TITLE_LENGTH
                        },
                highlightAnchorText =
                    (it[TocActivityResult.HIGHLIGHT_ANCHOR_TEXT_INDEX] as String)
                        .takeIf(String::isNotEmpty),
            )
        } ?: let {
            if (!viewModel.inBookshelf) {
                viewModel.delBook() //进目录会保存book，此时退出目录触发的book删除，不通知书源回调
            }
        }
    }
    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
        }
    }
    private val readBookResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.upBook(intent)
        when (it.resultCode) {
            RESULT_OK -> {
                viewModel.inBookshelf = true
                upTvBookshelf()
            }

            RESULT_DELETED -> {
                setResult(RESULT_OK)
                finish()
            }
        }
    }
    private val infoEditResult = registerForActivityResult(
        StartActivityContract(BookInfoEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_OK) {
            viewModel.upEditBook()
        }
    }
    private val editSourceResult = registerForActivityResult(
        StartActivityContract(BookSourceEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_CANCELED) {
            return@registerForActivityResult
        }
        book?.let { book ->
            viewModel.bookSource = appDb.bookSourceDao.getBookSource(book.origin)?.also { source ->
                viewModel.hasCustomBtn = source.customButton
            }
            viewModel.refreshBook(book)
        }
    }
    private var chapterChanged = false
    private val waitDialog by lazy { WaitDialog(this) }
    private var editMenuItem: MenuItem? = null
    private var menuCustomBtn: MenuItem? = null
    private val book get() = viewModel.getBook(false)

    override val binding by viewBinding(ActivityBookInfoBinding::inflate)
    override val viewModel by viewModels<BookInfoViewModel>()
    private var isIntroTextViewAttached = false
    private var introContent: String? = null
    private var introExpanded = false
    private var introCanCollapse = false
    private var introRenderGeneration = 0
    private var introRenderJob: Job? = null
    private val introTextViewDelegate = lazy {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_book_intro, binding.tvIntroContainer, false) as ScrollTextView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            view.revealOnFocusHint = false
        }
        view.onTextLayoutChanged = {
            updateIntroOverflow()
        }
        view
    }
    private val introTextView by introTextViewDelegate

    private var pooledWebView: PooledWebView? = null

    private val imgAvailableWidth by lazy {
        val textView = introTextView
        textView.width - textView.paddingLeft - textView.paddingRight - 8.dpToPx()  //8是为了文字对齐额外的右边距
    }
    private var initGetter = false
    private val glideImageGetter by lazy {
        initGetter = true
        GlideImageGetter(
            this,
            introTextView,
            lifecycle,
            imgAvailableWidth,
            viewModel.bookSource?.bookSourceUrl
        )
    }

    private val textViewTagHandler by lazy {
        TextViewTagHandler(object : TextViewTagHandler.OnButtonClickListener {
            override fun onButtonClick(name: String, click: String) {
                viewModel.onButtonClick(this@BookInfoActivity, "info button $name" , click)
            }
        })
    }

    @SuppressLint("PrivateResource")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setBackgroundResource(R.color.transparent)
        binding.refreshLayout?.setColorSchemeColors(accentColor)
        binding.refreshProgressBar.secondColor = accentColor
        binding.arcView?.setBgColor(backgroundColor)
        binding.llInfo.setBackgroundColor(backgroundColor)
        binding.ivCoverC.setCardBackgroundColor(backgroundColor)
        binding.flAction.setBackgroundColor(bottomBackground)
        binding.vwBg.applyNavigationBarPadding()
        binding.tvShelf.setTextColor(getPrimaryTextColor(ColorUtils.isColorLight(bottomBackground)))
        binding.tvToc.text = getString(R.string.toc_s, getString(R.string.loading))
        viewModel.bookData.observe(this) { showBook(it) }
        viewModel.chapterListData.observe(this) {
            upLoading(viewModel.loadingData.value == true, it)
        }
        viewModel.loadingData.observe(this) { isLoading ->
            binding.refreshProgressBar.isAutoLoading = isLoading
            if (isLoading) {
                upLoading(true)
            } else {
                viewModel.chapterListData.value?.let { upLoading(false, it) }
            }
        }
        viewModel.waitDialogData.observe(this) { upWaitDialogStatus(it) }
        viewModel.initData(intent)
        initViewEvent()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_info, menu)
        editMenuItem = menu.findItem(R.id.menu_edit)
        menuCustomBtn = menu.findItem(R.id.menu_custom_btn).also {
            it.isVisible = viewModel.hasCustomBtn
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_can_update)?.isChecked =
            viewModel.bookData.value?.canUpdate ?: true
        menu.findItem(R.id.menu_split_long_chapter)?.isChecked =
            viewModel.bookData.value?.getSplitLongChapter() ?: true
        menu.findItem(R.id.menu_login)?.isVisible =
            viewModel.bookSource?.hasLogin() == true
        menu.findItem(R.id.menu_set_source_variable)?.isVisible =
            viewModel.bookSource != null
        menu.findItem(R.id.menu_set_book_variable)?.isVisible =
            viewModel.bookSource != null
        menu.findItem(R.id.menu_can_update)?.isVisible =
            viewModel.bookSource != null
        menu.findItem(R.id.menu_split_long_chapter)?.isVisible =
            viewModel.bookData.value?.isLocalTxt ?: false
        menu.findItem(R.id.menu_upload)?.isVisible =
            viewModel.bookData.value?.isLocal ?: false
        menu.findItem(R.id.menu_create_book_update_task)?.isVisible =
            viewModel.bookData.value?.let {
                viewModel.inBookshelf &&
                    viewModel.bookSource != null &&
                    !it.isLocal &&
                    it.canUpdate
            } == true
        menu.findItem(R.id.menu_delete_alert)?.isChecked =
            LocalConfig.bookInfoDeleteAlert
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_custom_btn -> {
                viewModel.bookSource?.customButton?.let {
                    viewModel.getBook()?.let { book ->
                        SourceCallBack.callBackBtn(
                            this,
                            SourceCallBack.CLICK_CUSTOM_BUTTON,
                            viewModel.bookSource,
                            book,
                            null
                        )
                    }
                }
            }

            R.id.menu_edit -> {
                viewModel.getBook()?.let {
                    infoEditResult.launch {
                        putExtra("bookUrl", it.bookUrl)
                    }
                }
            }

            R.id.menu_share_it -> {
                viewModel.getBook()?.let {
                    val bookJson = GSON.toJson(it)
                    val shareStr = "${it.bookUrl}#$bookJson"
                    SourceCallBack.callBackBtn(
                        this,
                        SourceCallBack.CLICK_SHARE_BOOK,
                        viewModel.bookSource,
                        it,
                        null,
                        result = shareStr
                    ) {
                        val intent = Intent(Intent.ACTION_SEND)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        intent.putExtra(Intent.EXTRA_TEXT, shareStr)
                        intent.type = "text/plain"
                        startActivity(Intent.createChooser(intent, it.name))
                    }
                }
            }

            R.id.menu_refresh -> {
                refreshBook()
            }

            R.id.menu_create_book_update_task -> viewModel.getBook()?.let {
                openBookUpdateTask(it)
            }

            R.id.menu_login -> viewModel.bookSource?.let {
                startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", it.bookSourceUrl)
                    putExtra("bookUrl", book?.bookUrl)
                }
            }

            R.id.menu_top -> viewModel.topBook()
            R.id.menu_set_source_variable -> setSourceVariable()
            R.id.menu_set_book_variable -> setBookVariable()
            R.id.menu_copy_book_url -> viewModel.getBook()?.let {
                SourceCallBack.callBackBtn(
                    this,
                    SourceCallBack.CLICK_COPY_BOOK_URL,
                    viewModel.bookSource,
                    it,
                    null,
                    result = it.bookUrl
                ) {
                    sendToClip(it.bookUrl)
                }
            }

            R.id.menu_copy_toc_url -> viewModel.getBook()?.let {
                SourceCallBack.callBackBtn(
                    this,
                    SourceCallBack.CLICK_COPY_TOC_URL,
                    viewModel.bookSource,
                    it,
                    null,
                    result = it.tocUrl
                ) {
                    sendToClip(it.tocUrl)
                }
            }

            R.id.menu_can_update -> {
                viewModel.getBook()?.let {
                    it.canUpdate = !it.canUpdate
                    if (viewModel.inBookshelf) {
                        if (!it.canUpdate) {
                            it.removeType(BookType.updateError)
                        }
                        viewModel.saveBook(it)
                    }
                }
            }

            R.id.menu_clear_cache -> viewModel.getBook()?.let {
                    SourceCallBack.callBackBtn(this, SourceCallBack.CLICK_CLEAR_CACHE, viewModel.bookSource, it, null) {
                        viewModel.clearCache(it)
                    }
                }
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_split_long_chapter -> {
                upLoading(true)
                viewModel.getBook()?.let {
                    it.setSplitLongChapter(!item.isChecked)
                    viewModel.loadBookInfo(it, false)
                }
                item.isChecked = !item.isChecked
                if (!item.isChecked) longToastOnUi(R.string.need_more_time_load_content)
            }

            R.id.menu_delete_alert -> LocalConfig.bookInfoDeleteAlert = !item.isChecked
            R.id.menu_upload -> {
                viewModel.getBook()?.let { confirmAndUploadBook(it) }
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun observeLiveBus() {
        viewModel.actionLive.observe(this) {
            when (it) {
                "selectBooksDir" -> localBookTreeSelect.launch {
                    title = getString(R.string.select_book_folder)
                }
            }
        }

        observeEvent<Boolean>(EventBus.REFRESH_BOOK_INFO) { //书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshBook()
            }
        }

        observeEvent<Boolean>(EventBus.REFRESH_BOOK_TOC) { //书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshToc()
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isIntroTextViewAttached && ev.action == MotionEvent.ACTION_DOWN) {
            currentFocus?.let {
                if (it === introTextView && introTextView.hasSelection()) {
                    it.clearFocus()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun refreshBook() {
        upLoading(true)
        viewModel.getBook()?.let {
            viewModel.refreshBook(it)
        }
    }

    private fun openBookUpdateTask(book: Book) {
        val task = AutoTask.buildBookUpdateTask(
            book = book,
            name = getString(R.string.auto_task_book_update_name, book.name)
        )
        lifecycleScope.launch {
            val existingTask = withContext(IO) {
                AutoTask.findBookUpdateTask(AutoTask.all(), book)
            }
            if (existingTask != null) {
                startActivity(AutoTaskEditActivity.intent(this@BookInfoActivity, existingTask.id))
            } else {
                showDialogFragment(ImportAutoTaskDialog(GSON.toJson(task)))
            }
        }
    }

    private fun refreshToc() {
        upLoading(true)
        viewModel.getBook()?.let {
            viewModel.loadChapter(it, true, isFromBookInfo = true)
        }
    }

    private fun upLoadBook(
        book: Book,
        bookWebDav: RemoteBookWebDav? = AppWebDav.defaultBookWebDav,
        overwrite: Boolean = true,
        onConflict: (() -> Unit)? = null,
        onFinished: () -> Unit = {},
    ) {
        lifecycleScope.launch {
            waitDialog.setText(R.string.loading)
            waitDialog.show()
            var uploadConflict = false
            try {
                bookWebDav
                    ?.upload(book, overwrite)
                    ?: throw NoStackTraceException(getString(R.string.webdav_not_configured))
                //更新书籍最后更新时间,使之比远程书籍的时间新
                book.lastCheckTime = System.currentTimeMillis()
                viewModel.saveBook(book)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (!overwrite &&
                    e is WebDavException &&
                    isWebDavOverwriteConflict(e.responseCode)
                ) {
                    uploadConflict = true
                } else {
                    toastOnUi(e.localizedMessage)
                }
            } finally {
                waitDialog.dismiss()
            }
            if (uploadConflict) {
                onConflict?.invoke() ?: onFinished()
            } else {
                onFinished()
            }
        }
    }

    private fun confirmAndUploadBook(
        book: Book,
        onFinished: () -> Unit = {},
    ) {
        val bookWebDav = AppWebDav.defaultBookWebDav
        if (bookWebDav == null) {
            toastOnUi(R.string.webdav_not_configured)
            onFinished()
            return
        }
        lifecycleScope.launch {
            waitDialog.setText(R.string.loading)
            waitDialog.show()
            val remoteExists = try {
                withContext(IO) { bookWebDav.hasRemoteBook(book) }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                toastOnUi(e.localizedMessage)
                onFinished()
                return@launch
            } finally {
                waitDialog.dismiss()
            }
            if (!remoteExists) {
                upLoadBook(
                    book = book,
                    bookWebDav = bookWebDav,
                    overwrite = false,
                    onConflict = {
                        showUploadOverwriteConfirm(book, bookWebDav, onFinished)
                    },
                    onFinished = onFinished,
                )
                return@launch
            }
            showUploadOverwriteConfirm(book, bookWebDav, onFinished)
        }
    }

    private fun showUploadOverwriteConfirm(
        book: Book,
        bookWebDav: RemoteBookWebDav,
        onFinished: () -> Unit,
    ) {
        alert(R.string.draw, R.string.webdav_book_exists_confirm) {
            yesButton {
                upLoadBook(
                    book = book,
                    bookWebDav = bookWebDav,
                    overwrite = true,
                    onFinished = onFinished,
                )
            }
            noButton { onFinished() }
            onCancelled { onFinished() }
        }
    }

    private fun showBook(book: Book) = binding.run {
        showCover(book)
        tvName.text = book.name
        tvAuthor.text = getString(R.string.author_show, book.getRealAuthor())
        tvOrigin.text = getString(R.string.origin_show, book.originName)
        tvLasted.text = getString(R.string.lasted_show, book.latestChapterTitle)
        showBookIntro(book)
        if (book.isWebFile) {
            llToc.gone()
            tvLasted.text = getString(R.string.lasted_show, "下载中...")
        } else {
            llToc.visible()
        }
        menuCustomBtn?.isVisible = viewModel.hasCustomBtn
        upTvBookshelf()
        upKinds(book)
        upGroup(book.group)
    }

    inner class CustomWebViewClient : WebViewClient() {
        private val jsStr = getInjectionString
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            request?.let {
                val uri = it.url
                return when (uri.scheme) {
                    "http", "https" -> false
                    "legado", "yuedu" -> {
                        startActivity<OnLineImportActivity> {
                            data = uri
                        }
                        true
                    }

                    else -> {
                        binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                            openUrl(uri)
                        }
                        true
                    }
                }
            }
            return true
        }
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            view?.evaluateJavascript(jsStr, null)
        }
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            view?.post {
                binding.tvIntroContainer.requestLayout()
            }
        }
    }

    private fun showBookIntro(book: Book) {
        val intro = book.getDisplayIntro()
        if (intro.isNullOrBlank()) {
            introContent = null
            introExpanded = false
            introCanCollapse = false
            introRenderGeneration++
            introRenderJob?.cancel()
            destroyWeb()
            binding.tvIntroContainer.removeAllViews()
            isIntroTextViewAttached = false
            binding.tvIntroContainer.gone()
            updateIntroToggle()
            return
        }
        if (intro != introContent) {
            introContent = intro
            introExpanded = true
            introCanCollapse = false
        }
        val renderGeneration = ++introRenderGeneration
        introRenderJob?.cancel()
        binding.tvIntroContainer.visible()
        if (intro.startsWith("<useweb>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 8) {
                val tvIntro = prepareIntroTextView()
                tvIntro.text = intro
                scheduleIntroOverflowCheck(renderGeneration)
                return
            }
            introCanCollapse = false
            updateIntroToggle()
            val html = intro.substring(8, lastIndex)
            val pooledWebView = this.pooledWebView ?: let{
                val pooledWebView = WebViewPool.acquire(this)
                val webView = pooledWebView.realWebView
                webView.onResume()
                webView.webViewClient = CustomWebViewClient()
                webView.addJavascriptInterface(WebCacheManager, nameCache)
                viewModel.bookSource?.let {
                    webView.addJavascriptInterface(it as BaseSource, nameSource)
                    val webJsExtensions = WebJsExtensions(it, null, webView)
                    webView.addJavascriptInterface(webJsExtensions, nameJava)
                }
                pooledWebView
            }
            val webView = pooledWebView.realWebView
            if (isIntroTextViewAttached || this.pooledWebView == null) {
                isIntroTextViewAttached = false
                this.pooledWebView = pooledWebView
                binding.tvIntroContainer.removeAllViews()
                binding.tvIntroContainer.addView(webView)
            }
            val bookUrl = viewModel.getBook()?.bookUrl
                ?.takeIf { it.startsWith("http", true) }
                ?.substringBefore(",")
            webView.loadDataWithBaseURL(bookUrl, html, "text/html", "utf-8", bookUrl)
            return
        }
        val tvIntro = prepareIntroTextView()
        if (intro.startsWith("<usehtml>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 9) {
                tvIntro.text = intro
                scheduleIntroOverflowCheck(renderGeneration)
                return
            }
            val html = intro.substring(9, lastIndex)
            tvIntro.setHtml(
                html,
                glideImageGetter,
                textViewTagHandler,
                imgOnLongClickListener = {
                    showDialogFragment(PhotoDialog(it, viewModel.bookSource?.bookSourceUrl))
                },
                imgOnClickListener = {
                    viewModel.onButtonClick(this@BookInfoActivity, "info image" , it)
                }
            )
            scheduleIntroOverflowCheck(renderGeneration)
        } else if (intro.startsWith("<md>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 4) {
                tvIntro.text = intro
                scheduleIntroOverflowCheck(renderGeneration)
                return
            }
            val mark = intro.substring(4, lastIndex)
            tvIntro.text = null
            introRenderJob = lifecycleScope.launch {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tvIntro.setTextClassifier(TextClassifier.NO_OP)
                }
                val context = this@BookInfoActivity
                val markwon: Markwon
                val markdown = withContext(IO) {
                    markwon = Markwon.builder(context)
                        .usePlugin(
                            GlideImagesPlugin.create(
                                Glide.with(context)
                                    .applyDefaultRequestOptions(
                                        RequestOptions()
                                            .override(imgAvailableWidth)
                                            .encodeQuality(88)
                                    )
                            )
                        )
                        .usePlugin(HtmlPlugin.create())
                        .usePlugin(TablePlugin.create(context))
                        .build()
                    markwon.toMarkdown(mark)
                }
                if (renderGeneration != introRenderGeneration) return@launch
                tvIntro.setMarkdown(
                    markwon,
                    markdown,
                    imgOnLongClickListener = { source ->
                        showDialogFragment(PhotoDialog(source, viewModel.bookSource?.bookSourceUrl))
                    }
                )
                scheduleIntroOverflowCheck(renderGeneration)
            }
        } else {
            setPlainBookIntro(tvIntro, intro)
            scheduleIntroOverflowCheck(renderGeneration)
        }
    }

    private fun setPlainBookIntro(textView: ScrollTextView, intro: String) {
        val ranges = introIndentRanges(intro)
        if (ranges.isEmpty()) {
            textView.text = intro
            return
        }
        val indentWidth = textView.paint.measureText("　　").roundToInt().coerceAtLeast(1)
        textView.text = SpannableString(intro).apply {
            ranges.forEach { range ->
                setSpan(
                    LeadingMarginSpan.Standard(indentWidth, 0),
                    range.start,
                    range.endExclusive,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    private fun prepareIntroTextView(): ScrollTextView {
        val tvIntro = introTextView
        if (!isIntroTextViewAttached || pooledWebView != null) {
            destroyWeb()
            binding.tvIntroContainer.removeAllViews()
            tvIntro.text = null
            binding.tvIntroContainer.addView(tvIntro)
            isIntroTextViewAttached = true
        }
        applyIntroCollapseState(tvIntro)
        return tvIntro
    }

    private fun applyIntroCollapseState(tvIntro: ScrollTextView = introTextView) {
        tvIntro.internalScrollEnabled = false
        tvIntro.maxLines = if (introExpanded) Int.MAX_VALUE else BookIntroCollapse.COLLAPSED_LINES
        tvIntro.maxMeasuredHeight = if (introExpanded) {
            null
        } else {
            collapsedIntroHeight(tvIntro)
        }
        tvIntro.ellipsize = if (introExpanded) null else TextUtils.TruncateAt.END
        if (!introExpanded) {
            tvIntro.scrollTo(0, 0)
        }
        updateIntroToggle()
        tvIntro.requestLayout()
    }

    private fun collapsedIntroHeight(tvIntro: ScrollTextView): Int =
        tvIntro.lineHeight * BookIntroCollapse.COLLAPSED_LINES +
            tvIntro.compoundPaddingTop + tvIntro.compoundPaddingBottom

    private fun scheduleIntroOverflowCheck(renderGeneration: Int) {
        introTextView.post {
            if (renderGeneration == introRenderGeneration &&
                lifecycle.currentState != Lifecycle.State.DESTROYED
            ) {
                updateIntroOverflow()
            }
        }
    }

    private fun updateIntroOverflow() {
        if (!isIntroTextViewAttached || pooledWebView != null) return
        val tvIntro = introTextView
        val textLayout = tvIntro.layout ?: return
        val lineCount = textLayout.lineCount
        if (lineCount <= 0) return
        val lastLine = lineCount - 1
        val collapsedHeight = collapsedIntroHeight(tvIntro)
        val canCollapse = tvIntro.isMeasuredHeightLimited || BookIntroCollapse.hasOverflow(
            expanded = introExpanded,
            lineCount = lineCount,
            lastLineEllipsisCount = textLayout.getEllipsisCount(lastLine),
            lastLineEnd = textLayout.getLineEnd(lastLine),
            textLength = tvIntro.text.length,
            contentHeight = textLayout.height +
                tvIntro.compoundPaddingTop + tvIntro.compoundPaddingBottom,
            collapsedContentHeight = collapsedHeight,
        )
        if (introCanCollapse != canCollapse) {
            introCanCollapse = canCollapse
            updateIntroToggle()
        }
    }

    private fun updateIntroToggle() = binding.tvIntroToggle.run {
        if (!introCanCollapse || !isIntroTextViewAttached || pooledWebView != null) {
            gone()
            return@run
        }
        setText(
            if (introExpanded) {
                R.string.book_intro_collapse
            } else {
                R.string.book_intro_expand
            }
        )
        visible()
    }

    private fun upKinds(book: Book) = binding.run {
        lifecycleScope.launch {
            var kinds = book.getKindList()
            if (book.isLocal) {
                withContext(IO) {
                    val size = try {
                        FileDoc.fromUri(book.getLocalUri(), false).size
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        0L
                    }
                    if (size > 0) {
                        kinds = kinds.toMutableList()
                        kinds.add(ConvertUtils.formatFileSize(size))
                    }
                }
            }
            if (kinds.isEmpty()) {
                lbKind.gone()
            } else {
                lbKind.visible()
                val source = viewModel.bookSource
                if (source == null) {
                    lbKind.setLabels(kinds)
                    return@launch
                }
                lbKind.setLabels(
                    kinds,
                    { kind ->
                        SourceCallBack.callBackBtn(
                            this@BookInfoActivity,
                            SourceCallBack.CLICK_BOOK_LABEL,
                            source,
                            book,
                            null,
                            result = kind
                        ) {
                            SearchActivity.start(this@BookInfoActivity, source, kind)
                        }
                    },
                    { kind ->
                        SourceCallBack.callBackBtn(
                            this@BookInfoActivity,
                            SourceCallBack.LONG_CLICK_BOOK_LABEL,
                            source,
                            book,
                            null,
                            result = kind
                        )
                        true
                    }
                )
            }
        }
    }

    private fun showCover(book: Book) {
        binding.ivCover.load(book, false) {
            if (!AppConfig.isEInkMode) {
                BookCover.loadBlur(
                    this,
                    book.getDisplayCover(),
                    false,
                    book.getCoverSourceOrigin()
                )
                    .into(binding.bgBook)
            }
        }
    }

    private fun upLoading(isLoading: Boolean, chapterList: List<BookChapter>? = null) {
        when {
            isLoading -> {
                binding.tvToc.text = getString(R.string.toc_s, getString(R.string.loading))
            }

            chapterList.isNullOrEmpty() -> {
                binding.tvToc.text = getString(
                    R.string.toc_s,
                    getString(R.string.error_load_toc)
                )
                binding.tvLasted.text = getString(R.string.lasted_show, book?.latestChapterTitle)
            }

            else -> {
                book?.let {
                    val tocTitle = resolveBookInfoTocTitle(
                        it.durChapterTitle,
                        it.durChapterIndex,
                        chapterList,
                    ) ?: getString(R.string.no_last_chapter)
                    val readStatus = resolveBookInfoReadProgress(it)?.let { percent ->
                        getString(R.string.read_y, "$percent%")
                    }
                    binding.tvToc.text = getString(
                        R.string.toc_s,
                        listOfNotNull(tocTitle, readStatus).joinToString("  ·  "),
                    )
                    binding.tvLasted.text = getString(R.string.lasted_show, it.latestChapterTitle)
                }
            }
        }
    }

    private fun upTvBookshelf() {
        if (viewModel.inBookshelf) {
            binding.tvShelf.text = getString(R.string.remove_from_bookshelf)
        } else {
            binding.tvShelf.text = getString(R.string.add_to_bookshelf)
        }
        editMenuItem?.isVisible = viewModel.inBookshelf
    }

    private fun upGroup(groupId: Long) {
        viewModel.loadGroup(groupId) {
            if (it.isNullOrEmpty()) {
                binding.tvGroup.text = if (book?.isLocal == true) {
                    getString(R.string.group_s, getString(R.string.local_no_group))
                } else {
                    getString(R.string.group_s, getString(R.string.no_group))
                }
            } else {
                binding.tvGroup.text = getString(R.string.group_s, it)
            }
        }
    }

    private fun initViewEvent() = binding.run {
        tvIntroToggle.setOnClickListener {
            if (!introCanCollapse || !isIntroTextViewAttached) return@setOnClickListener
            introExpanded = !introExpanded
            applyIntroCollapseState()
            scheduleIntroOverflowCheck(introRenderGeneration)
        }
        ivCover.setOnClickListener {
            viewModel.getBook()?.let {
                showDialogFragment(
                    ChangeCoverDialog(it.name, it.author)
                )
            }
        }
        ivCover.setOnLongClickListener {
            viewModel.getBook()?.getDisplayCover()?.let { path ->
                showDialogFragment(PhotoDialog(path, isBook = true))
            }
            true
        }
        tvRead.setOnClickListener {
            viewModel.getBook()?.let { book ->
                if (book.isWebFile) {
                    showWebFileDownloadAlert {
                        readBook(it)
                    }
                } else {
                    readBook(book)
                }
            }
        }
        tvShelf.setOnClickListener {
            viewModel.getBook()?.let { book ->
                if (viewModel.inBookshelf) {
                    deleteBook()
                } else {
                    if (book.isWebFile) {
                        showWebFileDownloadAlert()
                    } else {
                        viewModel.addToBookshelf {
                            upTvBookshelf()
                        }
                    }
                }
            }
        }
        tvOrigin.setOnClickListener {
            viewModel.getBook()?.let { book ->
                if (book.isLocal) return@let
                if (!appDb.bookSourceDao.has(book.origin)) {
                    toastOnUi(R.string.error_no_source)
                    return@let
                }
                editSourceResult.launch {
                    putExtra("sourceUrl", book.origin)
                }
            }
        }
        tvChangeSource.setOnClickListener {
            viewModel.getBook()?.let { book ->
                showDialogFragment(ChangeBookSourceDialog(book.name, book.author))
            }
        }
        tvTocView.setOnClickListener {
            if (viewModel.chapterListData.value.isNullOrEmpty()) {
                toastOnUi(R.string.chapter_list_empty)
                return@setOnClickListener
            }
            val book = viewModel.getBook(false)
            if (book == null) {
                toastOnUi(R.string.book_not_exist)
                return@setOnClickListener
            }
            if (!viewModel.inBookshelf) {
                viewModel.saveBook(book) { //点击目录会保存book
                    viewModel.saveChapterList {
                        openChapterList()
                    }
                }
            } else {
                openChapterList()
            }
        }
        tvChangeGroup.setOnClickListener {
            viewModel.getBook()?.let {
                showDialogFragment(
                    GroupSelectDialog(it.group)
                )
            }
        }
        tvAuthor.setOnClickListener {
            viewModel.getBook(false)?.let { book ->
                SourceCallBack.callBackBtn(
                    this@BookInfoActivity,
                    SourceCallBack.CLICK_AUTHOR,
                    viewModel.bookSource,
                    book,
                    null,
                    result = book.author
                ) {
                    SearchActivity.start(this@BookInfoActivity, book.author)
                }
            }
        }
        tvAuthor.setOnLongClickListener {
            viewModel.getBook(false)?.let { book ->
                SourceCallBack.callBackBtn(
                    this@BookInfoActivity,
                    SourceCallBack.LONG_CLICK_AUTHOR,
                    viewModel.bookSource,
                    book,
                    null,
                    result = book.author
                ) {
                    SearchActivity.start(this@BookInfoActivity, book.author)
                }
            }
            true
        }
        tvName.setOnClickListener {
            viewModel.getBook(false)?.let { book ->
                SourceCallBack.callBackBtn(
                    this@BookInfoActivity,
                    SourceCallBack.CLICK_BOOK_NAME,
                    viewModel.bookSource,
                    book,
                    null,
                    result = book.name
                ) {
                    SearchActivity.start(this@BookInfoActivity, book.name)
                }
            }
        }
        tvName.setOnLongClickListener {
            viewModel.getBook(false)?.let { book ->
                SourceCallBack.callBackBtn(
                    this@BookInfoActivity,
                    SourceCallBack.LONG_CLICK_BOOK_NAME,
                    viewModel.bookSource,
                    book,
                    null,
                    result = book.name
                ) {
                    SearchActivity.start(this@BookInfoActivity, book.name)
                }
            }
            true
        }
        refreshLayout?.setOnRefreshListener {
            refreshLayout.isRefreshing = false
            refreshBook()
        }
    }

    private fun setSourceVariable() {
        lifecycleScope.launch {
            val source = viewModel.bookSource
            if (source == null) {
                toastOnUi("书源不存在")
                return@launch
            }
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

    private fun setBookVariable() {
        lifecycleScope.launch {
            val source = viewModel.bookSource
            if (source == null) {
                toastOnUi("书源不存在")
                return@launch
            }
            val book = viewModel.getBook() ?: return@launch
            val variable = withContext(IO) { book.getCustomVariable() }
            val comment = source.getDisplayVariableComment(
                """书籍变量可在js中通过book.getVariable("custom")获取"""
            )
            showDialogFragment(
                VariableDialog(
                    getString(R.string.set_book_variable),
                    book.bookUrl,
                    variable,
                    comment
                )
            )
        }
    }

    override fun setVariable(key: String, variable: String?) {
        when (key) {
            viewModel.bookSource?.getKey() -> viewModel.bookSource?.setVariable(variable)
            viewModel.bookData.value?.bookUrl -> viewModel.bookData.value?.let {
                it.putCustomVariable(variable)
                if (viewModel.inBookshelf) {
                    viewModel.saveBook(it)
                }
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun deleteBook() {
        viewModel.getBook()?.let { book ->
            if (LocalConfig.bookInfoDeleteAlert) {
                alert(
                    titleResource = R.string.draw,
                    messageResource = R.string.sure_del
                ) {
                    var deleteOriginalCheckBox: CheckBox? = null
                    var deleteRemoteCheckBox: CheckBox? = null
                    if (book.isLocal) {
                        deleteOriginalCheckBox = CheckBox(this@BookInfoActivity).apply {
                            setText(R.string.delete_book_file)
                            isChecked = LocalConfig.deleteBookOriginal
                        }
                        val view = LinearLayout(this@BookInfoActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                            addView(deleteOriginalCheckBox)
                            if (AppWebDav.defaultBookWebDav != null) {
                                deleteRemoteCheckBox = CheckBox(this@BookInfoActivity).apply {
                                    setText(R.string.delete_webdav_book_file)
                                }
                                addView(deleteRemoteCheckBox)
                            }
                        }
                        customView { view }
                    }
                    yesButton {
                        if (deleteOriginalCheckBox != null) {
                            LocalConfig.deleteBookOriginal = deleteOriginalCheckBox.isChecked
                        }
                        deleteBook(
                            book = book,
                            deleteOriginal = LocalConfig.deleteBookOriginal,
                            deleteRemote = deleteRemoteCheckBox?.isChecked == true,
                        )
                    }
                    noButton()
                }
            } else {
                deleteBook(book, LocalConfig.deleteBookOriginal, deleteRemote = false)
            }
        }
    }

    private fun deleteBook(book: Book, deleteOriginal: Boolean, deleteRemote: Boolean) {
        if (!deleteRemote) {
            finishDeleteBook(book, deleteOriginal)
            return
        }
        val bookWebDav = AppWebDav.defaultBookWebDav
        if (bookWebDav == null) {
            toastOnUi(R.string.webdav_not_configured)
            return
        }
        lifecycleScope.launch {
            waitDialog.setText(R.string.loading)
            waitDialog.show()
            val deleted = try {
                withContext(IO) { bookWebDav.delete(book) }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                toastOnUi(e.localizedMessage ?: getString(R.string.delete_webdav_book_file_fail))
                return@launch
            } finally {
                waitDialog.dismiss()
            }
            if (!deleted) {
                toastOnUi(R.string.delete_webdav_book_file_fail)
                return@launch
            }
            finishDeleteBook(book, deleteOriginal)
        }
    }

    private fun finishDeleteBook(book: Book, deleteOriginal: Boolean) {
        SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, viewModel.bookSource, book)
        viewModel.delBook(deleteOriginal) {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun openChapterList() {
        viewModel.getBook()?.let {
            tocActivityResult.launch(it.bookUrl)
        }
    }

    private fun readFromChapter(
        index: Int,
        pos: Int,
        changed: Boolean,
        volumeIndex: Int,
        chapterInVolumeIndex: Int,
        highlightLayoutTitleLength: Int?,
        highlightAnchorText: String?,
    ) {
        viewModel.getBook(false)?.let { book ->
            val deferHighlightPosition = highlightLayoutTitleLength != null &&
                !book.isAudio && !book.isVideo &&
                (book.isLocal || !book.isImage || !AppConfig.showMangaUi)
            if (!deferHighlightPosition) {
                book.durChapterIndex = index
                book.durChapterPos = pos
                book.durVolumeIndex = volumeIndex
                book.chapterInVolumeIndex = chapterInVolumeIndex
            }
            chapterChanged = changed
            if (!viewModel.inBookshelf) {
                book.addType(BookType.notShelf)
                lifecycleScope.launch {
                    withContext(IO) {
                        book.save()
                    }
                    viewModel.saveChapterList {
                        startReadActivity(
                            book,
                            index.takeIf { deferHighlightPosition },
                            pos.takeIf { deferHighlightPosition },
                            highlightLayoutTitleLength.takeIf { deferHighlightPosition },
                            highlightAnchorText.takeIf { deferHighlightPosition }
                        )
                    }
                }
            } else {
                lifecycleScope.launch {
                    withContext(IO) {
                        book.update()
                    }
                    startReadActivity(
                        book,
                        index.takeIf { deferHighlightPosition },
                        pos.takeIf { deferHighlightPosition },
                        highlightLayoutTitleLength.takeIf { deferHighlightPosition },
                        highlightAnchorText.takeIf { deferHighlightPosition }
                    )
                }
            }
        }
    }

    private fun showWebFileDownloadAlert(
        onClick: ((Book) -> Unit)? = null,
    ) {
        val webFiles = viewModel.webFiles
        if (webFiles.isEmpty()) {
            toastOnUi("Unexpected webFileData")
            return
        }
        val uploadCheckBox = CheckBox(this).apply {
            setText(R.string.upload_imported_book_to_webdav)
            isChecked = LocalConfig.uploadImportedBookToWebDav
            setOnCheckedChangeListener { _, isChecked ->
                LocalConfig.uploadImportedBookToWebDav = isChecked
            }
        }
        alert(titleResource = R.string.download_and_import_file) {
            items(webFiles) { _, webFile, _ ->
                val uploadToWebDav = uploadCheckBox.isChecked
                if (webFile.isSupported) {
                    /* import */
                    viewModel.importOrDownloadWebFile<Book>(webFile) {
                        onWebBookImported(it, uploadToWebDav, onClick)
                    }
                } else if (webFile.isSupportDecompress) {
                    /* 解压筛选后再选择导入项 */
                    viewModel.importOrDownloadWebFile<Uri>(webFile) { uri ->
                        viewModel.getArchiveFilesName(uri) { fileNames ->
                            if (fileNames.size == 1) {
                                viewModel.importArchiveBook(uri, fileNames[0]) {
                                    onWebBookImported(it, uploadToWebDav, onClick)
                                }
                            } else {
                                showDecompressFileImportAlert(uri, fileNames) {
                                    onWebBookImported(it, uploadToWebDav, onClick)
                                }
                            }
                        }
                    }
                } else {
                    alert(
                        title = getString(R.string.draw),
                        message = getString(R.string.file_not_supported, webFile.name)
                    ) {
                        neutralButton(R.string.open_fun) {
                            /* download only */
                            viewModel.importOrDownloadWebFile<Uri>(webFile) {
                                openFileUri(it, "*/*")
                            }
                        }
                        noButton()
                    }
                }
            }
            customView {
                LinearLayout(this@BookInfoActivity).apply {
                    setPadding(16.dpToPx(), 0, 16.dpToPx(), 8.dpToPx())
                    addView(uploadCheckBox)
                }
            }
        }
    }

    private fun onWebBookImported(
        book: Book,
        uploadToWebDav: Boolean,
        onClick: ((Book) -> Unit)?,
    ) {
        val onFinished: () -> Unit = { onClick?.invoke(book) }
        if (uploadToWebDav) {
            confirmAndUploadBook(book, onFinished)
        } else {
            onFinished()
        }
    }

    private fun showDecompressFileImportAlert(
        archiveFileUri: Uri,
        fileNames: List<String>,
        success: ((Book) -> Unit)? = null,
    ) {
        if (fileNames.isEmpty()) {
            toastOnUi(R.string.unsupport_archivefile_entry)
            return
        }
        selector(
            R.string.import_select_book,
            fileNames
        ) { _, name, _ ->
            viewModel.importArchiveBook(archiveFileUri, name) {
                success?.invoke(it)
            }
        }
    }

    private fun readBook(book: Book) {
        if (!viewModel.inBookshelf) {
            book.addType(BookType.notShelf)
            viewModel.saveBook(book) {
                viewModel.saveChapterList {
                    startReadActivity(book)
                }
            }
        } else {
            viewModel.saveBook(book) {
                startReadActivity(book)
            }
        }
    }

    private fun startReadActivity(
        book: Book,
        highlightIndex: Int? = null,
        highlightChapterPos: Int? = null,
        highlightLayoutTitleLength: Int? = null,
        highlightAnchorText: String? = null,
    ) {
        when {
            book.isAudio -> readBookResult.launch(
                Intent(this, AudioPlayActivity::class.java)
                    .putExtra("bookUrl", book.bookUrl)
                    .putExtra("inBookshelf", viewModel.inBookshelf)
            )
            book.isVideo -> readBookResult.launch(
                Intent(this, VideoPlayerActivity::class.java)
                    .putExtra("bookUrl", book.bookUrl)
                    .putExtra("inBookshelf", viewModel.inBookshelf)
            )

            else -> readBookResult.launch(
                Intent(
                    this,
                    if (!book.isLocal && book.isImage && AppConfig.showMangaUi) ReadMangaActivity::class.java
                    else ReadBookActivity::class.java
                ).apply {
                    putExtra("bookUrl", book.bookUrl)
                    putExtra("inBookshelf", viewModel.inBookshelf)
                    putExtra("chapterChanged", chapterChanged)
                    if (highlightIndex != null &&
                        highlightChapterPos != null &&
                        highlightLayoutTitleLength != null
                    ) {
                        putExtra("index", highlightIndex)
                        putExtra("chapterPos", highlightChapterPos)
                        putExtra(
                            TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH,
                            highlightLayoutTitleLength
                        )
                        highlightAnchorText?.let {
                            putExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT, it)
                        }
                    }
                }
            )
        }
    }

    override val oldBook: Book?
        get() = viewModel.bookData.value

    override fun changeTo(
        source: BookSource,
        book: Book,
        toc: List<BookChapter>,
        onSuccess: () -> Unit,
    ) {
        viewModel.changeTo(source, book, toc, onSuccess)
    }

    override fun coverChangeTo(coverUrl: String) {
        viewModel.bookData.value?.let { book ->
            book.customCoverUrl = coverUrl
            book.persistedCoverUrl = null
            showCover(book)
            if (viewModel.inBookshelf) {
                viewModel.saveBook(book, preserveCustomCoverUrl = false)
            }
        }
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        upGroup(groupId)
        viewModel.getBook()?.let { book ->
            book.group = groupId
            if (viewModel.inBookshelf) {
                viewModel.saveBook(book)
            } else if (groupId > 0) {
                viewModel.addToBookshelf {
                    upTvBookshelf()
                }
            }
        }
    }

    private fun upWaitDialogStatus(isShow: Boolean) {
        val showText = "Loading....."
        if (isShow) {
            waitDialog.run {
                setText(showText)
                show()
            }
        } else {
            waitDialog.dismiss()
        }
    }

     override fun onStart() {
         super.onStart()
         if (initGetter) {
             glideImageGetter.start()
         }
     }

     override fun onStop() {
         super.onStop()
         if (initGetter) {
             glideImageGetter.stop()
         }
     }

    override fun onDestroy() {
        introRenderJob?.cancel()
        if (introTextViewDelegate.isInitialized()) {
            introTextView.onTextLayoutChanged = null
        }
        destroyWeb()
        super.onDestroy()
        if (initGetter) {
            glideImageGetter.clear()
        }
    }

    private fun destroyWeb() {
        pooledWebView?.let { WebViewPool.release(it) }
        pooledWebView = null
    }

}

internal fun resolveBookInfoTocTitle(
    storedTitle: String?,
    currentIndex: Int,
    chapters: List<BookChapter>,
): String? {
    return storedTitle?.takeIf { it.isNotBlank() }
        ?: (chapters.getOrNull(currentIndex) ?: chapters.lastOrNull())
            ?.getDisplayTitle(chineseConvert = false)
            ?.takeIf { it.isNotBlank() }
}

internal fun resolveBookInfoReadProgress(book: Book): Int? {
    if (book.totalChapterNum <= 1) return null
    return book.readProgress()?.let { (it * 100).roundToInt() }
}
