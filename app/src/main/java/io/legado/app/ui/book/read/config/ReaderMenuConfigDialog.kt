package io.legado.app.ui.book.read.config

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemReaderMenuConfigBinding
import io.legado.app.help.ReaderMenuConfig
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.read.ReaderMenuItem
import io.legado.app.ui.book.read.loadReaderMenuConfig
import io.legado.app.ui.book.read.saveReaderMenuConfig
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

/** Configure which reader overflow actions stay in the first-level menu. */
class ReaderMenuConfigDialog : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy { MenuAdapter(requireContext()) }
    private lateinit var itemTouchHelper: ItemTouchHelper

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.setTitle(R.string.reader_menu_config)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.toolBar.setOnMenuItemClickListener(this)
        binding.toolBar.inflateMenu(R.menu.reader_menu_config)
        binding.toolBar.menu.applyTint(requireContext())
        initData()

        // Reuse the app-wide edge-scrolling slide selection behavior.
        val dragSelectTouchHelper = DragSelectTouchHelper(adapter.dragSelectCallback)
            .setSlideArea(0, 24)
        dragSelectTouchHelper.attachToRecyclerView(binding.recyclerView)
        dragSelectTouchHelper.activeSlideSelect()

        // Let drag selection inspect touches before ItemTouchHelper starts a reorder.
        itemTouchHelper = ItemTouchHelper(MenuItemTouchCallback(adapter))
        itemTouchHelper.attachToRecyclerView(binding.recyclerView)
    }

    private fun initData() {
        val config = loadReaderMenuConfig(requireContext()).normalized()
        val primaryKeys = config.primary.toHashSet()
        val orderedKeys = config.primary + config.more
        adapter.setItems(
            orderedKeys.mapNotNull { key ->
                ReaderMenuItem.byKey[key]?.let { item ->
                    Entry(item.key, item.titleRes, key in primaryKeys)
                }
            }
        )
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_reader_select_all -> adapter.setAllPrimary(true)
            R.id.menu_reader_select_none -> adapter.setAllPrimary(false)
            R.id.menu_reader_reset -> {
                adapter.reset()
            }
        }
        return true
    }

    private data class Entry(
        val key: String,
        val titleRes: Int,
        val primary: Boolean
    )

    private inner class MenuAdapter(context: Context) :
        RecyclerAdapter<Entry, ItemReaderMenuConfigBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemReaderMenuConfigBinding {
            return ItemReaderMenuConfigBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemReaderMenuConfigBinding,
            item: Entry,
            payloads: MutableList<Any>
        ) {
            val position = holder.bindingAdapterPosition
            val previous = getItem(position - 1)
            val isFirstInGroup = previous == null || previous.primary != item.primary
            binding.tvSection.visibility = if (isFirstInGroup) View.VISIBLE else View.GONE
            if (isFirstInGroup) {
                binding.tvSection.setText(
                    if (item.primary) {
                        R.string.reader_menu_zone_primary
                    } else {
                        R.string.reader_menu_zone_more
                    }
                )
            }
            binding.checkBox.text = context.getString(item.titleRes)
            binding.checkBox.isChecked = item.primary
        }

        override fun registerListener(
            holder: ItemViewHolder,
            binding: ItemReaderMenuConfigBinding
        ) {
            binding.checkBox.setOnUserCheckedChangeListener { isChecked ->
                val position = holder.bindingAdapterPosition
                setPrimary(position, isChecked, persistNow = true)
            }
            binding.root.setOnClickListener { binding.checkBox.performClick() }
            binding.ivDrag.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper.startDrag(holder)
                }
                true
            }
        }

        fun setAllPrimary(primary: Boolean) {
            setItems(getItems().map { it.copy(primary = primary) })
            persist()
        }

        fun reset() {
            setItems(
                ReaderMenuConfig.ALL_KEYS.mapNotNull { key ->
                    ReaderMenuItem.byKey[key]?.let {
                        Entry(it.key, it.titleRes, true)
                    }
                }
            )
            persist()
        }

        fun setPrimary(position: Int, primary: Boolean, persistNow: Boolean) {
            val entry = getItem(position) ?: return
            if (entry.primary == primary) return
            if (!persistNow) {
                setItem(position, entry.copy(primary = primary))
                return
            }
            val items = getItems().toMutableList().apply {
                removeAt(position)
            }
            val updated = entry.copy(primary = primary)
            val insertion = if (primary) {
                items.indexOfLast { it.primary } + 1
            } else {
                items.size
            }
            items.add(insertion, updated)
            setItems(items)
            persist()
        }

        fun grouped(items: List<Entry>): List<Entry> {
            return items.filter { it.primary } + items.filterNot { it.primary }
        }

        fun regroupAndPersist() {
            val grouped = grouped(getItems())
            if (grouped != getItems()) {
                setItems(grouped)
            }
            persist()
        }

        fun swapWithinGroup(srcPosition: Int, targetPosition: Int): Boolean {
            if (!canSwapWithinGroup(srcPosition, targetPosition)) return false
            swapItem(srcPosition, targetPosition)
            return true
        }

        fun canSwapWithinGroup(srcPosition: Int, targetPosition: Int): Boolean {
            val source = getItem(srcPosition) ?: return false
            val target = getItem(targetPosition) ?: return false
            return source.primary == target.primary
        }

        fun persist() {
            val items = getItems()
            saveReaderMenuConfig(
                requireContext(),
                ReaderMenuConfig(
                    primary = items.filter { it.primary }.map(Entry::key),
                    more = items.filterNot { it.primary }.map(Entry::key)
                )
            )
            (activity as? io.legado.app.ui.book.read.ReadBookActivity)
                ?.refreshReaderMenu()
        }

        val dragSelectCallback =
            object : DragSelectTouchHelper.AdvanceCallback<String>(Mode.ToggleAndReverse) {
                override fun currentSelectedId(): MutableSet<String> {
                    return getItems().filter { it.primary }.mapTo(linkedSetOf(), Entry::key)
                }

                override fun getItemId(position: Int): String {
                    return getItem(position)?.key.orEmpty()
                }

                override fun updateSelectState(position: Int, isSelected: Boolean): Boolean {
                    if (getItem(position) == null) return false
                    setPrimary(position, isSelected, persistNow = false)
                    return true
                }

                override fun onSelectEnd(end: Int) {
                    super.onSelectEnd(end)
                    regroupAndPersist()
                }
            }
    }

    private inner class MenuItemTouchCallback(
        private val adapter: MenuAdapter
    ) : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

        override fun isLongPressDragEnabled(): Boolean = false

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            return adapter.swapWithinGroup(
                viewHolder.bindingAdapterPosition,
                target.bindingAdapterPosition
            )
        }

        override fun canDropOver(
            recyclerView: RecyclerView,
            current: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            return adapter.canSwapWithinGroup(
                current.bindingAdapterPosition,
                target.bindingAdapterPosition
            )
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ) {
            super.clearView(recyclerView, viewHolder)
            adapter.persist()
        }
    }
}
