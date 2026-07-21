package io.legado.app.ui.widget.dialog

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogSleepTimerBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.service.MAX_CHAPTER_STOP_COUNT
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.setLayout
import io.legado.app.utils.showSoftInput
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

class SleepTimerDialog : BaseDialogFragment(R.layout.dialog_sleep_timer) {

    interface CallBack {
        fun onSleepTimerMinute(minute: Int)
        fun onSleepTimerChapter(count: Int)
    }

    private val binding by viewBinding(DialogSleepTimerBinding::bind)
    private val callBack: CallBack?
        get() = (parentFragment as? CallBack) ?: (activity as? CallBack)
    private var minute = 0
    private var chapter = 0
    private var useEpisodes = false
    private var customChapterMode = false

    private val timeOptions: List<TextView>
        get() = binding.run { listOf(tvTimeP1, tvTimeP2, tvTimeP3, tvTimeP4) }

    private val chapterOptions: List<TextView>
        get() = binding.run { listOf(tvChapterP1, tvChapterP2, tvChapterP3, tvChapterP4) }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        minute = arguments?.getInt(ARG_MINUTE)?.coerceIn(0, MAX_MINUTES) ?: 0
        chapter = arguments?.getInt(ARG_CHAPTER)?.coerceIn(0, MAX_CHAPTER_STOP_COUNT) ?: 0
        useEpisodes = arguments?.getBoolean(ARG_EPISODES) == true

        binding.run {
            tvChapterLabel.setText(
                if (useEpisodes) R.string.sleep_timer_by_episode
                else R.string.sleep_timer_by_chapter
            )
            timeOptions.forEachIndexed { index, option ->
                val value = TIME_PRESETS[index]
                option.text = getString(R.string.sleep_timer_minute_short, value)
                option.setOnClickListener { applyMinute(value, save = false) }
            }
            chapterOptions.forEachIndexed { index, option ->
                val value = CHAPTER_PRESETS[index]
                option.text = getString(
                    if (useEpisodes) R.string.sleep_timer_episode_short
                    else R.string.sleep_timer_chapter_short,
                    value,
                )
                option.setOnClickListener { applyChapter(value, save = false) }
            }
            tvTimeCustom.setOnClickListener { showCustomInput(chapterMode = false) }
            tvChapterCustom.setOnClickListener { showCustomInput(chapterMode = true) }
            tvCustomOk.setOnClickListener { applyCustomInput() }
            etCustom.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    applyCustomInput()
                    true
                } else {
                    false
                }
            }
            ivOff.setOnClickListener { applyMinute(0, save = false) }
        }
        bindCurrentState()
    }

    private fun bindCurrentState() = binding.run {
        val selected = when {
            chapter > 0 -> {
                tvStatus.text = getString(
                    if (useEpisodes) R.string.audio_stop_chapters
                    else R.string.sleep_timer_chapters,
                    chapter,
                )
                chapterOptions.getOrNull(CHAPTER_PRESETS.indexOf(chapter)) ?: tvChapterCustom
            }

            minute > 0 -> {
                tvStatus.text = getString(R.string.sleep_timer_status_time, minute)
                timeOptions.getOrNull(TIME_PRESETS.indexOf(minute)) ?: tvTimeCustom
            }

            else -> {
                tvStatus.setText(R.string.sleep_timer_status_none)
                null
            }
        }
        selectOption(selected)
        ivOff.isEnabled = selected != null
        ivOff.alpha = if (selected == null) 0.38f else 1f
    }

    private fun showCustomInput(chapterMode: Boolean) = binding.run {
        if (llCustomInput.isVisible && customChapterMode == chapterMode) {
            etCustom.showSoftInput()
            return@run
        }
        customChapterMode = chapterMode
        selectOption(if (chapterMode) tvChapterCustom else tvTimeCustom)
        llCustomInput.visible()
        etCustom.setHint(
            when {
                chapterMode && useEpisodes -> R.string.sleep_timer_episode_hint
                chapterMode -> R.string.sleep_timer_chapter_hint
                else -> R.string.sleep_timer_minute_hint
            }
        )
        val prefKey = if (chapterMode) PreferKey.lastSleepChapter else PreferKey.lastSleepTimer
        val current = if (chapterMode) chapter else minute
        val value = requireContext().getPrefInt(prefKey, 0).takeIf { it > 0 }
            ?: current.takeIf { it > 0 }
        etCustom.setText(value?.toString().orEmpty())
        etCustom.setSelection(etCustom.text?.length ?: 0)
        etCustom.showSoftInput()
    }

    private fun applyCustomInput() {
        val value = binding.etCustom.text.toString().toIntOrNull()
        val max = if (customChapterMode) MAX_CHAPTER_STOP_COUNT else MAX_MINUTES
        if (value == null || value !in 1..max) {
            requireContext().toastOnUi(getString(R.string.sleep_timer_range_hint, max))
            return
        }
        if (customChapterMode) {
            applyChapter(value, save = true)
        } else {
            applyMinute(value, save = true)
        }
    }

    private fun selectOption(selected: TextView?) {
        (timeOptions + chapterOptions + listOf(binding.tvTimeCustom, binding.tvChapterCustom))
            .forEach { it.isSelected = it === selected }
    }

    private fun applyMinute(minute: Int, save: Boolean) {
        if (minute > 0) AppConfig.sleepTimerPreferChapter = false
        if (save) requireContext().putPrefInt(PreferKey.lastSleepTimer, minute)
        callBack?.onSleepTimerMinute(minute)
        dismissAllowingStateLoss()
    }

    private fun applyChapter(chapter: Int, save: Boolean) {
        if (chapter > 0) AppConfig.sleepTimerPreferChapter = true
        if (save) requireContext().putPrefInt(PreferKey.lastSleepChapter, chapter)
        callBack?.onSleepTimerChapter(chapter)
        dismissAllowingStateLoss()
    }

    companion object {
        private const val ARG_MINUTE = "minute"
        private const val ARG_CHAPTER = "chapter"
        private const val ARG_EPISODES = "episodes"
        private const val MAX_MINUTES = 180
        private val TIME_PRESETS = intArrayOf(15, 30, 45, 60)
        private val CHAPTER_PRESETS = intArrayOf(1, 2, 3, 5)

        fun newInstance(
            minute: Int,
            chapter: Int,
            useEpisodes: Boolean = false,
        ) = SleepTimerDialog().apply {
            arguments = Bundle().apply {
                putInt(ARG_MINUTE, minute)
                putInt(ARG_CHAPTER, chapter)
                putBoolean(ARG_EPISODES, useEpisodes)
            }
        }
    }
}
