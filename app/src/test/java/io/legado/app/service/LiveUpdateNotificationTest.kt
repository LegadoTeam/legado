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
        assertTrue(service.contains("DownloadInfo(url, fileName, allocateNotificationId()"))
        assertTrue(service.contains("notificationManager.activeNotifications"))
        assertTrue(service.contains("?: fallbackNotificationId"))
        assertTrue(service.contains("putExtra(\"notificationId\", notificationId)"))
        assertTrue(service.contains("downloads.values.filter { it.isPromoted }"))
        assertTrue(service.contains("stopSelfResult(startId)"))
        assertEquals(
            1,
            Regex("""notificationManager\.notify\(""").findAll(service).count()
        )
        assertTrue(service.contains("isActiveDownloadStatus(statusCode)"))
        assertTrue(helper.contains("setRequestPromotedOngoing(true)"))
        assertTrue(helper.contains("setOngoing(true)"))
        assertTrue(helper.contains("setProgress(max, boundedProgress, false)"))
        assertTrue(helper.contains("setProgress(0, 0, true)"))
        assertTrue(helper.contains("setColorized(false)"))
        assertTrue(helper.contains("build().hasPromotableCharacteristics()"))
        assertFalse(helper.contains("SDK_INT_FULL"))
        assertTrue(download.contains("isAppUpdate: Boolean = false"))
        assertTrue(settings.contains("canConfigurePromotedNotifications()"))
        assertTrue(settings.contains("canPostPromotedNotifications() ||"))
        assertTrue(settings.contains("promotedNotificationSettingsIntent().resolveActivity"))
        assertTrue(settings.contains("intent.resolveActivity(requireContext().packageManager)"))
        assertTrue(settings.contains("putPrefBoolean(PreferKey.liveUpdateNotifications, false)"))
        assertTrue(updateDialog.contains("isAppUpdate = true"))
    }

    private fun source(path: String): String {
        return listOf(File(path), File("../$path"))
            .first { it.isFile }
            .readText()
            .replace("\r\n", "\n")
    }
}
