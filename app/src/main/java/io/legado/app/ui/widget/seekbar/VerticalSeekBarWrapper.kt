package io.legado.app.ui.widget.seekbar

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.view.ViewCompat

/** Hosts a horizontal SeekBar rotated into a bottom-to-top brightness control. */
class VerticalSeekBarWrapper @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val seekBar: AppCompatSeekBar?
        get() = getChildAt(0) as? AppCompatSeekBar

    override fun onFinishInflate() {
        super.onFinishInflate()
        @Suppress("DEPRECATION")
        seekBar?.let {
            ViewCompat.setLayoutDirection(it, ViewCompat.LAYOUT_DIRECTION_LTR)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val child = seekBar ?: return
        val contentHeight = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0)
        val contentWidth = (
            MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        ).coerceAtLeast(0)
        child.measure(
            MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.AT_MOST),
        )
        val desiredWidth = child.measuredHeight + paddingLeft + paddingRight
        setMeasuredDimension(
            View.resolveSizeAndState(desiredWidth, widthMeasureSpec, 0),
            measuredHeight,
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = seekBar ?: return
        val contentWidth = right - left - paddingLeft - paddingRight
        val contentHeight = bottom - top - paddingTop - paddingBottom
        val childLeft = paddingLeft + (contentWidth - child.measuredWidth) / 2
        val childTop = paddingTop + (contentHeight - child.measuredHeight) / 2
        child.layout(
            childLeft,
            childTop,
            childLeft + child.measuredWidth,
            childTop + child.measuredHeight,
        )
    }
}
