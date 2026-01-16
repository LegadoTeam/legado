package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.SeekBar
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogBgmConfigBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.service.BgmManager
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 背景音乐 (BGM) 设置对话框
 * 风格完全对齐 ktouls-legado-new
 */
class BgmConfigDialog : BaseDialogFragment(R.layout.dialog_bgm_config) {

    private val binding by viewBinding(DialogBgmConfigBinding::bind)

    // 标准 SAF 目录选择器
    private val selectDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            AppConfig.bgmPath = uri.toString()
            binding.tvPath.text = uri.path?.substringAfterLast("/") ?: "已选择"
            BgmManager.loadBgmFiles()
            if (AppConfig.isBgmEnabled) {
                BgmManager.play()
                upPlayState()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(R.color.background)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? ReadBookActivity)?.bottomDialog?.let { if (it > 0) (activity as ReadBookActivity).bottomDialog-- }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val activity = activity as? ReadBookActivity
        if (activity != null) {
            val bottomDialog = activity.bottomDialog++
            if (bottomDialog > 0) {
                dismiss()
                return
            }
        }

        // 动态配色逻辑：与 ReadAloudDialog 风格完全一致
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)

        binding.run {
            rootView.setBackgroundColor(bg)
            switchBgm.setTextColor(textColor)
            tvPath.setTextColor(textColor)
            btnSelectFolder.setTextColor(textColor)
            ivVolumeIcon.setColorFilter(textColor)
            tvVolumeValue.setTextColor(textColor)
            ivPrev.setColorFilter(textColor)
            ivPlayPause.setColorFilter(textColor)
            ivNext.setColorFilter(textColor)
        }

        initData()
        initEvent()
    }

    private fun initData() = binding.run {
        switchBgm.isChecked = AppConfig.isBgmEnabled
        tvPath.text = AppConfig.bgmPath?.substringAfterLast("/") ?: "未选择文件夹"
        seekVolume.progress = AppConfig.bgmVolume
        tvVolumeValue.text = "${AppConfig.bgmVolume}%"
        upPlayState()
    }

    private fun initEvent() = binding.run {
        switchBgm.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.isBgmEnabled = isChecked
            if (isChecked) BgmManager.play() else BgmManager.pause()
            upPlayState()
        }

        btnSelectFolder.setOnClickListener {
            selectDir.launch { mode = HandleFileContract.DIR }
        }

        seekVolume.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    BgmManager.setVolume(progress)
                    tvVolumeValue.text = "$progress%"
                }
            }
        })

        ivPrev.setOnClickListener {
            BgmManager.prev()
            upPlayState()
        }

        ivNext.setOnClickListener {
            BgmManager.next()
            upPlayState()
        }

        ivPlayPause.setOnClickListener {
            if (BgmManager.isPlaying()) {
                BgmManager.pause()
                ivPlayPause.setImageResource(R.drawable.ic_play_24dp)
            } else {
                BgmManager.play()
                ivPlayPause.setImageResource(R.drawable.ic_pause_24dp)
            }
        }
    }

    private fun upPlayState() {
        if (BgmManager.isPlaying()) {
            binding.ivPlayPause.setImageResource(R.drawable.ic_pause_24dp)
        } else {
            binding.ivPlayPause.setImageResource(R.drawable.ic_play_24dp)
        }
    }
}
