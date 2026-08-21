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
    private val groupKey = "${appCtx.packageName}.download"
    private val downloads = hashMapOf<Long, DownloadInfo>()
    private val completeDownloads = hashSetOf<Long>()
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
                if (completeDownloads.contains(id)) {
                    openDownload(id, downloads[id]?.fileName)
                } else {
                    toastOnUi("未完成,下载的文件夹Download")
                }
            }

            IntentAction.stop -> {
                val downloadId = intent.getLongExtra("downloadId", 0)
                val notificationId = intent.getIntExtra("notificationId", 0)
                removeDownload(downloadId, notificationId)
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
        if (downloads.values.any { it.url == url }) {
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
                DownloadInfo(url, fileName, allocateNotificationId(), isAppUpdate)
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
    private fun removeDownload(downloadId: Long, fallbackNotificationId: Int = 0) {
        val notificationId = downloads.remove(downloadId)?.notificationId ?: fallbackNotificationId
        if (!completeDownloads.contains(downloadId)) {
            downloadManager.remove(downloadId)
        }
        completeDownloads.remove(downloadId)
        if (notificationId > 0) {
            notificationManager.cancel(notificationId)
        }
    }

    /**
     * 下载成功
     */
    @Synchronized
    private fun successDownload(downloadId: Long) {
        if (!completeDownloads.contains(downloadId)) {
            completeDownloads.add(downloadId)
            val fileName = downloads[downloadId]?.fileName
            openDownload(downloadId, fileName)
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
            stopSelf()
            return
        }
        val ids = downloads.keys
        val query = DownloadManager.Query()
        query.setFilterById(*ids.toLongArray())
        downloadManager.query(query).use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val progressIndex =
                    cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val fileSizeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                do {
                    val id = cursor.getLong(idIndex)
                    val progress = cursor.getInt(progressIndex)
                    val max = cursor.getInt(fileSizeIndex)
                    val statusCode = cursor.getInt(statusIndex)
                    val status = when (statusCode) {
                        DownloadManager.STATUS_PAUSED -> getString(R.string.pause)
                        DownloadManager.STATUS_PENDING -> getString(R.string.wait_download)
                        DownloadManager.STATUS_RUNNING -> getString(R.string.downloading)
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            successDownload(id)
                            getString(R.string.download_success)
                        }

                        DownloadManager.STATUS_FAILED -> getString(R.string.download_error)
                        else -> getString(R.string.unknown_state)
                    }
                    downloads[id]?.let { downloadInfo ->
                        downloadInfo.isPromoted = upDownloadNotification(
                            id,
                            downloadInfo.notificationId,
                            "${downloadInfo.fileName} $status",
                            max,
                            progress,
                            downloadInfo.startTime,
                            downloadInfo.isAppUpdate,
                            isActiveDownloadStatus(statusCode)
                        )
                    }
                } while (cursor.moveToNext())
            }
        }
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
        downloadId: Long,
        notificationId: Int,
        content: String,
        max: Int,
        progress: Int,
        startTime: Long,
        isAppUpdate: Boolean,
        isOngoing: Boolean
    ): Boolean {
        val notificationBuilder = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setSubText(getString(R.string.action_download))
            .setContentTitle(content)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                servicePendingIntent<DownloadService>(IntentAction.play, downloadId.toInt()) {
                    putExtra("downloadId", downloadId)
                }
            )
            .setDeleteIntent(
                servicePendingIntent<DownloadService>(IntentAction.stop, downloadId.toInt()) {
                    putExtra("downloadId", downloadId)
                    putExtra("notificationId", notificationId)
                }
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(groupKey)
            .setWhen(startTime)
        val promoted = notificationBuilder.applyPromotedProgress(
            this,
            AppConst.channelIdDownload,
            isAppUpdate,
            isOngoing,
            max,
            progress
        )
        if (promoted) {
            notificationBuilder.addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<DownloadService>(IntentAction.stop, downloadId.toInt()) {
                    putExtra("downloadId", downloadId)
                    putExtra("notificationId", notificationId)
                }
            )
        } else if (progress < max) {
            notificationBuilder.setProgress(max, progress, false)
        }
        notificationManager.notify(notificationId, notificationBuilder.build())
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
        val url: String,
        val fileName: String,
        val notificationId: Int,
        val isAppUpdate: Boolean,
        val startTime: Long = System.currentTimeMillis(),
        var isPromoted: Boolean = false
    )

}
