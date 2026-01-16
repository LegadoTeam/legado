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
 * 背景音乐设置对话框
 * 修改点：位置居中、标题文案更新、样式优化
 */
class BgmConfigDialog : BaseDialogFragment(R.layout.dialog_bgm_config) {

    private val binding by viewBinding(DialogBgmConfigBinding::bind)

    // SAF 目录选择
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
            // 居中弹窗通常需要背景变暗，这里设置暗度
            val attr = attributes
            attr.dimAmount = 0.5f  
            attr.gravity = Gravity.CENTER // 【修改】从 BOTTOM 改为 CENTER
            attributes = attr
            
            // 设置透明背景，以支持 XML 中定义的圆角
            setBackgroundDrawableResource(R.color.transparent)
            
            // 【修改】居中弹窗宽度不建议 MATCH_PARENT，改为屏幕宽度的 90%
            setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? ReadBookActivity)?.let {
            if (it.bottomDialog > 0) it.bottomDialog--
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        if (activity is ReadBookActivity) {
            (activity as ReadBookActivity).bottomDialog++
        }

        // 动态配色
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)

        binding.run {
            rootView.setBackgroundColor(bg)
            // 【修改】显式设置标题文字
            tvTitle.text = "背景音乐设置" 
            
            switchBgm.setTextColor(textColor)
            tvTitle.setTextColor(textColor)
            tvPathLabel.setTextColor(textColor)
            tvPath.setTextColor(textColor)
            btnSelectFolder.setTextColor(textColor)
            tvVolLabel.setTextColor(textColor)
            tvVolumeValue.setTextColor(textColor)
            ivPrev.setColorFilter(textColor)
            ivPlayPause.setColorFilter(textColor)
            ivNext.setColorFilter(textColor)
            switchBgm.setTextColor(textColor)
        }

        initData()
        initEvent()
    }

    private fun initData() = binding.run {
        switchBgm.isChecked = AppConfig.isBgmEnabled
        tvPath.text = AppConfig.bgmPath?.substringAfterLast("/") ?: "未选择"
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

        ivPrev.setOnClickListener { BgmManager.prev(); upPlayState() }
        ivNext.setOnClickListener { BgmManager.next(); upPlayState() }
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
