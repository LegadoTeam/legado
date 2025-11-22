package io.legado.app.ui.video.config

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogVideoSettingsBinding
import io.legado.app.model.VideoPlay
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.init.appCtx

class SettingsDialog(private val callBack: CallBack? = null) :
    BaseDialogFragment(R.layout.dialog_video_settings) {
    companion object {
        private val pressSpeedStr by lazy { appCtx.getString(R.string.press_speed) + " " }
    }
    private val binding by viewBinding(DialogVideoSettingsBinding::bind)

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initData()
        initView()
    }

    @SuppressLint("SetTextI18n")
    private fun initData() {
        binding.run {
            tvPressSpeed.text = pressSpeedStr + VideoPlay.longPressSpeed / 10.0
            cbAutoPlay.isChecked = VideoPlay.autoPlay
            cbStartFull.isChecked = VideoPlay.startFull
            cbFullBottomProgress.isChecked = VideoPlay.fullBottomProgressBar
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initView() {
        binding.run {
            cbAutoPlay.setOnCheckedChangeListener { _, isChecked ->
                VideoPlay.autoPlay = isChecked
                ctStartFull.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
            cbStartFull.setOnCheckedChangeListener { _, isChecked ->
                VideoPlay.startFull = isChecked
            }
            cbFullBottomProgress.setOnCheckedChangeListener { _, isChecked ->
                VideoPlay.fullBottomProgressBar = isChecked
            }
            tvPressSpeed.setOnClickListener { _ ->
                NumberPickerDialog(requireContext(), true)
                    .setTitle(getString(R.string.press_speed))
                    .setMaxValue(60)
                    .setMinValue(5)
                    .setValue(VideoPlay.longPressSpeed)
                    .setCustomButton((R.string.btn_default_s)) {
                        VideoPlay.longPressSpeed = 30
                        tvPressSpeed.text = pressSpeedStr + 3.0
                    }
                    .show {
                        VideoPlay.longPressSpeed = it
                        tvPressSpeed.text = pressSpeedStr + it / 10.0
                    }
            }
        }
    }

    interface CallBack {
//        fun upUi()
    }

}