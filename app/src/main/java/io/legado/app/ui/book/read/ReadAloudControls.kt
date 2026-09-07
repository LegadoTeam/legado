package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ViewReadAloudFloatBarBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.dpToPx
import kotlin.math.abs

/** Reader-only controls; positions are fractions of the safe viewport, so rotation stays reachable. */
class ReadAloudControls(
    private val binding: ViewReadAloudFloatBarBinding,
    private val refresh: () -> Unit,
) : SharedPreferences.OnSharedPreferenceChangeListener {
    private val bar = binding.root
    private val context = bar.context
    private val prefs = context.defaultSharedPreferences
    private val parent get() = bar.parent as ViewGroup
    private var menuVisible = false
    private var hidden = false
    private var movement = 0f
    private var wasFollowing = true
    private var running = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var initialX = 0f
    private var initialY = 0f
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (!dragging) position()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
        bar.addOnLayoutChangeListener(layoutListener)
        parent.addOnLayoutChangeListener(layoutListener)
        listOf(bar, binding.ivPauseAloud, binding.llBackToSpeech, binding.llReadFromHere)
            .forEach { it.setOnTouchListener(::onTouch) }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key?.startsWith("readAloudControls") == true) {
            if (key != PreferKey.readAloudControlsX && key != PreferKey.readAloudControlsY) reveal()
            else position()
        }
    }

    fun update(menuVisible: Boolean) {
        this.menuVisible = menuVisible
        val following = ReadAloud.followReadAloudPosition
        val isRun = BaseReadAloudService.isRun
        if (isRun != running || following && !wasFollowing) {
            hidden = false
            movement = 0f
        }
        running = isRun
        wasFollowing = following
        val showPause = following && prefs.getBoolean(PreferKey.readAloudControlsPause, false)
        val shouldShow = ReadAloudBarVisibility.shouldShow(
            isRun, following, menuVisible, showPause,
        ) && !hidden
        bar.isVisible = shouldShow
        if (!shouldShow) return
        binding.ivPauseAloud.isVisible = showPause
        binding.llBackToSpeech.isVisible = !showPause
        binding.llReadFromHere.isVisible = !showPause
        binding.vBarDivider.isVisible = !showPause
        binding.ivPauseAloud.setImageResource(
            if (BaseReadAloudService.pause) R.drawable.ic_play_24dp else R.drawable.ic_pause_24dp,
        )
        binding.ivPauseAloud.contentDescription = context.getString(
            if (BaseReadAloudService.pause) R.string.resume else R.string.pause,
        )
        TooltipCompat.setTooltipText(binding.ivPauseAloud, binding.ivPauseAloud.contentDescription)
        val background = context.bottomBackground
        val foreground = context.getPrimaryTextColor(ColorUtils.isColorLight(background))
        val opacity = prefs.getInt(PreferKey.readAloudControlsOpacity, 30).coerceIn(0, 100) / 100f
        (bar.background.mutate() as GradientDrawable).apply {
            setColor(ColorUtils.withAlpha(background, opacity))
            setStroke(1.dpToPx(), ColorUtils.withAlpha(foreground, if (AppConfig.isEInkMode) 1f else .25f))
        }
        binding.ivPauseAloud.setColorFilter(foreground)
        binding.ivBackToSpeech.setColorFilter(foreground)
        binding.ivReadFromHere.setColorFilter(foreground)
        binding.tvBackToSpeech.setTextColor(foreground)
        binding.tvReadFromHere.setTextColor(foreground)
        binding.vBarDivider.setBackgroundColor(ColorUtils.withAlpha(foreground, .3f))
        val size = prefs.getInt(PreferKey.readAloudControlsSize, 48).coerceIn(48, 72)
        val height = size.dpToPx()
        binding.ivPauseAloud.updateLayoutParams { width = height; this.height = height }
        binding.llBackToSpeech.minimumHeight = height
        binding.llReadFromHere.minimumHeight = height
        val width = if (showPause) height else (size * 6).dpToPx()
        val availableWidth = (parent.width - 32.dpToPx()).coerceAtLeast(96.dpToPx())
        bar.updateLayoutParams<FrameLayout.LayoutParams> {
            this.width = minOf(width, availableWidth)
        }
        position()
    }

    fun onMovement(percentOfPage: Float) {
        if (!BaseReadAloudService.isRun || ReadAloud.followReadAloudPosition || menuVisible || hidden ||
            !prefs.getBoolean(PreferKey.readAloudControlsAutoHide, false) || percentOfPage <= 0f
        ) return
        movement += percentOfPage
        if (movement >= prefs.getInt(PreferKey.readAloudControlsThreshold, 100).coerceIn(10, 500)) {
            hidden = true
            refresh()
        }
    }

    fun reveal(resetPosition: Boolean = false) {
        hidden = false
        movement = 0f
        if (resetPosition) prefs.edit().remove(PreferKey.readAloudControlsX)
            .remove(PreferKey.readAloudControlsY).apply()
        refresh()
    }

    private fun safeBounds(): FloatArray {
        val insets = ViewCompat.getRootWindowInsets(parent)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val left = (insets?.left ?: 0).toFloat()
        val top = (insets?.top ?: 0).toFloat()
        return floatArrayOf(left, top,
            (parent.width - (insets?.right ?: 0) - bar.width).toFloat().coerceAtLeast(left),
            (parent.height - (insets?.bottom ?: 0) - bar.height).toFloat().coerceAtLeast(top))
    }

    private fun position() {
        if (bar.width == 0 || parent.height == 0 || dragging) return
        val (left, top, right, bottom) = safeBounds()
        val movable = prefs.getBoolean(PreferKey.readAloudControlsDrag, false)
        val dock = prefs.getBoolean(PreferKey.readAloudControlsDock, false)
        var x = if (movable) prefs.getFloat(PreferKey.readAloudControlsX, .5f) else .5f
        x = if (x.isFinite()) x.coerceIn(0f, 1f) else .5f
        if (dock) x = if (x < .5f) 0f else 1f
        val y = if (movable) prefs.getFloat(PreferKey.readAloudControlsY, -1f) else -1f
        bar.x = left + (right - left) * x
        bar.y = if (y.isFinite() && y >= 0f) top + (bottom - top) * y.coerceIn(0f, 1f)
            else (bottom - 24.dpToPx()).coerceAtLeast(top)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onTouch(view: View, event: MotionEvent): Boolean {
        if (!prefs.getBoolean(PreferKey.readAloudControlsDrag, false)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                initialX = bar.x
                initialY = bar.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (abs(dx) > slop || abs(dy) > slop) dragging = true
                if (dragging) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    val (left, top, right, bottom) = safeBounds()
                    bar.x = (initialX + dx).coerceIn(left, right)
                    bar.y = (initialY + dy).coerceIn(top, bottom)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    val (left, top, right, bottom) = safeBounds()
                    val x = (bar.x - left) / (right - left).coerceAtLeast(1f)
                    val y = (bar.y - top) / (bottom - top).coerceAtLeast(1f)
                    prefs.edit().putFloat(PreferKey.readAloudControlsX, x)
                        .putFloat(PreferKey.readAloudControlsY, y).apply()
                } else view.performClick()
                dragging = false
                parent.requestDisallowInterceptTouchEvent(false)
                position()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent.requestDisallowInterceptTouchEvent(false)
                position()
            }
        }
        return true
    }

    fun save() = Bundle().apply { putBoolean("hidden", hidden); putFloat("movement", movement) }
    fun restore(state: Bundle?) {
        hidden = state?.getBoolean("hidden") ?: false
        movement = state?.getFloat("movement") ?: 0f
        running = BaseReadAloudService.isRun
        wasFollowing = ReadAloud.followReadAloudPosition
    }
    fun dispose() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        bar.removeOnLayoutChangeListener(layoutListener)
        parent.removeOnLayoutChangeListener(layoutListener)
    }
}
