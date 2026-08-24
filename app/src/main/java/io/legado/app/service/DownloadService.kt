package io.legado.app.service

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.utils.IntentType
import io.legado.app.utils.applyPromotedProgress
import io.legado.app.utils.openFileUri
import io.legado.app.utils.progressPercent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.downloadManager
import splitties.systemservices.notificationManager

internal fun isActiveDownloadStatus(statusCode: Int): Boolean =
    statusCode == DownloadManager.STATUS_PENDING ||
        statusCode == DownloadManager.STATUS_PAUSED ||
        statusCode == DownloadManager.STATUS_RUNNING

/**
 * 下载文件
 */
class DownloadService : BaseService() {
    companion object {
        private const val TERMINAL_NOTIFICATION_DURATION = 4_500L
    }

    private enum class DownloadState {
        ACTIVE,
        COMPLETED,
        FAILED,
        CANCELED
    }

    private val groupKey = "${appCtx.packageName}.download"
    private val downloads = hashMapOf<Long, DownloadInfo>()
    private val terminalJobs = hashMapOf<Long, Job>()
    private var nextNotificationId = NotificationId.Download
    private var upStateJob: Job? = null
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            queryState()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onDestroy() {
        upStateJob?.cancel()
        terminalJobs.values.forEach { it.cancel() }
        downloads.values.filter { it.isPromoted }.forEach {
            notificationManager.cancel(it.notificationId)
        }
        unregisterReceiver(downloadReceiver)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> startDownload(
                intent.getStringExtra("url"),
                intent.getStringExtra("fileName"),
                intent.getBooleanExtra("isAppUpdate", false)
            )

            IntentAction.play -> {
                val id = intent.getLongExtra("downloadId", 0)
                val fileName = intent.getStringExtra("fileName")
                when (downloads[id]?.state) {
                    DownloadState.COMPLETED -> openDownload(id, downloads[id]?.fileName)
                    DownloadState.CANCELED -> toastOnUi("下载已取消")
                    DownloadState.FAILED -> toastOnUi("下载失败")
                    null -> if (fileName.isNullOrBlank()) {
                        toastOnUi("未完成,下载的文件夹Download")
                    } else {
                        openDownload(id, fileName)
                    }

                    else -> toastOnUi("未完成,下载的文件夹Download")
                }
            }

            IntentAction.stop -> {
                val downloadId = intent.getLongExtra("downloadId", 0)
                val notificationId = intent.getIntExtra("notificationId", 0)
                cancelDownload(downloadId, notificationId)
            }
        }
        val result = super.onStartCommand(intent, flags, startId)
        if (downloads.isEmpty()) {
            stopSelfResult(startId)
        }
        return result
    }

    /**
     * 开始下载
     */
    @Synchronized
    private fun startDownload(url: String?, fileName: String?, isAppUpdate: Boolean) {
        if (url == null || fileName == null) {
            if (downloads.isEmpty()) {
                stopSelf()
            }
            return
        }
        if (downloads.values.any { it.url == url && it.state == DownloadState.ACTIVE }) {
            toastOnUi("已在下载列表")
            return
        }
        kotlin.runCatching {
            // 指定下载地址
            val request = DownloadManager.Request(Uri.parse(url))
            // 设置通知
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            // 设置下载文件保存的路径和文件名
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            // 添加一个下载任务
            val downloadId = downloadManager.enqueue(request)
            downloads[downloadId] =
                DownloadInfo(downloadId, url, fileName, allocateNotificationId(), isAppUpdate)
            queryState()
            if (upStateJob == null) {
                checkDownloadState()
            }
        }.onFailure {
            it.printStackTrace()
            val msg = when (it) {
                is SecurityException -> "下载出错,没有存储权限"
                else -> "下载出错,${it.localizedMessage}"
            }
            toastOnUi(msg)
            AppLog.put(msg, it)
        }
    }

    /**
     * 取消下载
     */
    @Synchronized
    private fun cancelDownload(downloadId: Long, fallbackNotificationId: Int = 0) {
        val downloadInfo = downloads[downloadId]
        if (downloadInfo == null) {
            if (fallbackNotificationId > 0) {
                notificationManager.cancel(fallbackNotificationId)
            }
            return
        }
        if (downloadInfo.state != DownloadState.ACTIVE) return
        downloadManager.remove(downloadId)
        downloadInfo.state = DownloadState.CANCELED
        updateTerminalNotification(downloadInfo, getString(R.string.download_live_canceled))
        scheduleTerminalCleanup(downloadId)
    }

    /**
     * 下载成功
     */
    @Synchronized
    private fun successDownload(downloadId: Long) {
        val downloadInfo = downloads[downloadId] ?: return
        if (downloadInfo.state != DownloadState.ACTIVE) return
        downloadInfo.state = DownloadState.COMPLETED
        openDownload(downloadId, downloadInfo.fileName)
        updateTerminalNotification(
            downloadInfo,
            if (downloadInfo.isAppUpdate) {
                getString(R.string.download_live_update_completed)
            } else {
                getString(R.string.download_live_completed)
            }
        )
        scheduleTerminalCleanup(downloadId)
    }

    @Synchronized
    private fun failDownload(downloadId: Long) {
        val downloadInfo = downloads[downloadId] ?: return
        if (downloadInfo.state != DownloadState.ACTIVE) return
        downloadInfo.state = DownloadState.FAILED
        updateTerminalNotification(downloadInfo, getString(R.string.download_live_failed))
        scheduleTerminalCleanup(downloadId)
    }

    private fun scheduleTerminalCleanup(downloadId: Long) {
        terminalJobs.remove(downloadId)?.cancel()
        terminalJobs[downloadId] = lifecycleScope.launch {
            delay(TERMINAL_NOTIFICATION_DURATION)
            finishDownload(downloadId)
        }
    }

    @Synchronized
    private fun finishDownload(downloadId: Long) {
        val downloadInfo = downloads.remove(downloadId) ?: return
        terminalJobs.remove(downloadId)
        notificationManager.cancel(downloadInfo.notificationId)
        if (downloads.isEmpty()) {
            stopSelf()
        }
    }

    private fun checkDownloadState() {
        upStateJob?.cancel()
        upStateJob = lifecycleScope.launch {
            while (isActive) {
                queryState()
                delay(1000)
            }
        }
    }

    /**
     * 查询下载进度
     */
    @Synchronized
    private fun queryState() {
        if (downloads.isEmpty()) {
            upStateJob?.cancel()
            upStateJob = null
            stopSelf()
            return
        }
        val activeIds = downloads
            .filterValues { it.state == DownloadState.ACTIVE }
            .keys
        if (activeIds.isEmpty()) {
            upStateJob?.cancel()
            upStateJob = null
            return
        }
        val query = DownloadManager.Query()
        query.setFilterById(*activeIds.toLongArray())
        downloadManager.query(query).use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val progressIndex =
                    cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val fileSizeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                do {
                    val id = cursor.getLong(idIndex)
                    val progress = cursor.getLong(progressIndex)
                        .coerceIn(0L, Int.MAX_VALUE.toLong())
                        .toInt()
                    val max = cursor.getLong(fileSizeIndex)
                        .coerceIn(0L, Int.MAX_VALUE.toLong())
                        .toInt()
                    val statusCode = cursor.getInt(statusIndex)
                    downloads[id]?.let { downloadInfo ->
                        if (downloadInfo.state != DownloadState.ACTIVE) return@let
                        downloadInfo.progress = progress
                        downloadInfo.max = max
                        when (statusCode) {
                            DownloadManager.STATUS_SUCCESSFUL -> successDownload(id)
                            DownloadManager.STATUS_FAILED -> failDownload(id)
                            else -> updateActiveNotification(downloadInfo, statusCode)
                        }
                    }
                } while (cursor.moveToNext())
            }
        }
        if (downloads.values.none { it.state == DownloadState.ACTIVE }) {
            upStateJob?.cancel()
            upStateJob = null
        }
    }

    private fun updateActiveNotification(downloadInfo: DownloadInfo, statusCode: Int) {
        val criticalText = if (downloadInfo.max > 0) {
            getString(
                R.string.download_live_downloading,
                progressPercent(downloadInfo.progress, downloadInfo.max) ?: 0
            )
        } else {
            getString(R.string.download_live_waiting)
        }
        val contentText = when (statusCode) {
            DownloadManager.STATUS_PAUSED -> getString(R.string.pause)
            DownloadManager.STATUS_PENDING -> getString(R.string.wait_download)
            DownloadManager.STATUS_RUNNING -> getString(R.string.downloading)
            else -> getString(R.string.unknown_state)
        }
        downloadInfo.isPromoted = upDownloadNotification(
            downloadInfo,
            contentText,
            criticalText,
            terminal = false
        )
    }

    private fun updateTerminalNotification(downloadInfo: DownloadInfo, statusText: String) {
        downloadInfo.isPromoted = upDownloadNotification(
            downloadInfo,
            statusText,
            statusText,
            terminal = true
        )
    }

    /**
     * 打开下载文件
     */
    private fun openDownload(downloadId: Long, fileName: String?) {
        kotlin.runCatching {
            downloadManager.getUriForDownloadedFile(downloadId)?.let { uri ->
                val type = IntentType.from(fileName)
                openFileUri(uri, type)
            }
        }.onFailure {
            AppLog.put("打开下载文件${fileName}出错", it)
        }
    }

    override fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setSubText(getString(R.string.action_download))
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setOngoing(true)
            .build()
        startForeground(NotificationId.DownloadService, notification)
    }

    /**
     * 更新通知
     */
    private fun upDownloadNotification(
        downloadInfo: DownloadInfo,
        contentText: String,
        criticalText: String,
        terminal: Boolean
    ): Boolean {
        val notificationBuilder = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setSubText(getString(R.string.action_download))
            .setContentTitle(contentText)
            .setContentText(downloadInfo.fileName)
            .setOnlyAlertOnce(true)
            .apply {
                if (downloadInfo.state == DownloadState.COMPLETED) {
                    setContentIntent(
                        servicePendingIntent<DownloadService>(IntentAction.play, downloadInfo.id.toInt()) {
                            putExtra("downloadId", downloadInfo.id)
                            putExtra("fileName", downloadInfo.fileName)
                        }
                    )
                }
            }
            .setDeleteIntent(
                servicePendingIntent<DownloadService>(IntentAction.stop, downloadInfo.id.toInt()) {
                    putExtra("downloadId", downloadInfo.id)
                    putExtra("notificationId", downloadInfo.notificationId)
                }
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(groupKey)
            .setWhen(downloadInfo.startTime)
            .apply {
                if (terminal) setTimeoutAfter(TERMINAL_NOTIFICATION_DURATION)
            }
        val promoted = notificationBuilder.applyPromotedProgress(
            this,
            AppConst.channelIdDownload,
            eligible = true,
            ongoing = true,
            max = downloadInfo.max,
            progress = downloadInfo.progress,
            criticalText = criticalText,
            terminal = terminal
        )
        if (!terminal) {
            notificationBuilder.addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<DownloadService>(IntentAction.stop, downloadInfo.id.toInt()) {
                    putExtra("downloadId", downloadInfo.id)
                    putExtra("notificationId", downloadInfo.notificationId)
                }
            )
            if (!promoted && downloadInfo.progress < downloadInfo.max) {
                notificationBuilder.setProgress(downloadInfo.max, downloadInfo.progress, false)
            }
        }
        notificationManager.notify(downloadInfo.notificationId, notificationBuilder.build())
        return promoted
    }

    private fun allocateNotificationId(): Int {
        val activeIds = notificationManager.activeNotifications.mapTo(hashSetOf()) { it.id }
        while (nextNotificationId in activeIds) {
            nextNotificationId++
        }
        return nextNotificationId++
    }

    private data class DownloadInfo(
        val id: Long,
        val url: String,
        val fileName: String,
        val notificationId: Int,
        val isAppUpdate: Boolean,
        val startTime: Long = System.currentTimeMillis(),
        var progress: Int = 0,
        var max: Int = 0,
        var state: DownloadState = DownloadState.ACTIVE,
        var isPromoted: Boolean = false
    )

}
