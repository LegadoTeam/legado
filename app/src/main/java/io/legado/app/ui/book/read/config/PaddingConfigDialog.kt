package io.legado.app.ui.book.read.config

import android.animation.ValueAnimator
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogReadPaddingBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.throttle
import io.legado.app.utils.viewbindingdelegate.viewBinding

class PaddingConfigDialog : BaseDialogFragment(R.layout.dialog_read_padding) {

    private val binding by viewBinding(DialogReadPaddingBinding::bind)
    private val defaultConfig = ReadBookConfig.Config()
    private var curRegion = Region.BODY
    private var trackingRegion: Region? = null
    private var activeTrackingCount = 0
    private var lockLR = false
    private var textColor = 0
    private var bgColor = 0
    private var pendingBodyEdit: PaddingEdit? = null
    private var alphaAnimator: ValueAnimator? = null
    private val bodyThrottle = throttle<Unit>(150, leading = false) {
        val edit = pendingBodyEdit
        pendingBodyEdit = null
        if (edit != null) applyEdit(edit)
    }

    private enum class Region(val titleRes: Int) {
        HEADER(R.string.header),
        BODY(R.string.main_body),
        FOOTER(R.string.footer),
    }

    private enum class Side { TOP, BOTTOM, LEFT, RIGHT }

    private data class PaddingEdit(
        val region: Region,
        val side: Side,
        val value: Int,
        val linkSides: Boolean,
        val linkTopBottom: Boolean,
    )

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            attributes = attributes.apply {
                dimAmount = 0.0f
                gravity = Gravity.CENTER
            }
        }
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        (activity as ReadBookActivity).bottomDialog++
        initView()
        bindRegion(Region.BODY)
    }

    override fun onDestroyView() {
        finishPendingBodyEdit()
        activeTrackingCount = 0
        trackingRegion = null
        alphaAnimator?.cancel()
        restoreWindowAlpha()
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        finishPendingBodyEdit()
        ReadBookConfig.save()
        (activity as ReadBookActivity).bottomDialog--
        super.onDismiss(dialog)
    }

    private fun initView() = binding.run {
        bgColor = requireContext().bottomBackground
        textColor = requireContext().getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
        val radius = 8.dpToPx().toFloat()
        rootView.background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(bgColor)
        }
        swLockLr.setTextColor(textColor)
        swLockTb.setTextColor(textColor)
        swShowLine.setTextColor(textColor)
        ivReset.setColorFilter(textColor)
        TooltipCompat.setTooltipText(ivReset, getString(R.string.restore_default))
        updateControlSizes()

        arrayOf(dsbTop, dsbBottom, dsbLeft, dsbRight).forEach { seekBar ->
            seekBar.onTrackingStart = { startTracking() }
            seekBar.onTrackingStop = { stopTracking() }
        }
        dsbTop.onDragging = { liveApply(Side.TOP, it) }
        dsbBottom.onDragging = { liveApply(Side.BOTTOM, it) }
        dsbLeft.onDragging = { liveApply(Side.LEFT, it) }
        dsbRight.onDragging = { liveApply(Side.RIGHT, it) }
        dsbTop.onChanged = { finalApply(Side.TOP, it) }
        dsbBottom.onChanged = { finalApply(Side.BOTTOM, it) }
        dsbLeft.onChanged = { finalApply(Side.LEFT, it) }
        dsbRight.onChanged = { finalApply(Side.RIGHT, it) }

        btnRegionHeader.setOnClickListener { bindRegion(Region.HEADER) }
        btnRegionBody.setOnClickListener { bindRegion(Region.BODY) }
        btnRegionFooter.setOnClickListener { bindRegion(Region.FOOTER) }
        regionButtons().forEach { button ->
            button.setOnLongClickListener {
                AppConfig.readPaddingLargeControls = !AppConfig.readPaddingLargeControls
                updateControlSizes()
                true
            }
        }
        ivReset.setOnClickListener { askResetRegion() }
        swLockLr.setOnUserCheckedChangeListener { isChecked ->
            finishPendingBodyEdit()
            lockLR = isChecked
        }
        swLockTb.setOnUserCheckedChangeListener { isChecked ->
            finishPendingBodyEdit()
            ReadBookConfig.paddingTopBottomLinked = isChecked
            if (isChecked) finalApply(Side.TOP, dsbTop.progress)
        }
        swShowLine.setOnUserCheckedChangeListener { isChecked ->
            when (curRegion) {
                Region.HEADER -> ReadBookConfig.showHeaderLine = isChecked
                Region.FOOTER -> ReadBookConfig.showFooterLine = isChecked
                Region.BODY -> return@setOnUserCheckedChangeListener
            }
            postRegionEvent(curRegion)
        }
    }

    private fun bindRegion(region: Region) = binding.run {
        finishPendingBodyEdit()
        curRegion = region
        dsbTop.progress = getPadding(region, Side.TOP)
        dsbBottom.progress = getPadding(region, Side.BOTTOM)
        dsbLeft.progress = getPadding(region, Side.LEFT)
        dsbRight.progress = getPadding(region, Side.RIGHT)
        swLockTb.visibility = if (region == Region.BODY) View.VISIBLE else View.GONE
        swLockTb.isChecked = ReadBookConfig.paddingTopBottomLinked
        swShowLine.visibility = if (region == Region.BODY) View.GONE else View.VISIBLE
        when (region) {
            Region.BODY -> Unit
            Region.HEADER -> {
                swShowLine.isChecked = ReadBookConfig.showHeaderLine
            }

            Region.FOOTER -> {
                swShowLine.isChecked = ReadBookConfig.showFooterLine
            }
        }
        lockLR = getPadding(region, Side.LEFT) == getPadding(region, Side.RIGHT)
        swLockLr.isChecked = lockLR
        upRegionStyles()
    }

    private fun upRegionStyles() = binding.run {
        mapOf(
            Region.HEADER to btnRegionHeader,
            Region.BODY to btnRegionBody,
            Region.FOOTER to btnRegionFooter,
        ).forEach { (region, button) ->
            val selected = region == curRegion
            button.isSelected = selected
            button.setTextColor(if (selected) bgColor else textColor)
            button.backgroundTintList = null
            button.background = regionBackground(selected)
        }
    }

    private fun updateControlSizes() = binding.run {
        val large = AppConfig.readPaddingLargeControls
        val buttonSize = (if (large) 48 else 28).dpToPx()
        val iconSize = (if (large) 48 else 24).dpToPx()
        val height = (if (large) 48 else 40).dpToPx()
        val rowPadding = (height - buttonSize) / 2
        arrayOf(dsbTop, dsbBottom, dsbLeft, dsbRight).forEach {
            it.setAdjustButtonSize(buttonSize, iconSize)
            it.repeatIntervalMillis = 150L
            it.layoutParams = it.layoutParams.apply { this.height = height }
            it.setPadding(0, rowPadding, 0, height - buttonSize - rowPadding)
        }
        arrayOf(swLockLr, swLockTb, swShowLine).forEach {
            it.minimumHeight = height
            it.layoutParams = it.layoutParams.apply { this.height = height }
        }
        headerControls.layoutParams = (headerControls.layoutParams as LinearLayout.LayoutParams).apply {
            this.height = height
            bottomMargin = (if (large) 12 else 13).dpToPx()
        }
        val tabHeight = (if (large) 48 else 36).dpToPx()
        regionButtons().forEach { button ->
            button.layoutParams = button.layoutParams.apply { this.height = tabHeight }
        }
        regionControls.layoutParams = (regionControls.layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = (if (large) 0 else 8).dpToPx()
            marginEnd = marginStart
        }
        val resetSize = (if (large) 48 else 32).dpToPx()
        ivReset.layoutParams = (ivReset.layoutParams as LinearLayout.LayoutParams).apply {
            width = resetSize
            this.height = resetSize
            marginEnd = (if (large) 0 else 4).dpToPx()
        }
        val padding = (resetSize - (if (large) 24 else 20).dpToPx()) / 2
        ivReset.setPadding(padding, padding, padding, padding)
    }

    private fun regionBackground(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = 8.dpToPx().toFloat()
        setColor(if (selected) textColor else Color.TRANSPARENT)
        setStroke(1.dpToPx(), textColor)
    }

    private fun startTracking() {
        if (activeTrackingCount++ == 0) {
            trackingRegion = curRegion
            setPanelActionsEnabled(false)
            ghostWindow(true)
        }
    }

    private fun stopTracking() {
        activeTrackingCount = (activeTrackingCount - 1).coerceAtLeast(0)
        if (activeTrackingCount == 0) {
            ghostWindow(false)
            trackingRegion = null
            setPanelActionsEnabled(true)
        }
    }

    private fun setPanelActionsEnabled(enabled: Boolean) = binding.run {
        val alpha = if (enabled) 1f else 0.38f
        regionButtons().forEach {
            it.isEnabled = enabled
            it.alpha = alpha
        }
        ivReset.isEnabled = enabled
        ivReset.alpha = alpha
        swLockLr.isEnabled = enabled
        swLockLr.alpha = alpha
        swLockTb.isEnabled = enabled
        swLockTb.alpha = alpha
    }

    private fun regionButtons(): List<TextView> = binding.run {
        listOf(btnRegionHeader, btnRegionBody, btnRegionFooter)
    }

    private fun liveApply(side: Side, value: Int) {
        val edit = paddingEdit(side, value)
        if (edit.region == Region.BODY) {
            finishPendingBodyEditIfDifferent(edit)
            upCurrentSeekBar(edit)
            pendingBodyEdit = edit
            bodyThrottle()
        } else {
            applyEdit(edit)
        }
    }

    private fun finalApply(side: Side, value: Int) {
        val edit = paddingEdit(side, value)
        finishPendingBodyEditIfDifferent(edit)
        cancelPendingBodyEdit()
        applyEdit(edit)
    }

    private fun paddingEdit(side: Side, value: Int): PaddingEdit {
        val region = trackingRegion ?: curRegion
        return PaddingEdit(
            region, side, value,
            lockLR && region == curRegion,
            region == Region.BODY && ReadBookConfig.paddingTopBottomLinked,
        )
    }

    private fun applyEdit(edit: PaddingEdit) {
        upCurrentSeekBar(edit)
        setPadding(edit.region, edit.side, edit.value)
        val other = when {
            edit.linkSides && edit.side == Side.LEFT -> Side.RIGHT
            edit.linkSides && edit.side == Side.RIGHT -> Side.LEFT
            edit.linkTopBottom && edit.side == Side.TOP -> Side.BOTTOM
            edit.linkTopBottom && edit.side == Side.BOTTOM -> Side.TOP
            else -> null
        }
        if (other != null) {
            setPadding(edit.region, other, edit.value)
            upCurrentSeekBar(edit.copy(side = other))
        }
        postRegionEvent(edit.region)
    }

    private fun upCurrentSeekBar(edit: PaddingEdit) {
        if (edit.region != curRegion) return
        val seekBar = binding.run {
            when (edit.side) {
                Side.TOP -> dsbTop
                Side.BOTTOM -> dsbBottom
                Side.LEFT -> dsbLeft
                Side.RIGHT -> dsbRight
            }
        }
        if (seekBar.progress != edit.value) seekBar.progress = edit.value
    }

    private fun cancelPendingBodyEdit() {
        bodyThrottle.cancel()
        pendingBodyEdit = null
    }

    private fun finishPendingBodyEdit() {
        val edit = pendingBodyEdit
        cancelPendingBodyEdit()
        if (edit != null) applyEdit(edit)
    }

    private fun finishPendingBodyEditIfDifferent(edit: PaddingEdit) {
        pendingBodyEdit?.let { pending ->
            if (pending.region != edit.region || pending.side != edit.side) {
                finishPendingBodyEdit()
            }
        }
    }

    private fun postRegionEvent(region: Region) {
        postEvent(
            EventBus.UP_CONFIG,
            if (region == Region.BODY) arrayListOf(10, 5) else arrayListOf(2),
        )
    }

    private fun ghostWindow(ghost: Boolean) {
        val window = dialog?.window ?: return
        alphaAnimator?.cancel()
        alphaAnimator = ValueAnimator.ofFloat(window.attributes.alpha, if (ghost) 0.25f else 1f)
            .apply {
                duration = 120
                addUpdateListener { animation ->
                    window.attributes = window.attributes.apply {
                        alpha = animation.animatedValue as Float
                    }
                }
                start()
            }
    }

    private fun restoreWindowAlpha() {
        dialog?.window?.let { window ->
            window.attributes = window.attributes.apply { alpha = 1f }
        }
    }

    private fun askResetRegion() {
        val region = curRegion
        alert(R.string.restore_default) {
            setMessage(getString(R.string.reset_region_padding_confirm, getString(region.titleRes)))
            yesButton {
                cancelPendingBodyEdit()
                if (region == Region.BODY) {
                    ReadBookConfig.paddingTopBottomLinked = defaultConfig.paddingTopBottomLinked
                }
                Side.entries.forEach { side ->
                    setPadding(region, side, defaultPadding(region, side))
                }
                bindRegion(region)
                postRegionEvent(region)
            }
            noButton()
        }
    }

    private fun getPadding(region: Region, side: Side): Int = when (region) {
        Region.HEADER -> when (side) {
            Side.TOP -> ReadBookConfig.headerPaddingTop
            Side.BOTTOM -> ReadBookConfig.headerPaddingBottom
            Side.LEFT -> ReadBookConfig.headerPaddingLeft
            Side.RIGHT -> ReadBookConfig.headerPaddingRight
        }

        Region.BODY -> when (side) {
            Side.TOP -> ReadBookConfig.paddingTop
            Side.BOTTOM -> ReadBookConfig.paddingBottom
            Side.LEFT -> ReadBookConfig.paddingLeft
            Side.RIGHT -> ReadBookConfig.paddingRight
        }

        Region.FOOTER -> when (side) {
            Side.TOP -> ReadBookConfig.footerPaddingTop
            Side.BOTTOM -> ReadBookConfig.footerPaddingBottom
            Side.LEFT -> ReadBookConfig.footerPaddingLeft
            Side.RIGHT -> ReadBookConfig.footerPaddingRight
        }
    }

    private fun setPadding(region: Region, side: Side, value: Int) {
        when (region) {
            Region.HEADER -> when (side) {
                Side.TOP -> ReadBookConfig.headerPaddingTop = value
                Side.BOTTOM -> ReadBookConfig.headerPaddingBottom = value
                Side.LEFT -> ReadBookConfig.headerPaddingLeft = value
                Side.RIGHT -> ReadBookConfig.headerPaddingRight = value
            }

            Region.BODY -> when (side) {
                Side.TOP -> ReadBookConfig.paddingTop = value
                Side.BOTTOM -> ReadBookConfig.paddingBottom = value
                Side.LEFT -> ReadBookConfig.paddingLeft = value
                Side.RIGHT -> ReadBookConfig.paddingRight = value
            }

            Region.FOOTER -> when (side) {
                Side.TOP -> ReadBookConfig.footerPaddingTop = value
                Side.BOTTOM -> ReadBookConfig.footerPaddingBottom = value
                Side.LEFT -> ReadBookConfig.footerPaddingLeft = value
                Side.RIGHT -> ReadBookConfig.footerPaddingRight = value
            }
        }
    }

    private fun defaultPadding(region: Region, side: Side): Int = when (region) {
        Region.HEADER -> when (side) {
            Side.TOP -> defaultConfig.headerPaddingTop
            Side.BOTTOM -> defaultConfig.headerPaddingBottom
            Side.LEFT -> defaultConfig.headerPaddingLeft
            Side.RIGHT -> defaultConfig.headerPaddingRight
        }

        Region.BODY -> when (side) {
            Side.TOP -> defaultConfig.paddingTop
            Side.BOTTOM -> defaultConfig.paddingBottom
            Side.LEFT -> defaultConfig.paddingLeft
            Side.RIGHT -> defaultConfig.paddingRight
        }

        Region.FOOTER -> when (side) {
            Side.TOP -> defaultConfig.footerPaddingTop
            Side.BOTTOM -> defaultConfig.footerPaddingBottom
            Side.LEFT -> defaultConfig.footerPaddingLeft
            Side.RIGHT -> defaultConfig.footerPaddingRight
        }
    }
}
