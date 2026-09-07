package io.legado.app.service

import android.app.DownloadManager
import android.app.NotificationManager
import io.legado.app.utils.isPromotableNotificationChannel
import io.legado.app.utils.progressPercent
import io.legado.app.utils.shouldPromoteProgressNotification
import io.legado.app.utils.supportsPromotedNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiveUpdateNotificationTest {

    @Test
    fun `promotion requires Android 16 and every eligibility gate`() {
        assertFalse(supportsPromotedNotifications(35))
        assertTrue(supportsPromotedNotifications(36))
        assertTrue(supportsPromotedNotifications(37))

        assertTrue(shouldPromoteProgressNotification(true, true, true, true))
        assertFalse(shouldPromoteProgressNotification(false, true, true, true))
        assertFalse(shouldPromoteProgressNotification(true, false, true, true))
        assertFalse(shouldPromoteProgressNotification(true, true, false, true))
        assertFalse(shouldPromoteProgressNotification(true, true, true, false))
    }

    @Test
    fun `minimum importance channels are not promotable`() {
        assertFalse(isPromotableNotificationChannel(NotificationManager.IMPORTANCE_NONE))
        assertFalse(isPromotableNotificationChannel(NotificationManager.IMPORTANCE_MIN))
        assertTrue(isPromotableNotificationChannel(NotificationManager.IMPORTANCE_LOW))
    }

    @Test
    fun `progress text is bounded and absent without a known total`() {
        assertNull(progressPercent(1, 0))
        assertEquals(0, progressPercent(-1, 100))
        assertEquals(50, progressPercent(50, 100))
        assertEquals(100, progressPercent(101, 100))
    }

    @Test
    fun `only unfinished download states remain promoted`() {
        assertTrue(isActiveDownloadStatus(DownloadManager.STATUS_PENDING))
        assertTrue(isActiveDownloadStatus(DownloadManager.STATUS_PAUSED))
        assertTrue(isActiveDownloadStatus(DownloadManager.STATUS_RUNNING))
        assertFalse(isActiveDownloadStatus(DownloadManager.STATUS_SUCCESSFUL))
        assertFalse(isActiveDownloadStatus(DownloadManager.STATUS_FAILED))
    }

    @Test
    fun `download notification keeps one id and meets promotion contract`() {
        val service = source("app/src/main/java/io/legado/app/service/DownloadService.kt")
        val helper = source("app/src/main/java/io/legado/app/utils/NotificationExtensions.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")
        val settings = source(
            "app/src/main/java/io/legado/app/ui/config/OtherConfigFragment.kt"
        )
        val download = source("app/src/main/java/io/legado/app/model/Download.kt")
        val updateDialog = source("app/src/main/java/io/legado/app/ui/about/UpdateDialog.kt")

        assertTrue(manifest.contains("android.permission.POST_PROMOTED_NOTIFICATIONS"))
        assertTrue(service.contains("private var nextNotificationId = NotificationId.Download"))
        assertTrue(service.contains("allocateNotificationId(url, fileName, preferredNotificationId)"))
        assertTrue(service.contains("notificationManager.activeNotifications"))
        assertTrue(service.contains("putExtra(\"notificationId\", downloadInfo.notificationId)"))
        assertTrue(service.contains("TERMINAL_NOTIFICATION_DURATION = 4_500L"))
        assertTrue(service.contains("DownloadState.CANCELED"))
        assertTrue(service.contains("DownloadState.COMPLETED"))
        assertTrue(service.contains("scheduleTerminalCleanup(downloadId)"))
        assertTrue(service.contains("setTimeoutAfter(TERMINAL_NOTIFICATION_DURATION)"))
        assertTrue(service.contains("stopSelfResult(startId)"))
        assertEquals(
            1,
            Regex("""notificationManager\.notify\(""").findAll(service).count()
        )
        assertTrue(service.contains("eligible = !result"))
        assertTrue(service.contains("criticalText = criticalText"))
        assertTrue(service.contains("setContentText(downloadInfo.fileName)"))
        assertTrue(service.contains("if (!terminal && !result)"))
        assertTrue(service.contains("if (!result) setGroup(groupKey)"))
        assertTrue(service.contains("download_live_update_completed"))
        assertTrue(service.contains("download_live_canceled"))
        assertTrue(service.contains("private fun updateResultNotification(downloadInfo: DownloadInfo)"))
        assertTrue(service.contains("result = true"))
        assertTrue(service.contains("EXTRA_RESULT_NOTIFICATION"))
        assertTrue(service.contains("EXTRA_DOWNLOAD_ID"))
        assertTrue(service.contains("if (!intent.getBooleanExtra(EXTRA_RESULT_NOTIFICATION, false))"))
        assertTrue(service.contains("downloads.values.forEach { downloadInfo ->"))
        assertTrue(service.contains("updateResultNotification(downloadInfo)"))
        assertTrue(service.contains("IntentAction.start"))
        assertTrue(service.contains("putExtra(\"isAppUpdate\", downloadInfo.isAppUpdate)"))
        assertTrue(service.contains("notificationManager.cancel(downloadInfo.notificationId)"))
        assertTrue(service.contains("delay(TERMINAL_NOTIFICATION_DURATION + RESULT_NOTIFICATION_DELAY)"))
        assertTrue(service.contains("TERMINAL_NOTIFICATION_DURATION + RESULT_NOTIFICATION_DELAY"))
        assertTrue(service.contains("preferredNotificationId"))
        assertTrue(service.contains("putExtra(\"notificationId\", downloadInfo.notificationId)"))
        assertTrue(helper.contains("setRequestPromotedOngoing(true)"))
        assertTrue(helper.contains("setOngoing(true)"))
        assertTrue(helper.contains("setProgress(effectiveMax, boundedProgress, false)"))
        assertTrue(helper.contains("setProgress(0, 0, true)"))
        assertTrue(helper.contains("setShortCriticalText(criticalText)"))
        val nonColorized = helper.indexOf("setColorized(false)")
        val firstProbe = helper.indexOf("build().hasPromotableCharacteristics()")
        val colorizedFallback = helper.indexOf("setColorized(true)")
        val secondProbe = helper.lastIndexOf("build().hasPromotableCharacteristics()")
        assertTrue(nonColorized >= 0)
        assertTrue(firstProbe > nonColorized)
        assertTrue(colorizedFallback > firstProbe)
        assertTrue(secondProbe > colorizedFallback)
        assertFalse(helper.contains("SDK_INT_FULL"))
        assertTrue(download.contains("isAppUpdate: Boolean = false"))
        assertTrue(settings.contains("canConfigurePromotedNotifications()"))
        assertTrue(settings.contains("canPostPromotedNotifications() ||"))
        assertTrue(settings.contains("promotedNotificationSettingsIntent().resolveActivity"))
        assertTrue(settings.contains("intent.resolveActivity(requireContext().packageManager)"))
        assertTrue(settings.contains("putPrefBoolean(PreferKey.liveUpdateNotifications, false)"))
        assertTrue(updateDialog.contains("isAppUpdate = true"))
    }

    @Test
    fun `web and MCP services reuse one live notification through stop`() {
        val web = source("app/src/main/java/io/legado/app/service/WebService.kt")
        val mcp = source("app/src/main/java/io/legado/app/service/McpService.kt")
        val updateLog = source("app/src/main/assets/updateLog.md")

        assertTrue(web.contains("applyPromotedProgress("))
        assertTrue(web.contains("eligible = terminal || httpServer?.isAlive == true"))
        assertTrue(web.contains("terminal = terminal"))
        assertTrue(web.contains("startForeground(NotificationId.WebService"))
        assertTrue(web.contains("IntentAction.stop -> stopServiceWithNotification()"))
        assertTrue(web.contains("setTimeoutAfter(TERMINAL_NOTIFICATION_DURATION)"))
        assertTrue(web.contains("terminalStopJob"))
        assertTrue(web.contains("notificationManager.cancel(NotificationId.WebService)"))
        assertTrue(web.contains("if (isRun)"))
        assertTrue(web.contains("createNotification(terminal = stopping)"))

        assertTrue(mcp.contains("applyPromotedProgress("))
        assertTrue(mcp.contains("eligible = terminal || isRun"))
        assertTrue(mcp.contains("terminal = terminal"))
        assertTrue(mcp.contains("startForeground(NotificationId.McpService"))
        assertTrue(mcp.contains("IntentAction.stop -> stopServiceWithNotification()"))
        assertTrue(mcp.contains("setTimeoutAfter(TERMINAL_NOTIFICATION_DURATION)"))
        assertTrue(mcp.contains("terminalStopJob"))
        assertTrue(mcp.contains("notificationManager.cancel(NotificationId.McpService)"))
        assertTrue(mcp.contains("if (isRun)"))
        assertTrue(mcp.contains("createNotification(terminal = stopping)"))

        assertTrue(updateLog.contains("Web 与 MCP 服务支持 Android 16 实时更新通知"))
    }

    @Test
    fun `terminal copy keeps only successful and failed ordinary results`() {
        val service = source("app/src/main/java/io/legado/app/service/DownloadService.kt")
        val strings = source("app/src/main/res/values-zh/strings.xml")

        assertTrue(service.contains("DownloadState.COMPLETED ||"))
        assertTrue(service.contains("DownloadState.FAILED"))
        assertTrue(service.contains("DownloadState.COMPLETED -> getString(R.string.download_success)"))
        assertTrue(service.contains("DownloadState.FAILED -> getString(R.string.download_error)"))
        assertTrue(service.contains("setAutoCancel(true)"))
        assertTrue(strings.contains("<string name=\"download_live_downloading\">%1\$d%%</string>"))
        assertTrue(strings.contains("<string name=\"download_live_waiting\">下载中</string>"))
        assertTrue(strings.contains("<string name=\"download_live_completed\">已完成</string>"))
        assertTrue(strings.contains("<string name=\"download_live_update_completed\">待安装</string>"))
        assertTrue(strings.contains("<string name=\"download_live_canceled\">已取消</string>"))
        assertTrue(strings.contains("<string name=\"download_live_failed\">下载失败</string>"))
    }

    private fun source(path: String): String {
        return listOf(File(path), File("../$path"))
            .first { it.isFile }
            .readText()
            .replace("\r\n", "\n")
    }
}
