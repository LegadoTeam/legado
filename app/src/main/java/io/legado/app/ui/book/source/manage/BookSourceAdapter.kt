package io.legado.app.ui.book.source.manage

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.BookSourceCheckState
import io.legado.app.databinding.ItemBookSourceBinding
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.model.Debug
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.startActivity
import io.legado.app.utils.visible
import java.util.Collections


class BookSourceAdapter(
    context: Context,
    private val callBack: CallBack,
    private val recyclerView: RecyclerView
) : RecyclerAdapter<BookSourcePart, ItemBookSourceBinding>(context),
    ItemTouchCallback.Callback {

    private val selected = linkedSetOf<BookSourcePart>()
    private val finalMessageRegex = Regex("成功|失败")
    private val handler = buildMainHandler()
    var showSourceHost = false
    var showCheckStatus = false
    private var bookshelfCounts = emptyMap<String, Int>()

    fun updateBookshelfCounts(counts: Map<String, Int>) {
        val previous = bookshelfCounts
        bookshelfCounts = counts
        getItems().forEachIndexed { index, source ->
            if (previous[source.bookSourceUrl] != counts[source.bookSourceUrl]) {
                notifyItemChanged(index, Bundle().apply { putBoolean("bookshelfCount", true) })
            }
        }
    }

    val selection: List<BookSourcePart>
        get() {
            return getItems().filter {
                selected.contains(it)
            }
        }

    val diffItemCallback = object : DiffUtil.ItemCallback<BookSourcePart>() {

        override fun areItemsTheSame(oldItem: BookSourcePart, newItem: BookSourcePart): Boolean {
            return oldItem.bookSourceUrl == newItem.bookSourceUrl
        }

        override fun areContentsTheSame(oldItem: BookSourcePart, newItem: BookSourcePart): Boolean {
            return oldItem.bookSourceName == newItem.bookSourceName
                    && oldItem.bookSourceGroup == newItem.bookSourceGroup
                    && oldItem.enabled == newItem.enabled
                    && oldItem.enabledExplore == newItem.enabledExplore
                    && oldItem.hasExploreUrl == newItem.hasExploreUrl
                    && oldItem.hasJs == newItem.hasJs
                    && oldItem.checkStatus == newItem.checkStatus
                    && oldItem.checkDetail == newItem.checkDetail
        }

        override fun getChangePayload(oldItem: BookSourcePart, newItem: BookSourcePart): Any? {
            val payload = Bundle()
            if (oldItem.bookSourceName != newItem.bookSourceName
                || oldItem.bookSourceGroup != newItem.bookSourceGroup
            ) {
                payload.putBoolean("upName", true)
            }
            if (oldItem.enabled != newItem.enabled) {
                payload.putBoolean("enabled", newItem.enabled)
            }
            if (oldItem.enabledExplore != newItem.enabledExplore ||
                oldItem.hasExploreUrl != newItem.hasExploreUrl
            ) {
                payload.putBoolean("upExplore", true)
            }
            if (oldItem.hasJs != newItem.hasJs) {
                payload.putBoolean("upJs", true)
            }
            if (oldItem.checkStatus != newItem.checkStatus || oldItem.checkDetail != newItem.checkDetail) {
                payload.putString("checkSourceMessage", null)
            }
            if (payload.isEmpty) {
                return null
            }
            return payload
        }

    }

    override fun getViewBinding(parent: ViewGroup): ItemBookSourceBinding {
        return ItemBookSourceBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookSourceBinding,
        item: BookSourcePart,
        payloads: MutableList<Any>
    ) {
        binding.run {
            if (payloads.isEmpty()) {
                root.setBackgroundColor(ColorUtils.withAlpha(context.backgroundColor, 0.5f))
                cbBookSource.text = item.getDisPlayNameGroup()
                swtEnabled.isChecked = item.enabled
                cbBookSource.isChecked = selected.contains(item)
                upBookshelfCount(binding, item)
                upCheckSourceMessage(binding, item)
                upShowExplore(ivExplore, item)
                tvJsBadge.gone(!item.hasJs)
                upSourceHost(binding, holder.layoutPosition)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "enabled" -> swtEnabled.isChecked = bundle.getBoolean("enabled")
                            "upName" -> cbBookSource.text = item.getDisPlayNameGroup()
                            "upExplore" -> upShowExplore(ivExplore, item)
                            "upJs" -> tvJsBadge.gone(!item.hasJs)
                            "bookshelfCount" -> upBookshelfCount(binding, item)
                            "selected" -> cbBookSource.isChecked = selected.contains(item)
                            "checkSourceMessage" -> upCheckSourceMessage(binding, item)
                            "upSourceHost" -> upSourceHost(binding, holder.layoutPosition)
                        }
                    }
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookSourceBinding) {
        binding.apply {
            swtEnabled.setOnUserCheckedChangeListener { checked ->
                getItem(holder.layoutPosition)?.let {
                    it.enabled = checked
                    callBack.enable(checked, it)
                }
            }
            cbBookSource.setOnUserCheckedChangeListener { checked ->
                getItem(holder.layoutPosition)?.let {
                    if (checked) {
                        selected.add(it)
                    } else {
                        selected.remove(it)
                    }
                    callBack.upCountView()
                }
            }
            ivEdit.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.edit(it)
                }
            }
            ivMenuMore.setOnClickListener {
                showMenu(ivMenuMore, holder.layoutPosition)
            }
        }
    }

    override fun onCurrentListChanged() {
        callBack.upCountView()
        recyclerView.doOnLayout {
            handler.post {
                notifyItemRangeChanged(0, itemCount, Bundle().apply {
                    putString("upSourceHost", null)
                })
            }
        }
    }

    private fun showMenu(view: View, position: Int) {
        val source = getItem(position) ?: return
        popupActionMenu(context) {
            val defaultOrder = callBack.sort == BookSourceSort.Default
            item(context.getString(R.string.to_top), "top", defaultOrder)
            item(context.getString(R.string.to_bottom), "bottom", defaultOrder)
            item(context.getString(R.string.login), "login", source.hasLoginUrl)
            item(context.getString(R.string.search), "search")
            item(context.getString(R.string.debug), "debug")
            item(context.getString(R.string.delete), "delete")
            item(
                context.getString(
                    if (source.enabledExplore) R.string.disable_explore else R.string.enable_explore
                ),
                "toggleExplore",
                source.hasExploreUrl
            )
            danger("delete")
        }.show(view) { action ->
            when (action) {
                "top" -> callBack.toTop(source)
                "bottom" -> callBack.toBottom(source)
                "login" -> context.startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", source.bookSourceUrl)
                }

                "search" -> callBack.searchBook(source)
                "debug" -> callBack.debug(source)
                "delete" -> {
                    callBack.del(source)
                    selected.remove(source)
                }

                "toggleExplore" -> callBack.enableExplore(!source.enabledExplore, source)
            }
        }
    }

    private fun upShowExplore(iv: ImageView, source: BookSourcePart) {
        when {
            !source.hasExploreUrl -> {
                iv.invisible()
            }

            source.enabledExplore -> {
                iv.setColorFilter(Color.GREEN)
                iv.visible()
                iv.contentDescription = context.getString(R.string.tag_explore_enabled)
            }

            else -> {
                iv.setColorFilter(Color.RED)
                iv.visible()
                iv.contentDescription = context.getString(R.string.tag_explore_disabled)
            }
        }
    }

    private fun upBookshelfCount(binding: ItemBookSourceBinding, item: BookSourcePart) {
        binding.tvBookshelfCount.text = context.getString(
            R.string.source_bookshelf_count, bookshelfCounts[item.bookSourceUrl] ?: 0
        )
    }

    private fun upCheckSourceMessage(
        binding: ItemBookSourceBinding,
        item: BookSourcePart
    ) = binding.run {
        val msg = if (Debug.isChecking) Debug.debugMessageMap[item.bookSourceUrl].orEmpty() else ""
        if (!showCheckStatus && msg.isEmpty()) {
            ivDebugText.gone()
            ivProgressBar.gone()
            return@run
        }
        val status = context.getString(when (item.checkStatus) {
            BookSourceCheckState.PASSED -> R.string.source_check_passed
            BookSourceCheckState.FAILED -> R.string.source_check_failed
            else -> R.string.source_check_needed
        })
        ivDebugText.text = msg.ifEmpty {
            if (item.checkDetail.isEmpty()) status else "$status：${item.checkDetail}"
        }
        ivDebugText.visibility = View.VISIBLE
        ivProgressBar.visibility =
            if (msg.isEmpty() || msg.contains(finalMessageRegex)) View.GONE else View.VISIBLE
    }

    private fun upSourceHost(binding: ItemBookSourceBinding, position: Int) = binding.run {
        if (showSourceHost && isItemHeader(position)) {
            tvHostText.text = getHeaderText(position)
            tvHostText.visible()
        } else {
            tvHostText.gone()
        }
    }

    fun selectAll() {
        getItems().forEach {
            selected.add(it)
        }
        notifyItemRangeChanged(0, itemCount, Bundle().apply {
            putString("selected", null)
        })
        callBack.upCountView()
    }

    fun revertSelection() {
        getItems().forEach {
            if (selected.contains(it)) {
                selected.remove(it)
            } else {
                selected.add(it)
            }
        }
        notifyItemRangeChanged(0, itemCount, Bundle().apply {
            putString("selected", null)
        })
        callBack.upCountView()
    }

    fun checkSelectedInterval() {
        val selectedPosition = linkedSetOf<Int>()
        getItems().forEachIndexed { index, it ->
            if (selected.contains(it)) {
                selectedPosition.add(index)
            }
        }
        if (selectedPosition.isEmpty()) return
        val minPosition = Collections.min(selectedPosition)
        val maxPosition = Collections.max(selectedPosition)
        val itemCount = maxPosition - minPosition + 1
        for (i in minPosition..maxPosition) {
            getItem(i)?.let {
                selected.add(it)
            }
        }
        notifyItemRangeChanged(minPosition, itemCount, Bundle().apply {
            putString("selected", null)
        })
        callBack.upCountView()
    }

    fun getHeaderText(position: Int): String {
        val source = getItem(position)!!
        return callBack.getSourceHost(source.bookSourceUrl)
    }

    fun isItemHeader(position: Int): Boolean {
        if (position == 0) return true
        val lastHost = getHeaderText(position - 1)
        val curHost = getHeaderText(position)
        return lastHost != curHost
    }

    private var dragStartPosition = RecyclerView.NO_POSITION
    private var draggedKey: String? = null
    private var draggedHolder: RecyclerView.ViewHolder? = null

    override fun canStartDrag() = !listUpdatesPaused

    override fun onDragStarted(viewHolder: RecyclerView.ViewHolder) {
        if (listUpdatesPaused) return
        val position = viewHolder.bindingAdapterPosition
        val source = getItem(position) ?: return
        pauseListUpdates()
        dragStartPosition = position
        draggedKey = source.bookSourceUrl
        draggedHolder = viewHolder
    }

    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        val source = getItem(srcPosition) ?: return false
        if (getItem(targetPosition) == null) return false
        if (draggedHolder == null || source.bookSourceUrl != draggedKey) {
            return false
        }
        swapItem(srcPosition, targetPosition)
        return true
    }

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        if (viewHolder !== draggedHolder) return
        val start = dragStartPosition
        val key = draggedKey
        dragStartPosition = RecyclerView.NO_POSITION
        draggedKey = null
        draggedHolder = null
        fun finish() {
            resumeListUpdates()
            callBack.reload()
        }
        val end = viewHolder.bindingAdapterPosition
        val moved = getItem(end)?.takeIf { it.bookSourceUrl == key }
        if (start == RecyclerView.NO_POSITION || end == RecyclerView.NO_POSITION || start == end || moved == null) {
            finish()
            return
        }
        val after = end > start
        val target = getItem(if (after) end - 1 else end + 1)
        if (target == null) {
            finish()
            return
        }
        callBack.move(moved.bookSourceUrl, target.bookSourceUrl, after, ::finish)
    }

    val dragSelectCallback: DragSelectTouchHelper.Callback =
        object : DragSelectTouchHelper.AdvanceCallback<BookSourcePart>(Mode.ToggleAndReverse) {
            override fun currentSelectedId(): MutableSet<BookSourcePart> {
                return selected
            }

            override fun getItemId(position: Int): BookSourcePart {
                return getItem(position)!!
            }

            override fun updateSelectState(position: Int, isSelected: Boolean): Boolean {
                getItem(position)?.let {
                    if (isSelected) {
                        selected.add(it)
                    } else {
                        selected.remove(it)
                    }
                    notifyItemChanged(position, Bundle().apply {
                        putString("selected", null)
                    })
                    callBack.upCountView()
                    return true
                }
                return false
            }
        }

    interface CallBack {
        val sort: BookSourceSort
        fun del(bookSource: BookSourcePart)
        fun edit(bookSource: BookSourcePart)
        fun toTop(bookSource: BookSourcePart)
        fun toBottom(bookSource: BookSourcePart)
        fun searchBook(bookSource: BookSourcePart)
        fun debug(bookSource: BookSourcePart)
        fun move(sourceUrl: String, targetUrl: String, after: Boolean, onFinally: () -> Unit)
        fun reload()
        fun enable(enable: Boolean, bookSource: BookSourcePart)
        fun enableExplore(enable: Boolean, bookSource: BookSourcePart)
        fun upCountView()
        fun getSourceHost(origin: String): String
    }
}
