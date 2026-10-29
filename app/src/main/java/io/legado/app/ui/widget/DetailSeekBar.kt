package io.legado.app.ui.widget

import android.content.Context
import android.graphics.PorterDuff
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.appcompat.widget.TooltipCompat
import io.legado.app.R
import io.legado.app.databinding.ViewDetailSeekBarBinding
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.progressAdd


class DetailSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs),
    SeekBarChangeListener {
    private var binding: ViewDetailSeekBarBinding =
        ViewDetailSeekBarBinding.inflate(LayoutInflater.from(context), this, true)
    private val isBottomBackground: Boolean
    private val repeatOnLongPress: Boolean
    private val repeatStops = mutableListOf<() -> Unit>()

    var repeatIntervalMillis: Long = 100L
        set(value) {
            stopRepeating()
            field = value.coerceAtLeast(1L)
        }

    var valueFormat: ((progress: Int) -> String)? = null
    var onChanged: ((progress: Int) -> Unit)? = null
    var onTrackingStart: (() -> Unit)? = null
    var onTrackingStop: (() -> Unit)? = null
    var onDragging: ((progress: Int) -> Unit)? = null
    var progress: Int
        get() = binding.seekBar.progress
        set(value) {
            binding.seekBar.progress = value
            upValue()
        }
    var max: Int
        get() = binding.seekBar.max
        set(value) {
            binding.seekBar.max = value
        }

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.DetailSeekBar)
        isBottomBackground =
            typedArray.getBoolean(R.styleable.DetailSeekBar_isBottomBackground, false)
        repeatOnLongPress =
            typedArray.getBoolean(R.styleable.DetailSeekBar_repeatOnLongPress, false)
        val buttonSize = typedArray.getDimensionPixelSize(R.styleable.DetailSeekBar_adjustButtonSize, 0)
        if (buttonSize > 0) {
            setAdjustButtonSize(buttonSize, typedArray.getDimensionPixelSize(
                R.styleable.DetailSeekBar_adjustButtonIconSize, buttonSize
            ))
        }
        val title = typedArray.getText(R.styleable.DetailSeekBar_title)
        binding.tvSeekTitle.apply {
            text = title
            TooltipCompat.setTooltipText(this, title)
        }
        binding.seekBar.max = typedArray.getInteger(R.styleable.DetailSeekBar_max, 0)
        typedArray.recycle()
        if (isBottomBackground && !isInEditMode) {
            val isLight = ColorUtils.isColorLight(context.bottomBackground)
            val textColor = context.getPrimaryTextColor(isLight)
            binding.tvSeekTitle.setTextColor(textColor)
            binding.ivSeekPlus.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
            binding.ivSeekReduce.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
            binding.tvSeekValue.setTextColor(textColor)
        }
        bindAdjustButton(binding.ivSeekPlus, 1)
        bindAdjustButton(binding.ivSeekReduce, -1)
        binding.seekBar.setOnSeekBarChangeListener(this)
    }

    fun setAdjustButtonSize(size: Int, iconSize: Int = size) {
        stopRepeating()
        listOf(binding.ivSeekPlus, binding.ivSeekReduce).forEach { button ->
            button.layoutParams = button.layoutParams.apply {
                width = size
                height = size
            }
            val space = (size - iconSize).coerceAtLeast(0)
            val start = space / 2
            button.setPadding(start, start, space - start, space - start)
        }
    }

    private fun stopRepeating() {
        repeatStops.forEach { it.invoke() }
    }

    override fun onDetachedFromWindow() {
        stopRepeating()
        super.onDetachedFromWindow()
    }

    private fun bindAdjustButton(button: View, delta: Int) {
        fun adjust() {
            binding.seekBar.progressAdd(delta)
            onChanged?.invoke(binding.seekBar.progress)
        }

        button.setOnClickListener { adjust() }
        if (!repeatOnLongPress) return

        var pressed = false
        val repeat = object : Runnable {
            override fun run() {
                if (!pressed) return
                adjust()
                button.postDelayed(this, repeatIntervalMillis)
            }
        }
        val stop: () -> Unit = {
            pressed = false
            button.removeCallbacks(repeat)
        }
        repeatStops += {
            stop()
            button.cancelLongPress()
        }
        button.setOnLongClickListener {
            button.postDelayed(repeat, FIRST_REPEAT_DELAY_MILLIS)
            true
        }
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> pressed = true
                // View handles its own long-press/tap callbacks after this listener. Cancelling
                // them here clears PREPRESSED and loses quick taps inside scrolling containers.
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stop()
            }
            false
        }
    }

    override fun dispatchSaveInstanceState(container: SparseArray<Parcelable>) {
        dispatchFreezeSelfOnly(container)
    }

    override fun dispatchRestoreInstanceState(container: SparseArray<Parcelable>) {
        dispatchThawSelfOnly(container)
    }

    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        return Bundle().apply {
            putParcelable(STATE_SUPER, superState)
            putInt(STATE_PROGRESS, progress)
        }
    }

    @Suppress("DEPRECATION")
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.getParcelable<Parcelable>(STATE_SUPER))
        progress = state.getInt(STATE_PROGRESS)
    }

    private fun upValue(progress: Int = binding.seekBar.progress) {
        valueFormat?.let {
            binding.tvSeekValue.text = it.invoke(progress)
        } ?: let {
            binding.tvSeekValue.text = progress.toString()
        }
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        upValue(progress)
        if (fromUser) onDragging?.invoke(progress)
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        onTrackingStart?.invoke()
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        onChanged?.invoke(binding.seekBar.progress)
        onTrackingStop?.invoke()
    }

    private companion object {
        const val FIRST_REPEAT_DELAY_MILLIS = 100L
        const val STATE_SUPER = "superState"
        const val STATE_PROGRESS = "progress"
    }

}
