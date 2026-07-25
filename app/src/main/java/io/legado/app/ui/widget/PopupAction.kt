package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemPopupActionBinding
import io.legado.app.databinding.PopupActionBinding
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.secondaryDisabledTextColor
import io.legado.app.utils.applyMd3PopupStyle
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.resolveDropDownYOffset
import io.legado.app.utils.setTintMutate
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
    private var actionItems: List<PopupActionItem> = emptyList()
    private var reserveIconColumn = false
    private var reserveCheckColumn = false
    private val measureRow by lazy {
        ItemPopupActionBinding.inflate(context.layoutInflater).apply {
            textView.setPadding(0, 0, 0, 0)
            textView.minWidth = 0
            textView.minHeight = 0
        }
    }

    init {
        contentView = binding.root
        applyMd3PopupStyle()

        isTouchable = true
        isOutsideTouchable = false
        isFocusable = true

        binding.recyclerView.adapter = adapter
    }

    fun setItems(items: List<SelectItem<String>>) {
        setActionItems(
            items.map { item ->
                PopupActionItem(
                    title = item.title,
                    value = item.value
                )
            }
        )
    }

    fun setActionItems(items: List<PopupActionItem>) {
        actionItems = items
        updateMenuLayout(items)
        adapter.setItems(items)
    }

    private fun updateMenuLayout(items: List<PopupActionItem>) {
        reserveIconColumn = isVertical && items.any { it.icon != null }
        reserveCheckColumn = isVertical && items.any { it.checkable }
        binding.recyclerView.updateLayoutParams {
            width = if (isVertical) {
                measureVerticalMenuWidth(items)
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    private fun measureVerticalMenuWidth(items: List<PopupActionItem>): Int {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val textView = measureRow.textView
        var maxTextWidth = 0
        items.forEach { item ->
            textView.text = item.title
            textView.measure(unspecified, unspecified)
            maxTextWidth = maxOf(maxTextWidth, textView.measuredWidth)
        }
        val horizontalPadding = 32.dpToPx()
        val iconColumn = if (reserveIconColumn) 36.dpToPx() else 0
        val checkColumn = if (reserveCheckColumn) 32.dpToPx() else 0
        return (horizontalPadding + iconColumn + checkColumn + maxTextWidth)
            .coerceIn(112.dpToPx(), 280.dpToPx())
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
        updateMenuLayout(actionItems)
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

    data class PopupActionItem(
        val title: String,
        val value: String,
        val icon: Drawable? = null,
        val enabled: Boolean = true,
        val checkable: Boolean = false,
        val checked: Boolean = false
    )

    inner class Adapter(context: Context) :
        RecyclerAdapter<PopupActionItem, ItemPopupActionBinding>(context) {

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getViewBinding(parent: ViewGroup): ItemPopupActionBinding {
            return ItemPopupActionBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemPopupActionBinding,
            item: PopupActionItem,
            payloads: MutableList<Any>
        ) {
            with(binding) {
                val enabled = isItemEnabled(item)
                root.isEnabled = enabled
                ViewCompat.setAccessibilityDelegate(
                    root,
                    object : AccessibilityDelegateCompat() {
                        override fun onInitializeAccessibilityNodeInfo(
                            host: View,
                            info: AccessibilityNodeInfoCompat
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.isCheckable = item.checkable
                            info.setChecked(
                                if (item.checked) {
                                    AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE
                                } else {
                                    AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE
                                }
                            )
                        }
                    }
                )
                textView.text = item.title
                if (isVertical) {
                    root.updateLayoutParams { width = ViewGroup.LayoutParams.MATCH_PARENT }
                    root.minimumHeight = 48.dpToPx()
                    root.setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                    root.setBackgroundResource(selectableItemBackgroundResId())
                    textView.updateLayoutParams<LinearLayout.LayoutParams> {
                        width = 0
                        weight = 1f
                    }
                    textView.minHeight = 48.dpToPx()
                    textView.minWidth = 0
                    textView.gravity = Gravity.CENTER_VERTICAL
                    textView.setPadding(0, 0, 0, 0)
                    textView.background = null
                } else {
                    root.updateLayoutParams { width = ViewGroup.LayoutParams.WRAP_CONTENT }
                    root.minimumHeight = 0
                    root.setPadding(0, 0, 0, 0)
                    root.background = null
                    textView.updateLayoutParams<LinearLayout.LayoutParams> {
                        width = ViewGroup.LayoutParams.WRAP_CONTENT
                        weight = 0f
                    }
                    textView.minHeight = 0
                    textView.minWidth = 0
                    textView.gravity = Gravity.CENTER
                    textView.setPadding(5.dpToPx(), 5.dpToPx(), 5.dpToPx(), 5.dpToPx())
                    textView.setBackgroundResource(selectableItemBackgroundResId())
                }
                val textColor = when {
                    !enabled -> context.secondaryDisabledTextColor
                    item.value in dangerValues -> context.getCompatColor(R.color.error)
                    else -> context.getCompatColor(R.color.primaryText)
                }
                textView.setTextColor(textColor)
                bindLeadingIcon(ivIcon, item, textColor)
                bindTrailingCheck(ivCheckEnd, item, textColor)
            }
        }

        private fun bindLeadingIcon(
            imageView: android.widget.ImageView,
            item: PopupActionItem,
            tint: Int
        ) {
            when {
                item.icon != null -> {
                    imageView.setImageDrawable(item.icon)
                    item.icon.setTintMutate(tint)
                    imageView.visibility = View.VISIBLE
                }

                isVertical && reserveIconColumn -> {
                    imageView.setImageDrawable(null)
                    imageView.visibility = View.INVISIBLE
                }

                else -> {
                    imageView.setImageDrawable(null)
                    imageView.visibility = View.GONE
                }
            }
        }

        private fun bindTrailingCheck(
            imageView: android.widget.ImageView,
            item: PopupActionItem,
            tint: Int
        ) {
            when {
                item.checked -> {
                    imageView.setImageResource(R.drawable.ic_check)
                    imageView.drawable?.setTintMutate(tint)
                    imageView.visibility = View.VISIBLE
                }

                isVertical && reserveCheckColumn -> {
                    imageView.setImageDrawable(null)
                    imageView.visibility = View.INVISIBLE
                }

                else -> {
                    imageView.setImageDrawable(null)
                    imageView.visibility = View.GONE
                }
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemPopupActionBinding) {
            holder.itemView.setOnClickListener {
                getItem(holder.layoutPosition)?.takeIf(::isItemEnabled)?.let { item ->
                    onActionClick?.invoke(item.value)
                }
            }
        }

        private fun isItemEnabled(item: PopupActionItem): Boolean {
            return item.enabled && item.value !in disabledValues
        }

        private fun selectableItemBackgroundResId(): Int {
            val value = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            return value.resourceId
        }
    }

}
