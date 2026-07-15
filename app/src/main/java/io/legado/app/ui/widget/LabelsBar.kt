package io.legado.app.ui.widget

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.widget.TextView
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.ui.widget.text.AccentBgTextView
import io.legado.app.utils.dpToPx

@Suppress("unused", "MemberVisibilityCanBePrivate")
class LabelsBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FlexboxLayout(context, attrs) {

    private val unUsedViews = arrayListOf<TextView>()
    private val usedViews = arrayListOf<TextView>()
    var textSize = 12f

    fun setLabels(
        labels: List<String>,
        onClick: ((String) -> Unit)? = null,
        onLongClick: ((String) -> Boolean)? = null
    ) {
        clear()
        labels.forEach {
            addLabel(it, onClick, onLongClick)
        }
    }

    fun clear() {
        unUsedViews.addAll(usedViews)
        usedViews.clear()
        removeAllViews()
    }

    fun addLabel(label: String, onClick: ((String) -> Unit)?, onLongClick: ((String) -> Boolean)?) {
        val tv = if (unUsedViews.isEmpty()) {
            AccentBgTextView(context, null).apply {
                setPadding(3.dpToPx(), 0, 3.dpToPx(), 0)
                setRadius(2)
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
                val lp = FlexboxLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 2.dpToPx()
                    if (this@LabelsBar.flexWrap == FlexWrap.NOWRAP) {
                        flexShrink = 0f
                    } else {
                        bottomMargin = 2.dpToPx()
                    }
                }
                layoutParams = lp
                text = label
                usedViews.add(this)
            }
        } else {
            unUsedViews.last().apply {
                usedViews.add(this)
                unUsedViews.removeAt(unUsedViews.lastIndex)
            }
        }
        tv.textSize = textSize
        tv.text = label
        tv.setOnClickListener(null)
        tv.setOnLongClickListener(null)
        tv.isClickable = false
        tv.isLongClickable = false
        if (onClick != null) {
            tv.setOnClickListener { onClick.invoke(label) }
        }
        if (onLongClick != null) {
            tv.setOnLongClickListener { onLongClick.invoke(label) }
        }
        addView(tv)
    }
}
