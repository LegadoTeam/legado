package io.legado.app.service

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
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
        private const val RESULT_NOTIFICATION_DELAY = 100L
        private const val EXTRA_DOWNLOAD_ID = "io.legado.app.download.id"
        private const val EXTRA_DOWNLOAD_URL = "io.legado.app.download.url"
        private const val EXTRA_DOWNLOAD_FILE_NAME = "io.legado.app.download.file_name"
        private const val EXTRA_RESULT_NOTIFICATION = "io.legado.app.download.result"
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
        downloads.values.forEach { downloadInfo ->
            if (downloadInfo.state == DownloadState.COMPLETED ||
                downloadInfo.state == DownloadState.FAILED
            ) {
                notificationManager.cancel(downloadInfo.notificationId)
                updateResultNotification(downloadInfo)
            } else if (downloadInfo.isPromoted) {
                notificationManager.cancel(downloadInfo.notificationId)
            }
        }
        unregisterReceiver(downloadReceiver)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> startDownload(
                intent.getStringExtra("url"),
                intent.getStringExtra("fileName"),
                intent.getBooleanExtra("isAppUpdate", false),
                intent.getIntExtra("notificationId", 0)
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
                if (!intent.getBooleanExtra(EXTRA_RESULT_NOTIFICATION, false)) {
                    cancelDownload(downloadId)
                }
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
    private fun startDownload(
        url: String?,
        fileName: String?,
        isAppUpdate: Boolean,
        preferredNotificationId: Int = 0
    ) {
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
                DownloadInfo(
                    downloadId,
                    url,
                    fileName,
                    allocateNotificationId(url, fileName, preferredNotificationId),
                    isAppUpdate
                )
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
    private fun cancelDownload(downloadId: Long) {
        val downloadInfo = downloads[downloadId]
        if (downloadInfo == null) return
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
            delay(TERMINAL_NOTIFICATION_DURATION + RESULT_NOTIFICATION_DELAY)
            finishDownload(downloadId)
        }
    }

    @Synchronized
    private fun finishDownload(downloadId: Long) {
        val downloadInfo = downloads.remove(downloadId) ?: return
        terminalJobs.remove(downloadId)
        notificationManager.cancel(downloadInfo.notificationId)
        if (downloadInfo.state == DownloadState.COMPLETED ||
            downloadInfo.state == DownloadState.FAILED
        ) {
            updateResultNotification(downloadInfo)
        }
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

    private fun updateResultNotification(downloadInfo: DownloadInfo) {
        val statusText = when (downloadInfo.state) {
            DownloadState.COMPLETED -> getString(R.string.download_success)
            DownloadState.FAILED -> getString(R.string.download_error)
            else -> return
        }
        upDownloadNotification(
            downloadInfo,
            statusText,
            statusText,
            terminal = false,
            result = true
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
        terminal: Boolean,
        result: Boolean = false
    ): Boolean {
        val notificationBuilder = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setSubText(getString(R.string.action_download))
            .setContentTitle(contentText)
            .setContentText(downloadInfo.fileName)
            .setOnlyAlertOnce(true)
            .apply {
                if (result || downloadInfo.state == DownloadState.COMPLETED) {
                    val action = if (result && downloadInfo.state == DownloadState.FAILED) {
                        IntentAction.start
                    } else {
                        IntentAction.play
                    }
                    setContentIntent(
                        servicePendingIntent<DownloadService>(action, downloadInfo.id.toInt()) {
                            putExtra("downloadId", downloadInfo.id)
                            putExtra("fileName", downloadInfo.fileName)
                            if (action == IntentAction.start) {
                                putExtra("url", downloadInfo.url)
                                putExtra("isAppUpdate", downloadInfo.isAppUpdate)
                                putExtra("notificationId", downloadInfo.notificationId)
                            }
                        }
                    )
                }
            }
            .addExtras(Bundle().apply {
                putLong(EXTRA_DOWNLOAD_ID, downloadInfo.id)
                putString(EXTRA_DOWNLOAD_URL, downloadInfo.url)
                putString(EXTRA_DOWNLOAD_FILE_NAME, downloadInfo.fileName)
                putBoolean(EXTRA_RESULT_NOTIFICATION, result)
            })
            .setDeleteIntent(
                servicePendingIntent<DownloadService>(IntentAction.stop, downloadInfo.id.toInt()) {
                    putExtra("downloadId", downloadInfo.id)
                    putExtra("notificationId", downloadInfo.notificationId)
                    putExtra(EXTRA_RESULT_NOTIFICATION, result)
                }
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(downloadInfo.startTime)
            .apply {
                if (!result) setGroup(groupKey)
            }
            .apply {
                if (terminal) setTimeoutAfter(TERMINAL_NOTIFICATION_DURATION)
            }
        val promoted = notificationBuilder.applyPromotedProgress(
            this,
            AppConst.channelIdDownload,
            eligible = !result,
            ongoing = !result,
            max = downloadInfo.max,
            progress = downloadInfo.progress,
            criticalText = criticalText,
            terminal = terminal
        )
        if (!terminal && !result) {
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
        if (result) {
            notificationBuilder.setAutoCancel(true)
        }
        notificationManager.notify(downloadInfo.notificationId, notificationBuilder.build())
        return promoted
    }

    private fun allocateNotificationId(
        url: String,
        fileName: String,
        preferredNotificationId: Int = 0
    ): Int {
        val oldTerminal = downloads.values.firstOrNull {
            it.url == url && it.fileName == fileName && it.state != DownloadState.ACTIVE
        }
        if (oldTerminal != null) {
            terminalJobs.remove(oldTerminal.id)?.cancel()
            downloads.remove(oldTerminal.id)
            notificationManager.cancel(oldTerminal.notificationId)
            return oldTerminal.notificationId
        }
        val oldResult = notificationManager.activeNotifications.firstOrNull { item ->
            val extras = item.notification.extras
            extras.getBoolean(EXTRA_RESULT_NOTIFICATION) &&
                extras.getString(EXTRA_DOWNLOAD_URL) == url &&
                extras.getString(EXTRA_DOWNLOAD_FILE_NAME) == fileName
        }
        if (oldResult != null) {
            notificationManager.cancel(oldResult.id)
            return oldResult.id
        }
        if (preferredNotificationId > 0 && notificationManager.activeNotifications.none {
                it.id == preferredNotificationId
            }) {
            return preferredNotificationId
        }
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
