package io.legado.app.ui.book.read

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.getMediaItem
import io.legado.app.model.analyzeRule.ReviewRuleParser
import io.legado.app.model.analyzeRule.ReviewRuleParser.DetailItem as ReviewDetailItem
import io.legado.app.model.jsSource.JsSourceReview
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.windowSize
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemReviewCommentBinding
import io.legado.app.ui.widget.dialog.PhotoDialog
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import splitties.systemservices.windowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class ReviewDetailDialog() : BaseDialogFragment(R.layout.dialog_recycler_view) {

    constructor(
        paragraphNum: Int,
        totalCount: Int,
        chapterIndex: Int,
        paragraphData: String?,
        bookUrl: String,
        sourceKey: String,
        ruleHash: Int,
    ) : this() {
        arguments = Bundle().apply {
            putInt(ARG_PARAGRAPH_NUM, paragraphNum)
            putInt(ARG_TOTAL_COUNT, totalCount)
            putInt(ARG_CHAPTER_INDEX, chapterIndex)
            putString(ARG_PARAGRAPH_DATA, paragraphData)
            putString(ARG_BOOK_URL, bookUrl)
            putString(ARG_SOURCE_KEY, sourceKey)
            putInt(ARG_RULE_HASH, ruleHash)
        }
    }

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy { ReviewAdapter(requireContext()) }
    private var paragraphNum: Int = 0
    private var totalCount: Int = 0
    private var chapterIndex: Int = 0
    private var paragraphData: String = ""
    private var bookUrl: String = ""
    private var sourceKey: String = ""
    private var ruleHash: Int = 0
    private var isLoading = false
    private var hasMore = true
    private var currentPage = 1
    private var nextPageUrl: String? = null
    private val mainItemIndexByKey = LinkedHashMap<String, Int>()
    private val detailItems = ArrayList<ReviewDetailItem>()
    private val expandedReplyParentKeys = HashSet<String>()
    private val replyLoadingParentKeys = HashSet<String>()
    private val replyExhaustedParentKeys = HashSet<String>()
    private val replyPageByParentKey = HashMap<String, Int>()
    private var hasReplyUrl = false
    private val sourceImageOptions by lazy {
        RequestOptions().set(OkHttpModelLoader.sourceOriginOption, sourceKey)
    }
    private var reviewSource: BaseSource? = null
    private var reviewAudioPlayer: ExoPlayer? = null
    private var currentAudioUrl: String? = null
    private var isAudioPreparing = false
    private var dragStartRawY = 0f
    private var dragStartHeightPx = 0
    private var isDraggingHeight = false
    private var hasDraggedHeight = false
    private val dragTouchSlop by lazy {
        ViewConfiguration.get(requireContext()).scaledTouchSlop
    }
    private val audioPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val previousAudioUrl = currentAudioUrl
            when (playbackState) {
                Player.STATE_BUFFERING -> isAudioPreparing = true
                Player.STATE_READY -> isAudioPreparing = false
                Player.STATE_ENDED -> {
                    isAudioPreparing = false
                    currentAudioUrl = null
                }
            }
            refreshAudioPlaybackUi(previousAudioUrl, currentAudioUrl)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshAudioPlaybackUi(currentAudioUrl, currentAudioUrl)
        }

        override fun onPlayerError(error: PlaybackException) {
            val previousAudioUrl = currentAudioUrl
            isAudioPreparing = false
            currentAudioUrl = null
            AppLog.put("段评语音播放失败\n${error.localizedMessage}", error)
            context?.toastOnUi(error.localizedMessage ?: getString(R.string.load_over_time))
            refreshAudioPlaybackUi(previousAudioUrl, currentAudioUrl)
        }
    }
    private val uiItemDiffCallback = object : DiffUtil.ItemCallback<ReviewUiItem>() {
        override fun areItemsTheSame(oldItem: ReviewUiItem, newItem: ReviewUiItem): Boolean {
            if (oldItem.itemType != newItem.itemType) return false
            if (oldItem.itemType == TYPE_MORE) {
                return oldItem.parentKey == newItem.parentKey
            }
            val oldId = oldItem.id?.takeIf { it.isNotBlank() }
            val newId = newItem.id?.takeIf { it.isNotBlank() }
            if (oldId != null || newId != null) {
                return oldId == newId && oldItem.isReply == newItem.isReply
            }
            return oldItem.isReply == newItem.isReply &&
                    oldItem.parentKey == newItem.parentKey &&
                    oldItem.name == newItem.name &&
                    oldItem.replyToName == newItem.replyToName &&
                    oldItem.content == newItem.content &&
                    oldItem.time == newItem.time &&
                    oldItem.avatar == newItem.avatar &&
                    oldItem.imageUrl == newItem.imageUrl &&
                    oldItem.audioUrl == newItem.audioUrl
        }

        override fun areContentsTheSame(oldItem: ReviewUiItem, newItem: ReviewUiItem): Boolean {
            return oldItem == newItem
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.16f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
        }
        applyDialogHeightRatio(lastHeightRatio)
    }

    override fun onResume() {
        super.onResume()
        adapter.upResumed(true)
    }

    override fun onPause() {
        adapter.upResumed(false)
        super.onPause()
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        paragraphNum = arguments?.getInt(ARG_PARAGRAPH_NUM) ?: 0
        totalCount = arguments?.getInt(ARG_TOTAL_COUNT) ?: 0
        chapterIndex = arguments?.getInt(ARG_CHAPTER_INDEX) ?: 0
        paragraphData = arguments?.getString(ARG_PARAGRAPH_DATA).orEmpty()
        bookUrl = arguments?.getString(ARG_BOOK_URL).orEmpty()
        sourceKey = arguments?.getString(ARG_SOURCE_KEY).orEmpty()
        ruleHash = arguments?.getInt(ARG_RULE_HASH) ?: 0
        binding.root.setBackgroundResource(R.drawable.bg_dialog_round_top)
        binding.dragHandle.visible()
        binding.toolBar.setBackgroundResource(R.drawable.bg_review_toolbar)
        binding.toolBar.updateLayoutParams<ViewGroup.LayoutParams> {
            height = 42.dpToPx()
        }
        binding.toolBar.minimumHeight = 0
        binding.toolBar.setPadding(0, 0, 0, 0)
        binding.toolBar.setContentInsetsRelative(6.dpToPx(), 6.dpToPx())
        binding.toolBar.title = ""
        binding.toolBar.subtitle = null
        val oldCountView = binding.toolBar.findViewWithTag<View>("review_count_tag")
        if (oldCountView != null) {
            binding.toolBar.removeView(oldCountView)
        }
        if (totalCount > 0) {
            val countView = TextView(requireContext()).apply {
                tag = "review_count_tag"
                text = getString(R.string.review_total_count, totalCount)
                setTextColor(getCompatColor(R.color.secondaryText))
                textSize = 14f
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
            }
            val lp = androidx.appcompat.widget.Toolbar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                marginEnd = 8.dpToPx()
            }
            binding.toolBar.addView(countView, lp)
        }
        binding.toolBar.setNavigationIcon(R.drawable.ic_baseline_close)
        binding.toolBar.setNavigationContentDescription(R.string.close)
        binding.toolBar.navigationIcon?.setTint(getCompatColor(R.color.secondaryText))
        binding.toolBar.setNavigationOnClickListener { dismiss() }
        setupHeightDrag()
        val layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.setFastScrollEnabled(false)
        binding.recyclerView.setTrackVisible(false)
        binding.recyclerView.setBubbleVisible(false)
        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                dx: Int,
                dy: Int
            ) {
                if (dy <= 0) return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (hasMore && !isLoading && lastVisible >= adapter.itemCount - 3) {
                    loadDetailPage(paragraphNum, currentPage + 1, append = true)
                }
            }
        })
        loadDetailPage(paragraphNum, 1, append = false)
    }

    private fun setupHeightDrag() {
        binding.dragHandle.setOnClickListener {
            val windowHeight = requireContext().windowManager.windowSize.heightPixels
            if (windowHeight <= 0) return@setOnClickListener
            val currentHeight = dialog?.window?.attributes?.height?.takeIf { it > 0 }
                ?: (windowHeight * lastHeightRatio).roundToInt()
            val midpoint = windowHeight * (DEFAULT_HEIGHT_RATIO + MAX_HEIGHT_RATIO) / 2f
            applyDialogHeightRatio(
                if (currentHeight < midpoint) MAX_HEIGHT_RATIO else DEFAULT_HEIGHT_RATIO
            )
        }
        binding.dragHandle.setOnTouchListener { handle, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val windowHeight = requireContext().windowManager.windowSize.heightPixels
                    if (windowHeight <= 0) return@setOnTouchListener false
                    isDraggingHeight = true
                    hasDraggedHeight = false
                    dragStartRawY = event.rawY
                    dragStartHeightPx = dialog?.window?.attributes?.height?.takeIf { it > 0 }
                        ?: (windowHeight * lastHeightRatio).roundToInt()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingHeight) return@setOnTouchListener false
                    val deltaY = event.rawY - dragStartRawY
                    if (!hasDraggedHeight && abs(deltaY) < dragTouchSlop) {
                        return@setOnTouchListener true
                    }
                    hasDraggedHeight = true
                    applyDialogHeightPx((dragStartHeightPx - deltaY).roundToInt())
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDraggingHeight) return@setOnTouchListener false
                    isDraggingHeight = false
                    if (!hasDraggedHeight) handle.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isDraggingHeight = false
                    true
                }

                else -> false
            }
        }
    }

    private fun applyDialogHeightRatio(heightRatio: Float) {
        val windowHeight = requireContext().windowManager.windowSize.heightPixels
        if (windowHeight <= 0) return
        applyDialogHeightPx((windowHeight * heightRatio).roundToInt())
    }

    private fun applyDialogHeightPx(heightPx: Int) {
        val windowHeight = requireContext().windowManager.windowSize.heightPixels
        if (windowHeight <= 0) return
        val minHeight = (windowHeight * MIN_HEIGHT_RATIO).roundToInt()
        val maxHeight = (windowHeight * MAX_HEIGHT_RATIO).roundToInt()
        val clampedHeight = heightPx.coerceIn(minHeight, maxHeight)
        setLayout(1f, clampedHeight)
        lastHeightRatio = clampedHeight.toFloat() / windowHeight
    }

    override fun onDestroyView() {
        releaseAudioPlayer()
        super.onDestroyView()
    }

    private fun ensureAudioPlayer(): ExoPlayer {
        reviewAudioPlayer?.let { return it }
        return ExoPlayerHelper.createHttpExoPlayer(requireContext()).also { player ->
            player.addListener(audioPlayerListener)
            reviewAudioPlayer = player
        }
    }

    private fun toggleAudioPlayback(audioUrl: String) {
        val source = reviewSource ?: return
        val previousAudioUrl = currentAudioUrl
        val player = ensureAudioPlayer()
        if (currentAudioUrl == audioUrl) {
            if (isAudioPreparing) return
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
            refreshAudioPlaybackUi(previousAudioUrl, currentAudioUrl)
            return
        }
        currentAudioUrl = audioUrl
        isAudioPreparing = true
        runCatching {
            val mediaItem = AnalyzeUrl(
                audioUrl,
                source = source
            ).getMediaItem()
            player.setMediaItem(mediaItem, true)
            player.prepare()
            player.playWhenReady = true
        }.onFailure {
            isAudioPreparing = false
            currentAudioUrl = null
            AppLog.put("段评语音播放失败\n${it.localizedMessage}", it)
            context?.toastOnUi(it.localizedMessage ?: getString(R.string.load_over_time))
        }
        refreshAudioPlaybackUi(previousAudioUrl, currentAudioUrl)
    }

    private fun releaseAudioPlayer() {
        val previousAudioUrl = currentAudioUrl
        isAudioPreparing = false
        currentAudioUrl = null
        reviewAudioPlayer?.runCatching {
            removeListener(audioPlayerListener)
            stop()
            clearMediaItems()
            release()
        }
        reviewAudioPlayer = null
        refreshAudioPlaybackUi(previousAudioUrl, currentAudioUrl)
    }

    private fun refreshAudioPlaybackUi(oldAudioUrl: String?, newAudioUrl: String?) {
        if (view == null) return
        val affectedUrls = linkedSetOf<String>()
        oldAudioUrl?.takeIf { it.isNotBlank() }?.let { affectedUrls.add(it) }
        newAudioUrl?.takeIf { it.isNotBlank() }?.let { affectedUrls.add(it) }
        if (affectedUrls.isEmpty()) return
        binding.recyclerView.post {
            if (view == null) return@post
            adapter.getItems().forEachIndexed { index, item ->
                val itemAudioUrl = item.audioUrl
                if (item.itemType == TYPE_NORMAL &&
                    !itemAudioUrl.isNullOrBlank() &&
                    affectedUrls.contains(itemAudioUrl)
                ) {
                    adapter.updateItem(index, PAYLOAD_AUDIO_STATE)
                }
            }
        }
    }

    private fun loadDetailPage(paragraphNum: Int, page: Int, append: Boolean) {
        if (isLoading) return
        if (!append) {
            binding.rotateLoading.visible()
            binding.tvMsg.gone()
            adapter.setItems(emptyList())
            currentPage = 1
            hasMore = true
            nextPageUrl = null
            mainItemIndexByKey.clear()
            detailItems.clear()
            expandedReplyParentKeys.clear()
            replyLoadingParentKeys.clear()
            replyExhaustedParentKeys.clear()
            replyPageByParentKey.clear()
            hasReplyUrl = false
        }
        if (!hasMore) return
        isLoading = true
        Coroutine.async(lifecycleScope, IO, start = CoroutineStart.LAZY) {
            val source = ReadBook.bookSource ?: return@async null
            if (source.getKey() != sourceKey) return@async null
            val book = ReadBook.book ?: return@async null
            if (book.bookUrl != bookUrl) return@async null
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return@async null
            if (source.isJsSource()) {
                if (source.mainJs.hashCode() != ruleHash) return@async null
                val result = JsSourceReview.getReviewDetailAwait(
                    source = source,
                    book = book,
                    chapter = chapter,
                    paragraphIndex = paragraphNum,
                    paragraphData = paragraphData,
                    page = page,
                ) ?: return@async null
                return@async ReviewResult(
                    items = result.items,
                    nextPageUrl = result.nextPageUrl,
                    hasNextPageRule = true,
                    hasReplyUrl = JsSourceReview.hasReviewRepliesCapability(source),
                    source = source,
                )
            }
            val rule = source.ruleReview ?: return@async null
            if (!rule.enabled || rule.hashCode() != ruleHash) return@async null
            val firstPageUrlRule = rule.reviewDetailUrl?.takeIf { it.isNotBlank() } ?: return@async null
            val nextPageUrlRule = rule.reviewDetailNextPageUrl?.takeIf { it.isNotBlank() }
            val effectiveNextUrl = nextPageUrl?.takeIf { it.isNotBlank() }
            if (page > 1 && effectiveNextUrl == null && nextPageUrlRule == null) return@async null
            val detailUrlRule = when {
                page > 1 && !effectiveNextUrl.isNullOrBlank() -> effectiveNextUrl
                page > 1 -> nextPageUrlRule ?: firstPageUrlRule
                else -> firstPageUrlRule
            }
            if (rule.detailListRule.isNullOrBlank() || rule.detailContentRule.isNullOrBlank()) {
                return@async null
            }
            val paraIndex = paragraphNum.toString()
            val paraData = paragraphData
            val analyzeUrl = AnalyzeUrl(
                detailUrlRule,
                page = page,
                extraParams = mapOf(
                    "paraIndex" to paraIndex,
                    "paraData" to paraData,
                    "page" to page.toString()
                ),
                baseUrl = chapter.url,
                source = source,
                ruleData = book,
                chapter = chapter,
                coroutineContext = coroutineContext
            )
            val body = analyzeUrl.getStrResponseAwait(useWebView = false).body ?: ""
            val result = ReviewRuleParser.parseDetailPage(
                body = body,
                rule = rule,
                nextPageRule = nextPageUrlRule,
                baseUrl = analyzeUrl.url,
                source = source,
                book = book,
                chapter = chapter,
                context = coroutineContext,
                paraIndex = paraIndex,
                paraData = paraData,
                page = page.toString()
            )
            ReviewResult(
                items = result.items,
                nextPageUrl = result.nextPageUrl,
                hasNextPageRule = nextPageUrlRule != null,
                hasReplyUrl = !rule.reviewQuoteUrl.isNullOrBlank() &&
                        !rule.replyListRule.isNullOrBlank() &&
                        !rule.replyContentRule.isNullOrBlank(),
                source = source,
            )
        }.onSuccess(Main) { result ->
            if (!append) {
                binding.rotateLoading.gone()
            }
            result?.source?.let { reviewSource = it }
            result?.let { hasReplyUrl = it.hasReplyUrl }
            val items = result?.items.orEmpty()
            val nextUrlFromRule = result?.nextPageUrl
            if (result?.hasNextPageRule == true) {
                nextPageUrl = nextUrlFromRule
                if (nextUrlFromRule.isNullOrBlank()) {
                    hasMore = false
                }
            }
            if (items.isEmpty() && !append) {
                hasMore = false
                binding.tvMsg.text = getString(R.string.content_empty)
                binding.tvMsg.visible()
                isLoading = false
                return@onSuccess
            }
            if (items.isEmpty()) {
                hasMore = false
                isLoading = false
                return@onSuccess
            }
            val mergedCount = mergeDetailItems(items)
            if (mergedCount == 0 && append) {
                hasMore = false
                isLoading = false
                return@onSuccess
            }
            currentPage = page
            renderUiItems()
            isLoading = false
        }.onError {
            isLoading = false
            if (!append) {
                binding.rotateLoading.gone()
                binding.tvMsg.text = it.localizedMessage ?: getString(R.string.content_empty)
                binding.tvMsg.visible()
            }
        }.start()
    }

    private fun loadReplies(parentKey: String) {
        if (!hasReplyUrl || !replyLoadingParentKeys.add(parentKey)) return
        val itemIndex = mainItemIndexByKey[parentKey]
        val reviewId = itemIndex?.let { detailItems.getOrNull(it)?.id }
            ?.takeIf { it.isNotBlank() }
        if (reviewId == null) {
            replyLoadingParentKeys.remove(parentKey)
            return
        }
        val page = (replyPageByParentKey[parentKey] ?: 0) + 1
        renderUiItems()
        Coroutine.async(lifecycleScope, IO, start = CoroutineStart.LAZY) {
            val source = ReadBook.bookSource ?: return@async null
            if (source.getKey() != sourceKey) return@async null
            val book = ReadBook.book ?: return@async null
            if (book.bookUrl != bookUrl) return@async null
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                ?: return@async null
            if (source.isJsSource()) {
                if (source.mainJs.hashCode() != ruleHash) return@async null
                val replies = JsSourceReview.getReviewRepliesAwait(
                    source = source,
                    book = book,
                    chapter = chapter,
                    paragraphIndex = paragraphNum,
                    paragraphData = paragraphData,
                    reviewId = reviewId,
                    page = page,
                ) ?: return@async null
                return@async ReplyResult(
                    replies = replies,
                    page = page,
                    source = source,
                )
            }
            val rule = source.ruleReview ?: return@async null
            if (!rule.enabled || rule.hashCode() != ruleHash) return@async null
            val replyUrlRule = rule.reviewQuoteUrl?.takeIf { it.isNotBlank() }
                ?: return@async null
            if (rule.replyListRule.isNullOrBlank() || rule.replyContentRule.isNullOrBlank()) {
                return@async null
            }
            val paraIndex = paragraphNum.toString()
            val analyzeUrl = AnalyzeUrl(
                replyUrlRule,
                page = page,
                extraParams = mapOf(
                    "paraIndex" to paraIndex,
                    "paraData" to paragraphData,
                    "reviewId" to reviewId,
                    "page" to page.toString(),
                ),
                baseUrl = chapter.url,
                source = source,
                ruleData = book,
                chapter = chapter,
                coroutineContext = coroutineContext,
            )
            val body = analyzeUrl.getStrResponseAwait(useWebView = false).body
                ?.takeIf { it.isNotBlank() }
                ?: error(getString(R.string.content_empty))
            ReplyResult(
                replies = ReviewRuleParser.parseReplyPage(
                    body = body,
                    rule = rule,
                    baseUrl = analyzeUrl.url,
                    source = source,
                    book = book,
                    chapter = chapter,
                    context = coroutineContext,
                    paraIndex = paraIndex,
                    paraData = paragraphData,
                    page = page.toString(),
                ),
                page = page,
                source = source,
            )
        }.onSuccess(Main) { result ->
            replyLoadingParentKeys.remove(parentKey)
            if (result == null) {
                context?.toastOnUi(R.string.review_rule_missing)
                renderUiItems()
                return@onSuccess
            }
            reviewSource = result.source
            val currentIndex = mainItemIndexByKey[parentKey]
            val current = currentIndex?.let { detailItems.getOrNull(it) }
            if (current == null) {
                renderUiItems()
                return@onSuccess
            }
            replyPageByParentKey[parentKey] = result.page
            expandedReplyParentKeys.add(parentKey)
            if (result.replies.isEmpty()) {
                replyExhaustedParentKeys.add(parentKey)
            } else {
                val replies = mergeReplies(current.replies, result.replies)
                detailItems[currentIndex] = current.copy(replies = replies)
                if (replies.size == current.replies.size ||
                    current.replyCount != null && replies.size >= current.replyCount
                ) {
                    replyExhaustedParentKeys.add(parentKey)
                }
            }
            renderUiItems()
        }.onError {
            replyLoadingParentKeys.remove(parentKey)
            context?.toastOnUi(it.localizedMessage ?: getString(R.string.load_over_time))
            renderUiItems()
        }.start()
    }

    private fun buildDetailItemKey(item: ReviewDetailItem, isReply: Boolean): String {
        val id = item.id?.trim().orEmpty()
        if (id.isNotEmpty()) {
            return (if (isReply) "r|" else "m|") + id
        }
        return buildString {
            append(if (isReply) "r" else "m")
            append('|')
            append(item.name.orEmpty())
            append('|')
            append(item.replyToName.orEmpty())
            append('|')
            append(item.content.orEmpty())
            append('|')
            append(item.time.orEmpty())
            append('|')
            append(item.avatar.orEmpty())
            append('|')
            append(item.imageUrl.orEmpty())
            append('|')
            append(item.audioUrl.orEmpty())
        }
    }

    private fun mergeDetailItems(newItems: List<ReviewDetailItem>): Int {
        var changed = 0
        newItems.forEach { incoming ->
            val mainKey = buildDetailItemKey(incoming, isReply = false)
            val normalizedReplies = dedupeReplies(incoming.replies)
            val normalized = incoming.copy(replies = normalizedReplies)
            val index = mainItemIndexByKey[mainKey]
            if (index == null) {
                detailItems.add(normalized)
                mainItemIndexByKey[mainKey] = detailItems.lastIndex
                changed++
            } else {
                val old = detailItems[index]
                val mergedReplies = mergeReplies(old.replies, normalized.replies)
                val mergedReplyCount = max(old.replyCount ?: 0, normalized.replyCount ?: 0)
                    .takeIf { it > 0 }
                if (mergedReplies.size != old.replies.size || mergedReplyCount != old.replyCount) {
                    detailItems[index] = old.copy(
                        replies = mergedReplies,
                        replyCount = mergedReplyCount
                    )
                    changed++
                }
            }
        }
        return changed
    }

    private fun dedupeReplies(replies: List<ReviewDetailItem>): List<ReviewDetailItem> {
        if (replies.isEmpty()) return replies
        val seen = HashSet<String>(replies.size)
        val result = ArrayList<ReviewDetailItem>(replies.size)
        replies.forEach { reply ->
            val key = buildDetailItemKey(reply, isReply = true)
            if (seen.add(key)) result.add(reply)
        }
        return result
    }

    private fun mergeReplies(
        oldReplies: List<ReviewDetailItem>,
        newReplies: List<ReviewDetailItem>
    ): List<ReviewDetailItem> {
        if (oldReplies.isEmpty()) return newReplies
        if (newReplies.isEmpty()) return oldReplies
        val result = ArrayList<ReviewDetailItem>(oldReplies.size + newReplies.size)
        result.addAll(oldReplies)
        val seen = HashSet<String>(oldReplies.size + newReplies.size)
        oldReplies.forEach { seen.add(buildDetailItemKey(it, isReply = true)) }
        newReplies.forEach { reply ->
            val key = buildDetailItemKey(reply, isReply = true)
            if (seen.add(key)) result.add(reply)
        }
        return result
    }

    private data class ReviewResult(
        val items: List<ReviewDetailItem>,
        val nextPageUrl: String?,
        val hasNextPageRule: Boolean,
        val hasReplyUrl: Boolean,
        val source: BaseSource,
    )

    private data class ReplyResult(
        val replies: List<ReviewDetailItem>,
        val page: Int,
        val source: BaseSource,
    )

    private data class ReviewUiItem(
        val id: String?,
        val avatar: String?,
        val name: String?,
        val replyToName: String?,
        val badges: List<String>,
        val content: String?,
        val imageUrl: String?,
        val audioUrl: String?,
        val time: String?,
        val likeCount: Int?,
        val isReply: Boolean,
        val itemType: Int = TYPE_NORMAL,
        val parentKey: String? = null,
        val moreCount: Int = 0,
        val isLoading: Boolean = false,
    )

    private fun flattenItems(items: List<ReviewDetailItem>): List<ReviewUiItem> {
        val list = ArrayList<ReviewUiItem>()
        items.forEach { item ->
            val parentKey = buildDetailItemKey(item, isReply = false)
            list.add(item.toUiItem(isReply = false, parentKey = parentKey))

            val loadedReplyCount = item.replies.size
            val totalReplyCount = max(item.replyCount ?: 0, loadedReplyCount)
            val isExpanded = loadedReplyCount > 0 ||
                    expandedReplyParentKeys.contains(parentKey)
            val canLoadMore = hasReplyUrl &&
                    !item.id.isNullOrBlank() &&
                    !replyExhaustedParentKeys.contains(parentKey) &&
                    (item.replyCount ?: 0) > loadedReplyCount

            if (isExpanded) {
                item.replies.forEach { reply ->
                    list.add(reply.toUiItem(isReply = true, parentKey = parentKey))
                }
            }

            if ((!isExpanded && loadedReplyCount <= 0 && !canLoadMore) ||
                (isExpanded && !canLoadMore)
            ) return@forEach
            val moreCount = if (isExpanded) {
                totalReplyCount - loadedReplyCount
            } else {
                totalReplyCount
            }
            list.add(
                ReviewUiItem(
                    id = null,
                    avatar = null,
                    name = null,
                    replyToName = null,
                    badges = emptyList(),
                    content = null,
                    imageUrl = null,
                    audioUrl = null,
                    time = null,
                    likeCount = null,
                    isReply = false,
                    itemType = TYPE_MORE,
                    parentKey = parentKey,
                    moreCount = moreCount,
                    isLoading = replyLoadingParentKeys.contains(parentKey),
                )
            )
        }
        return list
    }

    private fun renderUiItems() {
        val uiItems = flattenItems(detailItems)
        adapter.setItems(uiItems, uiItemDiffCallback, skipDiff = true)
    }

    private fun ReviewDetailItem.toUiItem(isReply: Boolean, parentKey: String? = null) = ReviewUiItem(
        id = id,
        avatar = avatar,
        name = name,
        replyToName = replyToName,
        badges = badges,
        content = content,
        imageUrl = imageUrl,
        audioUrl = audioUrl,
        time = time,
        likeCount = likeCount,
        isReply = isReply,
        parentKey = parentKey
    )

    private companion object {
        private const val DEFAULT_HEIGHT_RATIO = 0.68f
        private const val MIN_HEIGHT_RATIO = 0.35f
        private const val MAX_HEIGHT_RATIO = 0.92f
        const val ARG_PARAGRAPH_NUM = "paragraphNum"
        const val ARG_TOTAL_COUNT = "totalCount"
        const val ARG_CHAPTER_INDEX = "chapterIndex"
        const val ARG_PARAGRAPH_DATA = "paragraphData"
        const val ARG_BOOK_URL = "bookUrl"
        const val ARG_SOURCE_KEY = "sourceKey"
        const val ARG_RULE_HASH = "ruleHash"
        const val TYPE_NORMAL = 0
        const val TYPE_MORE = 1
        const val PAYLOAD_AUDIO_STATE = "review_audio_state"
        private var lastHeightRatio = DEFAULT_HEIGHT_RATIO
    }

    private inner class ReviewAdapter(context: Context) :
        RecyclerAdapter<ReviewUiItem, ItemReviewCommentBinding>(context) {

        private val imageSizes = HashMap<String, Size>()

        override fun getViewBinding(parent: ViewGroup): ItemReviewCommentBinding {
            return ItemReviewCommentBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemReviewCommentBinding,
            item: ReviewUiItem,
            payloads: MutableList<Any>
        ) {
            if (payloads.any { it == PAYLOAD_AUDIO_STATE }) {
                bindAudioState(binding, item)
                return
            }
            val tvContentLp = binding.tvContent.layoutParams as ViewGroup.MarginLayoutParams
            val basePadding = 8.dpToPx()
            val mainAvatarSize = 36.dpToPx()
            binding.root.isEnabled = !item.isLoading
            if (item.itemType == TYPE_MORE) {
                binding.root.updatePaddingRelative(
                    start = basePadding,
                    top = 2.dpToPx(),
                    end = basePadding,
                    bottom = 2.dpToPx()
                )
                binding.ivAvatar.updateLayoutParams<ViewGroup.LayoutParams> {
                    width = mainAvatarSize
                    height = mainAvatarSize
                }
                binding.ivAvatar.visibility = View.INVISIBLE
                binding.llLikeArea.gone()
                binding.tvName.gone()
                binding.llBadges.gone()
                binding.tvTime.gone()
                binding.ivMedia.gone()
                binding.tvAudio.gone()
                binding.tvContent.visible()
                binding.tvContent.text = if (item.isLoading) {
                    context.getString(R.string.loading)
                } else {
                    context.getString(R.string.review_more_replies, item.moreCount)
                }
                binding.tvContent.textSize = 14f
                binding.tvContent.setTextColor(context.getCompatColor(R.color.accent))
                binding.tvContent.setPadding(0, 0, 0, 0)
                tvContentLp.topMargin = 0
                binding.tvContent.layoutParams = tvContentLp
                binding.llContentCard.background = null
                return
            }

            // 子评论缩进：使用主评论头像宽度作为占位，避免写死 magic number
            val replyIndent = mainAvatarSize
            val startPadding = if (item.isReply) basePadding + replyIndent else basePadding
            binding.root.updatePaddingRelative(
                start = startPadding,
                top = basePadding,
                end = basePadding,
                bottom = basePadding
            )

            binding.llContentCard.background = null

            val avatarSize = if (item.isReply) 28.dpToPx() else 36.dpToPx()
            binding.ivAvatar.updateLayoutParams<ViewGroup.LayoutParams> {
                width = avatarSize
                height = avatarSize
            }

            if (item.avatar.isNullOrBlank()) {
                binding.ivAvatar.gone()
            } else {
                binding.ivAvatar.visible()
                ImageLoader.load(context, item.avatar)
                    .apply(sourceImageOptions)
                    .circleCrop()
                    .into(binding.ivAvatar)
            }

            val primaryColor = context.getCompatColor(R.color.primaryText)
            val secondaryColor = context.getCompatColor(R.color.secondaryText)
            val contentColor = context.getCompatColor(R.color.reviewContentText)
            binding.llBadges.visibility = if (item.badges.isEmpty()) View.GONE else View.VISIBLE
            bindBadges(binding.llBadges, item.badges)
            binding.tvName.text = item.name.orEmpty()
            binding.tvName.visibility = if (item.name.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.tvName.setTextColor(primaryColor)
            if (item.isReply) {
                val content = item.content.orEmpty().trim()
                val replyToName = item.replyToName.orEmpty().trim()
                binding.tvContent.text = when {
                    content.isEmpty() || replyToName.isEmpty() -> content
                    else -> SpannableStringBuilder().apply {
                        val prefix = "回复 $replyToName："
                        append(prefix)
                        setSpan(
                            ForegroundColorSpan(secondaryColor),
                            0,
                            prefix.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        append(content)
                    }
                }
            } else {
                binding.tvContent.text = item.content.orEmpty()
            }
            val hasText = binding.tvContent.text?.isNotBlank() == true
            binding.tvContent.visibility = if (hasText) View.VISIBLE else View.GONE
            binding.tvContent.textSize = if (item.isReply) 14f else 16f
            binding.tvContent.setTextColor(contentColor)
            binding.tvContent.setPadding(0, 0, 0, 0)
            tvContentLp.topMargin = if (item.isReply) 0 else 4.dpToPx()
            binding.tvContent.layoutParams = tvContentLp

            bindReviewImage(binding, item.imageUrl)

            bindAudioState(binding, item)

            binding.tvTime.text = item.time.orEmpty()
            binding.tvTime.visibility = if (item.time.isNullOrBlank()) View.GONE else View.VISIBLE
            val likeCount = item.likeCount
            val showLikeArea = !item.isReply || (likeCount != null && likeCount > 0)
            if (showLikeArea) {
                binding.ivLike.visible()
                binding.llLikeArea.visible()
                if (likeCount != null && likeCount > 0) {
                    binding.tvLikeCount.text = likeCount.toString()
                    binding.tvLikeCount.visible()
                } else {
                    binding.tvLikeCount.gone()
                }
            } else {
                binding.ivLike.gone()
                binding.tvLikeCount.gone()
                binding.llLikeArea.gone()
            }

        }

        private fun bindReviewImage(binding: ItemReviewCommentBinding, imageUrl: String?) {
            val imageView = binding.ivMedia
            Glide.with(context).clear(imageView)
            imageView.setImageDrawable(null)
            imageView.updateLayoutParams<ViewGroup.LayoutParams> {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            if (imageUrl.isNullOrBlank()) {
                imageView.gone()
                return
            }

            val imageRequest = ImageLoader.load(context, imageUrl)
                .apply(sourceImageOptions)
                .addListener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean = false

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        val width = resource.intrinsicWidth
                        val height = resource.intrinsicHeight
                        if (width > 0 && height > 0) {
                            imageSizes[imageUrl] = Size(width, height)
                        }
                        return false
                    }
                })
            imageSizes[imageUrl]?.let { size ->
                val placeholder = ShapeDrawable().apply {
                    intrinsicWidth = size.width
                    intrinsicHeight = size.height
                    paint.color = Color.TRANSPARENT
                }
                imageRequest.placeholder(placeholder).error(placeholder)
            }
            imageView.visible()
            imageRequest.into(imageView)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemReviewCommentBinding) {
            binding.root.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition) ?: return@setOnClickListener
                if (item.itemType == TYPE_MORE) {
                    if (item.isLoading) return@setOnClickListener
                    val parentKey = item.parentKey ?: return@setOnClickListener
                    loadReplies(parentKey)
                }
            }
            binding.ivMedia.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition) ?: return@setOnClickListener
                val imageUrl = item.imageUrl ?: return@setOnClickListener
                PhotoDialog(imageUrl, sourceKey)
                    .show(this@ReviewDetailDialog.childFragmentManager, "reviewPhoto")
            }
            binding.tvAudio.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition) ?: return@setOnClickListener
                val audioUrl = item.audioUrl ?: return@setOnClickListener
                toggleAudioPlayback(audioUrl)
            }
        }

        private fun bindAudioState(binding: ItemReviewCommentBinding, item: ReviewUiItem) {
            if (item.itemType == TYPE_MORE || item.audioUrl.isNullOrBlank()) {
                binding.tvAudio.gone()
                return
            }
            val isCurrentAudio = item.audioUrl == currentAudioUrl
            val audioTextRes = when {
                isCurrentAudio && isAudioPreparing -> R.string.loading
                isCurrentAudio && reviewAudioPlayer?.isPlaying == true -> R.string.review_pause_audio
                else -> R.string.review_play_audio
            }
            val audioIconRes = if (isCurrentAudio && reviewAudioPlayer?.isPlaying == true) {
                R.drawable.ic_pause_24dp
            } else {
                R.drawable.ic_play_24dp
            }
            binding.tvAudio.visible()
            binding.tvAudio.text = context.getString(audioTextRes)
            binding.tvAudio.setCompoundDrawablesRelativeWithIntrinsicBounds(
                audioIconRes,
                0,
                0,
                0
            )
        }

        private fun bindBadges(container: FlexboxLayout, badges: List<String>) {
            container.removeAllViews()
            if (badges.isEmpty()) {
                container.gone()
                return
            }
            container.visible()
            badges.forEach { badge ->
                if (badge.isAbsUrl() || badge.isDataUrl()) {
                    val badgeHeight = 20.dpToPx()
                    val iv = ImageView(context).apply {
                        layoutParams = FlexboxLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            badgeHeight
                        ).apply { setMargins(0, 0, 6.dpToPx(), 0) }
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        adjustViewBounds = true
                    }
                    ImageLoader.load(context, badge)
                        .apply(sourceImageOptions)
                        .into(iv)
                    container.addView(iv)
                } else {
                    val tv = TextView(context).apply {
                        layoutParams = FlexboxLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 6.dpToPx(), 0) }
                        setPadding(6.dpToPx(), 2.dpToPx(), 6.dpToPx(), 2.dpToPx())
                        setTextColor(context.getCompatColor(R.color.secondaryText))
                        textSize = 11f
                        text = badge
                        setBackgroundResource(R.drawable.bg_review_badge_chip)
                    }
                    container.addView(tv)
                }
            }
        }
    }
}
