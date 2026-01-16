package io.legado.app.service

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.*
import splitties.init.appCtx
import java.io.File

/**
 * 背景音乐管理器
 * 负责音频播放、文件夹扫描及音量淡入淡出逻辑
 */
object BgmManager {

    private var exoPlayer: ExoPlayer? = null
    private val audioExtensions = arrayOf("mp3", "wav", "ogg", "flac", "m4a", "aac")
    private var playlist: MutableList<MediaItem> = mutableListOf()
    
    // 协程控制音量动画
    private var fadeJob: Job? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)

    /**
     * 初始化播放器
     */
    fun init(context: Context) {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                shuffleModeEnabled = false
                volume = 0f // 初始音量设为0，等待播放时淡入
            }
        }
    }

    /**
     * 音量平滑过渡动画
     * @param targetVolume 目标音量 (0.0 - 1.0)
     * @param duration 持续时间 (毫秒)
     * @param onComplete 动画完成后的回调
     */
    private fun animateVolume(targetVolume: Float, duration: Long = 500L, onComplete: (() -> Unit)? = null) {
        fadeJob?.cancel()
        val startVolume = exoPlayer?.volume ?: 0f
        
        fadeJob = mainScope.launch {
            val steps = 20
            val interval = duration / steps
            val delta = (targetVolume - startVolume) / steps
            
            for (i in 1..steps) {
                delay(interval)
                exoPlayer?.volume = startVolume + delta * i
            }
            exoPlayer?.volume = targetVolume
            onComplete?.invoke()
        }
    }

    /**
     * 加载音乐文件夹
     */
    fun loadBgmFiles() {
        val uriStr = AppConfig.bgmPath
        if (uriStr.isNullOrBlank()) return
        
        playlist.clear()
        try {
            if (uriStr.startsWith("content://")) {
                // SAF 文件夹处理
                val docFile = DocumentFile.fromTreeUri(appCtx, Uri.parse(uriStr))
                docFile?.listFiles()?.forEach { file ->
                    if (file.isFile && isAudioFile(file.name)) {
                        playlist.add(MediaItem.fromUri(file.uri))
                    }
                }
            } else {
                // 普通文件路径处理
                val dir = File(uriStr)
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { file ->
                        if (file.isFile && isAudioFile(file.name)) {
                            playlist.add(MediaItem.fromUri(Uri.fromFile(file)))
                        }
                    }
                }
            }

            if (playlist.isNotEmpty()) {
                playlist.shuffle() // 自动洗牌
                exoPlayer?.setMediaItems(playlist)
                exoPlayer?.prepare()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isAudioFile(name: String?): Boolean {
        return name != null && audioExtensions.any { name.endsWith(".$it", true) }
    }

    /**
     * 开始播放（带淡入）
     */
    fun play() {
        if (!AppConfig.isBgmEnabled) return
        if (playlist.isEmpty()) {
            loadBgmFiles()
        }
        if (playlist.isNotEmpty() && exoPlayer?.isPlaying == false) {
            exoPlayer?.play()
            animateVolume(AppConfig.bgmVolume / 100f)
        }
    }

    /**
     * 暂停（带淡出）
     */
    fun pause() {
        if (exoPlayer?.isPlaying == true) {
            animateVolume(0f, onComplete = {
                exoPlayer?.pause()
            })
        }
    }

    /**
     * 下一首（淡出后切换再淡入）
     */
    fun next() {
        animateVolume(0f, duration = 300L, onComplete = {
            if (exoPlayer?.hasNextMediaItem() == true) {
                exoPlayer?.seekToNextMediaItem()
            } else {
                exoPlayer?.seekToDefaultPosition(0)
            }
            animateVolume(AppConfig.bgmVolume / 100f, duration = 500L)
        })
    }

    /**
     * 上一首
     */
    fun prev() {
        animateVolume(0f, duration = 300L, onComplete = {
            if (exoPlayer?.hasPreviousMediaItem() == true) {
                exoPlayer?.seekToPreviousMediaItem()
            }
            animateVolume(AppConfig.bgmVolume / 100f, duration = 500L)
        })
    }

    /**
     * 实时调整音量
     */
    fun setVolume(progress: Int) {
        AppConfig.bgmVolume = progress
        fadeJob?.cancel() 
        exoPlayer?.volume = progress / 100f
    }

    /**
     * 释放资源
     */
    fun release() {
        fadeJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
        playlist.clear()
    }

    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying == true
    }
}
