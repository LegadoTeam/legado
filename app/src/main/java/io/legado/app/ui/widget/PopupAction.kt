package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemTextBinding
import io.legado.app.databinding.PopupActionBinding
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.secondaryDisabledTextColor
import io.legado.app.utils.applyMd3PopupStyle
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.resolveDropDownYOffset
import splitties.systemservices.layoutInflater

class PopupAction(private val context: Context) :
    PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {

    val binding = PopupActionBinding.inflate(context.layoutInflater)
    val adapter by lazy {
        Adapter(context).apply {
            setHasStableIds(true)
        }
    }
    var onActionClick: ((action: String) -> Unit)? = null
    private var isVertical = false
    private var dangerValues: Set<String> = emptySet()
    private var disabledValues: Set<String> = emptySet()

    init {
        contentView = binding.root
        applyMd3PopupStyle()

        isTouchable = true
        isOutsideTouchable = false
        isFocusable = true

        binding.recyclerView.adapter = adapter
    }

    fun setItems(items: List<SelectItem<String>>) {
        adapter.setItems(items)
    }

    fun setVertical(vertical: Boolean) {
        if (isVertical == vertical && binding.recyclerView.layoutManager != null) return
        isVertical = vertical
        binding.recyclerView.layoutManager = if (vertical) {
            LinearLayoutManager(context)
        } else {
            FlexboxLayoutManager(context).apply {
                flexDirection = FlexDirection.ROW
                flexWrap = FlexWrap.WRAP
            }
        }
        if (adapter.itemCount > 0) adapter.notifyDataSetChanged()
    }

    fun setDangerValues(values: Set<String>) {
        if (dangerValues == values) return
        dangerValues = values
        if (adapter.itemCount > 0) adapter.notifyDataSetChanged()
    }

    fun setDisabledValues(values: Set<String>) {
        if (disabledValues == values) return
        disabledValues = values
        if (adapter.itemCount > 0) adapter.notifyDataSetChanged()
    }

    override fun showAsDropDown(anchor: View?, xoff: Int, yoff: Int, gravity: Int) {
        if (anchor == null) {
            super.showAsDropDown(anchor, xoff, yoff, gravity)
            return
        }
        val visibleFrame = Rect()
        anchor.getWindowVisibleDisplayFrame(visibleFrame)
        contentView.measure(
            View.MeasureSpec.makeMeasureSpec(visibleFrame.width(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(visibleFrame.height(), View.MeasureSpec.AT_MOST),
        )
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val resolvedYOff = resolveDropDownYOffset(
            anchorTop = location[1],
            anchorHeight = anchor.height,
            popupHeight = contentView.measuredHeight,
            frameTop = visibleFrame.top,
            frameBottom = visibleFrame.bottom,
            gap = yoff,
        )
        super.showAsDropDown(anchor, xoff, resolvedYOff, gravity)
    }

    inner class Adapter(context: Context) :
        RecyclerAdapter<SelectItem<String>, ItemTextBinding>(context) {

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getViewBinding(parent: ViewGroup): ItemTextBinding {
            return ItemTextBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemTextBinding,
            item: SelectItem<String>,
            payloads: MutableList<Any>
        ) {
            with(binding) {
                holder.itemView.isEnabled = item.value !in disabledValues
                textView.text = item.title
                if (isVertical) {
                    textView.minHeight = 48.dpToPx()
                    textView.minWidth = 160.dpToPx()
                    textView.gravity = Gravity.CENTER_VERTICAL
                    textView.setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                }
                textView.setTextColor(
                    if (holder.itemView.isEnabled) {
                        context.getCompatColor(
                            if (item.value in dangerValues) R.color.error else R.color.primaryText
                        )
                    } else {
                        context.secondaryDisabledTextColor
                    }
                )
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemTextBinding) {
            holder.itemView.setOnClickListener {
                getItem(holder.layoutPosition)?.let { item ->
                    onActionClick?.invoke(item.value)
                }
            }
        }
    }

}
