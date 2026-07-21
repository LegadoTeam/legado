package io.legado.app.ui.book.audio

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityAudioPlayBinding
import io.legado.app.databinding.DialogDownloadChoiceBinding
import io.legado.app.help.audio.AudioCacheManager
import io.legado.app.help.audio.AudioCachePolicy
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.removeType
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.AudioPlay
import io.legado.app.model.AudioCacheKey
import io.legado.app.model.AudioCacheStateChanged
import io.legado.app.model.BookCover
import io.legado.app.service.AudioCacheService
import io.legado.app.service.AudioPlayService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.invisible
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toDurationTime
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onLongClick
import java.util.Locale
import io.legado.app.ui.book.audio.config.AudioSkipCredits
import io.legado.app.ui.widget.dialog.SleepTimerDialog
import com.dirror.lyricviewx.OnPlayClickListener
import io.legado.app.lib.theme.ThemeStore.Companion.accentColor
import io.legado.app.ui.book.audio.SliderPopup.Companion.SPEED
import io.legado.app.model.SourceCallBack
import io.legado.app.utils.gone

/**
 * 音频播放
 */
@SuppressLint("ObsoleteSdkInt")
class AudioPlayActivity :
    VMBaseActivity<ActivityAudioPlayBinding, AudioPlayViewModel>(toolBarTheme = Theme.Dark),
    ChangeBookSourceDialog.CallBack,
    AudioPlay.CallBack,
    SleepTimerDialog.CallBack {

    override val binding by viewBinding(ActivityAudioPlayBinding::inflate)
    override val viewModel by viewModels<AudioPlayViewModel>()
    private val speedControlPopup by lazy { SliderPopup(this, SPEED) }
    private var adjustProgress = false
    private var playMode = AudioPlay.PlayMode.LIST_END_STOP
    private val lyricViewX by lazy { binding.lyricViewX }
    private var lyricOn = false
    private var oldLyric: String? = null
    private var menuCustomBtn: MenuItem? = null
    private var pendingAudioCacheAction: (() -> Unit)? = null

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let {
            if (it[0] != AudioPlay.book?.durChapterIndex
                || it[1] == 0
            ) {
                AudioPlay.skipTo(it[0] as Int)
            }
        }
    }
    private val sourceEditResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource()
            }
        }
    private val audioCacheDirSelect = registerForActivityResult(HandleFileContract()) { result ->
        val treeUri = result.uri?.toString()
        if (treeUri == null) {
            pendingAudioCacheAction = null
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val available = withContext(IO) {
                runCatching { AudioCacheManager.isCacheDirAvailable(treeUri) }
                    .getOrDefault(false)
            }
            if (available) {
                if (AppConfig.audioCacheTreeUri != treeUri) {
                    AudioCacheService.stop(this@AudioPlayActivity)
                }
                AppConfig.audioCacheTreeUri = treeUri
                toastOnUi(R.string.audio_cache_folder_selected)
                pendingAudioCacheAction?.invoke()
            } else {
                toastOnUi(R.string.audio_cache_folder_invalid)
            }
            pendingAudioCacheAction = null
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setBackgroundResource(R.color.transparent)
        AudioPlay.register(this)
        viewModel.titleData.observe(this) { name ->
            binding.titleBar.title = name
            val lyric = AudioPlay.durChapter?.getVariable("lyric")?.takeIf { it.isNotBlank() }
            upLyric(lyric ?: AudioPlay.durLyric)
        }
        viewModel.coverData.observe(this) {
            upCover(it)
        }
        viewModel.customBtnListData.observe(this) { menuCustomBtn?.isVisible = it }
        viewModel.initData(intent) {
            initListener()
        }
        initView()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.audio_play, menu)
        menuCustomBtn = menu.findItem(R.id.menu_custom_btn)?.also {
            it.isVisible = viewModel.customBtnListData.value == true
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible = AudioPlay.bookSource?.hasLogin() == true
        menu.findItem(R.id.menu_wake_lock)?.isChecked = AppConfig.audioPlayUseWakeLock
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_custom_btn -> {
                AudioPlay.bookSource?.let {source ->
                    AudioPlay.book?.let { book ->
                        SourceCallBack.callBackBtn(
                            this,
                            SourceCallBack.CLICK_CUSTOM_BUTTON,
                            source,
                            book,
                            AudioPlay.durChapter,
                            BookType.audio
                        )
                    }
                }
            }
            R.id.menu_change_source -> AudioPlay.book?.let {
                showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
            }

            R.id.menu_login -> AudioPlay.bookSource?.let {
                startActivity<SourceLoginActivity> {
                    putExtra("bookType", BookType.audio)
                }
            }

            R.id.menu_wake_lock -> AppConfig.audioPlayUseWakeLock = !AppConfig.audioPlayUseWakeLock
            R.id.menu_copy_audio_url -> {
                AudioPlay.book?.let {
                    val url = AudioPlay.durPlayUrl.ifBlank {
                        AudioPlay.durChapter?.resourceUrl.orEmpty()
                    }
                    SourceCallBack.callBackBtn(
                        this,
                        SourceCallBack.CLICK_COPY_PLAY_URL,
                        AudioPlay.bookSource,
                        it,
                        AudioPlay.durChapter,
                        BookType.audio,
                        url
                    ) {
                        sendToClip(url)
                    }
                }
            }
            R.id.menu_audio_cache_folder -> selectAudioCacheFolder()
            R.id.menu_audio_cache_range -> showAudioCacheRange()
            R.id.menu_clear_current_audio_cache -> clearCurrentAudioCache()
            R.id.menu_edit_source -> AudioPlay.bookSource?.let {
                sourceEditResult.launch {
                    putExtra("sourceUrl", it.bookSourceUrl)
                }
            }

            /* 跳过片头片尾设定按钮 */
            R.id.menu_skip_credits -> AudioPlay.book?.let {
                showDialogFragment(AudioSkipCredits.newInstance(it))
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun selectAudioCacheFolder(onSelected: (() -> Unit)? = null) {
        pendingAudioCacheAction = onSelected
        audioCacheDirSelect.launch {
            title = getString(R.string.audio_cache_select_folder)
            mode = HandleFileContract.DIR_SYS
        }
    }

    private fun ensureAudioCacheFolder(action: () -> Unit) {
        val treeUri = AppConfig.audioCacheTreeUri
        lifecycleScope.launch {
            val available = withContext(IO) {
                runCatching { AudioCacheManager.isCacheDirAvailable(treeUri) }
                    .getOrDefault(false)
            }
            if (available) {
                action()
            } else {
                toastOnUi(
                    if (treeUri.isNullOrBlank()) R.string.audio_cache_folder_not_set
                    else R.string.audio_cache_folder_invalid
                )
                selectAudioCacheFolder(action)
            }
        }
    }

    private fun showAudioCacheRange() {
        val book = AudioPlay.book ?: return
        val chapterCount = AudioPlay.simulatedChapterSize.takeIf { it > 0 }
            ?: book.simulatedTotalChapterNum()
        if (chapterCount <= 0) return
        alert(titleResource = R.string.audio_cache_range) {
            val dialogBinding = DialogDownloadChoiceBinding.inflate(layoutInflater).apply {
                editStart.setText((AudioPlay.durChapterIndex + 1).toString())
                editEnd.setText(chapterCount.toString())
            }
            customView { dialogBinding.root }
            okButton {
                val start = dialogBinding.editStart.text?.toString()?.trim()?.toIntOrNull()
                val end = dialogBinding.editEnd.text?.toString()?.trim()?.toIntOrNull()
                val range = AudioCachePolicy.normalizeRange(
                    start = start?.minus(1) ?: -1,
                    endInclusive = end?.minus(1) ?: -1,
                    chapterCount = chapterCount,
                )
                if (range == null) {
                    toastOnUi(R.string.error_scope_input)
                    return@okButton
                }
                ensureAudioCacheFolder {
                    AudioCacheService.start(
                        this@AudioPlayActivity,
                        book.bookUrl,
                        range.first,
                        range.last,
                    )
                    toastOnUi(R.string.audio_cache_start_range)
                }
            }
            cancelButton()
        }
    }

    private fun clearCurrentAudioCache() {
        val book = AudioPlay.book ?: return
        val chapter = AudioPlay.durChapter ?: return
        ensureAudioCacheFolder {
            val treeUri = AppConfig.audioCacheTreeUri
            lifecycleScope.launch {
                val removed = withContext(IO) {
                    runCatching {
                        AudioCacheManager.removeCachedChapter(
                            treeUri,
                            book.bookUrl,
                            chapter,
                        )
                    }.getOrDefault(false)
                }
                if (removed) {
                    postEvent(
                        EventBus.AUDIO_CACHE_CHANGED,
                        AudioCacheStateChanged(
                            bookUrl = book.bookUrl,
                            key = AudioCacheKey.from(chapter),
                            cached = false,
                            treeUri = treeUri,
                        )
                    )
                    toastOnUi(R.string.audio_cache_current_chapter_cleared)
                } else {
                    toastOnUi(R.string.audio_cache_current_chapter_not_found)
                }
            }
        }
    }

    private fun initView() {
        observeEventSticky<AudioPlay.PlayMode>(EventBus.PLAY_MODE_CHANGED) {
            playMode = it
            updatePlayModeIcon()
        }
        binding.playerProgress.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.tvDurTime.text = progress.toDurationTime()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                adjustProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                adjustProgress = false
                AudioPlay.adjustProgress(seekBar.progress)
            }
        })
        /* 低于安卓6不显示调速按钮 */
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            binding.ivSpeedControl.invisible()
        }
        
        binding.ivSpeedControl.setOnClickListener {
            speedControlPopup.showAsDropDown(it, 0, (-100).dpToPx(), Gravity.TOP)
        }

        binding.ivTimer.setOnClickListener {
            showDialogFragment(
                SleepTimerDialog.newInstance(
                    AudioPlayService.timeMinute,
                    AudioPlayService.chapterToStop,
                    useEpisodes = true,
                )
            )
        }
        binding.llPlayMenu.applyNavigationBarPadding()
    }

    private fun initListener() {
        binding.ivPlayMode.setOnClickListener {
            AudioPlay.changePlayMode()
        }
        binding.fabPlayStop.setOnClickListener {
            playButton()
        }
        binding.fabPlayStop.onLongClick {
            AudioPlay.stop()
        }
        binding.ivSkipNext.setOnClickListener {
            AudioPlay.next()
        }
        binding.ivSkipPrevious.setOnClickListener {
            AudioPlay.prev()
        }
        binding.ivChapter.setOnClickListener {
            AudioPlay.book?.let {
                tocActivityResult.launch(it.bookUrl)
            }
        }
    }

    private fun updatePlayModeIcon() {
        binding.ivPlayMode.setImageResource(playMode.iconRes)
    }

    private fun upCover(path: String?) {
        BookCover.load(this, path, sourceOrigin = AudioPlay.bookSource?.bookSourceUrl) {
            BookCover.loadBlur(this, path, sourceOrigin = AudioPlay.bookSource?.bookSourceUrl)
                .into(binding.ivBg)
        }.into(binding.ivCover)
    }

    override fun upLyric(lyric: String?) {
        if (oldLyric == lyric) return
        oldLyric = lyric
        if(lyric.isNullOrBlank()) {
            binding.lyricViewX.gone()
            return
        }
        lyricViewX.loadLyric(lyric)
        binding.lyricViewX.visible()
        if (lyricOn) {
            upLyricP(AudioPlay.durChapterPos)
        } else {
            lyricOn = true
            lyricViewX.apply {
                setNormalTextSize(50F)
                setCurrentTextSize(60F)
                setTimelineTextColor(accentColor)
                setDraggable(true, object : OnPlayClickListener {
                    override fun onPlayClick(time: Long): Boolean {
                        AudioPlay.adjustProgress(time.toInt())
                        playButton(false)
                        return true
                    }
                })
            }
            lyricViewX.postDelayed({
                upLyricP(AudioPlay.durChapterPos)
            }, 100)
        }
    }
    override fun upLyricP(position: Int) {
        lyricViewX.updateTime(position.toLong(),false)
    }

    private fun playButton(noLyr: Boolean = true) {
        val status = AudioPlay.status
        when (status) {
            Status.PLAY if noLyr -> {
                AudioPlay.pause(this)
            }
            Status.PAUSE -> {
                AudioPlay.resume(this)
            }
            else -> {
                AudioPlay.loadOrUpPlayUrl()
            }
        }
    }

    override val oldBook: Book?
        get() = AudioPlay.book

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (book.isAudio) {
            viewModel.changeTo(source, book, toc)
        } else {
            AudioPlay.stop()
            lifecycleScope.launch {
                withContext(IO) {
                    AudioPlay.book?.migrateTo(book, toc)
                    book.removeType(BookType.updateError)
                    AudioPlay.book?.delete()
                    appDb.bookDao.insert(book)
                }
                startActivityForBook(book)
                finish()
            }
        }
    }

    override fun finish() {
        val book = AudioPlay.book ?: return super.finish()
        if (AudioPlay.inBookshelf) {
            callBackBookEnd()
            return super.finish()
        }
        if (!AppConfig.showAddToShelfAlert) {
            callBackBookEnd()
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    AudioPlay.book?.removeType(BookType.notShelf)
                    AudioPlay.book?.save()
                    SourceCallBack.callBackBook(SourceCallBack.ADD_BOOK_SHELF, AudioPlay.bookSource, AudioPlay.book)
                    AudioPlay.inBookshelf = true
                    setResult(RESULT_OK)
                }
                noButton {
                    callBackBookEnd()
                    viewModel.removeFromBookshelf { super.finish() }
                }
            }
        }
    }

    private fun callBackBookEnd() {
        SourceCallBack.callBackBook(SourceCallBack.END_READ, AudioPlay.bookSource, AudioPlay.book, AudioPlay.durChapter)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (AudioPlay.status != Status.PLAY) {
            AudioPlay.stop()
        }
        AudioPlay.unregister(this)
    }

    @SuppressLint("SetTextI18n")
    override fun observeLiveBus() {
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) {
            if (it) {
                playButton()
            }
        }
        observeEventSticky<Int>(EventBus.AUDIO_STATE) {
            AudioPlay.status = it
            if (it == Status.PLAY) {
                binding.fabPlayStop.setImageResource(R.drawable.ic_pause_24dp)
            } else {
                binding.fabPlayStop.setImageResource(R.drawable.ic_play_24dp)
            }
        }
        observeEventSticky<String>(EventBus.AUDIO_SUB_TITLE) {
            binding.tvSubTitle.text = it
            val chapterSize = AudioPlay.simulatedChapterSize
            if (chapterSize > 0) {
                binding.tvChapterIndex.text = getString(
                    R.string.audio_chapter_progress,
                    AudioPlay.durChapterIndex + 1,
                    chapterSize,
                )
                binding.tvChapterIndex.visible()
            } else {
                binding.tvChapterIndex.gone()
            }
            binding.ivSkipPrevious.isEnabled = AudioPlay.durChapterIndex > 0
            binding.ivSkipNext.isEnabled =
                AudioPlay.durChapterIndex < AudioPlay.simulatedChapterSize - 1
        }
        observeEventSticky<Int>(EventBus.AUDIO_SIZE) {
            binding.playerProgress.max = it
            binding.tvAllTime.text = it.toDurationTime()
        }
        observeEventSticky<Int>(EventBus.AUDIO_PROGRESS) {
            if (!adjustProgress) binding.playerProgress.progress = it
            binding.tvDurTime.text = it.toDurationTime()
        }
        observeEventSticky<Int>(EventBus.AUDIO_BUFFER_PROGRESS) {
            binding.playerProgress.secondaryProgress = it
        }
        observeEventSticky<Float>(EventBus.AUDIO_SPEED) {
            if (it == 1f) {
                binding.tvSpeed.invisible()
            } else {
                binding.tvSpeed.text = String.format(Locale.ROOT, "%.1fX", it)
                binding.tvSpeed.visible()
            }
        }
        observeEventSticky<Int>(EventBus.AUDIO_DS) { upTimerText() }
        observeEventSticky<Int>(EventBus.AUDIO_CHAPTER_STOP) { upTimerText() }
    }

    private fun upTimerText() {
        val chapter = AudioPlayService.chapterToStop
        val minute = AudioPlayService.timeMinute
        when {
            chapter > 0 -> {
                binding.tvTimer.text = getString(R.string.audio_stop_chapters, chapter)
                binding.tvTimer.visible()
            }

            minute > 0 -> {
                binding.tvTimer.text = getString(R.string.timer_m, minute)
                binding.tvTimer.visible()
            }

            else -> binding.tvTimer.gone()
        }
    }

    override fun onSleepTimerMinute(minute: Int) {
        AudioPlay.setTimer(minute)
    }

    override fun onSleepTimerChapter(count: Int) {
        AudioPlay.setChapterStop(count)
    }

    override fun upLoading(loading: Boolean) {
        runOnUiThread {
            binding.progressLoading.visible(loading)
        }
    }
}
