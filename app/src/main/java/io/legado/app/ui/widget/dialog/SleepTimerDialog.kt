package io.legado.app.ui.widget.dialog

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogSleepTimerBinding
import io.legado.app.service.MAX_CHAPTER_STOP_COUNT
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

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
    private var chapterMode = false

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        minute = arguments?.getInt(ARG_MINUTE)?.coerceIn(0, MAX_MINUTES) ?: 0
        chapter = arguments?.getInt(ARG_CHAPTER)?.coerceIn(0, MAX_CHAPTER_STOP_COUNT) ?: 0
        chapterMode = chapter > 0

        binding.run {
            rbTime.isChecked = !chapterMode
            rbChapter.isChecked = chapterMode
            rgMode.setOnCheckedChangeListener { _, checkedId ->
                bindMode(checkedId == R.id.rb_chapter)
            }
            seekValue.setOnSeekBarChangeListener(object : SeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (chapterMode) chapter = progress else minute = progress
                    upValueText()
                }
            })
            tvCancel.setOnClickListener { dismissAllowingStateLoss() }
            tvOk.setOnClickListener {
                if (chapterMode) {
                    callBack?.onSleepTimerChapter(chapter)
                } else {
                    callBack?.onSleepTimerMinute(minute)
                }
                dismissAllowingStateLoss()
            }
        }
        bindMode(chapterMode)
    }

    private fun bindMode(useChapter: Boolean) = binding.run {
        chapterMode = useChapter
        seekValue.max = if (chapterMode) MAX_CHAPTER_STOP_COUNT else MAX_MINUTES
        seekValue.progress = if (chapterMode) chapter else minute
        upValueText()
    }

    private fun upValueText() {
        binding.tvValue.text = if (chapterMode) {
            getString(R.string.sleep_timer_chapters, chapter)
        } else {
            getString(R.string.timer_m, minute)
        }
    }

    companion object {
        private const val ARG_MINUTE = "minute"
        private const val ARG_CHAPTER = "chapter"
        private const val MAX_MINUTES = 180

        fun newInstance(minute: Int, chapter: Int) = SleepTimerDialog().apply {
            arguments = Bundle().apply {
                putInt(ARG_MINUTE, minute)
                putInt(ARG_CHAPTER, chapter)
            }
        }
    }
}
