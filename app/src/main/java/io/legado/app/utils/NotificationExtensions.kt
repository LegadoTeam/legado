package io.legado.app.utils

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.legado.app.help.config.AppConfig

internal const val ANDROID_16_QPR2_SDK_INT_FULL = 3_600_001

internal fun supportsPromotedNotifications(sdkInt: Int): Boolean = sdkInt >= 36

internal fun supportsPromotedNotifications(): Boolean =
    supportsPromotedNotifications(Build.VERSION.SDK_INT)

internal fun usesExplicitPromotedNotificationContract(
    sdkInt: Int,
    sdkIntFull: Int
): Boolean = sdkInt >= 36 && sdkIntFull >= ANDROID_16_QPR2_SDK_INT_FULL

internal fun usesExplicitPromotedNotificationContract(): Boolean {
    if (Build.VERSION.SDK_INT < 36) return false
    return usesExplicitPromotedNotificationContract(
        Build.VERSION.SDK_INT,
        Build.VERSION.SDK_INT_FULL
    )
}

internal fun isPromotableNotificationChannel(importance: Int): Boolean =
    importance > NotificationManager.IMPORTANCE_MIN

internal fun shouldPromoteProgressNotification(
    enabled: Boolean,
    allowed: Boolean,
    eligible: Boolean,
    ongoing: Boolean
): Boolean = enabled && allowed && eligible && ongoing

internal fun progressPercent(progress: Int, max: Int): Int? {
    if (max <= 0) return null
    return (progress.coerceIn(0, max).toLong() * 100 / max).toInt()
}

internal fun NotificationCompat.Builder.applyPromotedProgress(
    context: Context,
    channelId: String,
    eligible: Boolean,
    ongoing: Boolean,
    max: Int,
    progress: Int
): Boolean {
    if (!supportsPromotedNotifications()) return false
    val enabled = AppConfig.liveUpdateNotifications
    val notificationManager = NotificationManagerCompat.from(context)
    val allowed = enabled && notificationManager.canPostPromotedNotifications()
    val channelImportance = notificationManager.getNotificationChannel(channelId)?.importance
        ?: NotificationManager.IMPORTANCE_NONE
    if (!shouldPromoteProgressNotification(
            enabled,
            allowed,
            eligible,
            ongoing
        ) || !isPromotableNotificationChannel(channelImportance)
    ) return false

    val style = NotificationCompat.ProgressStyle()
    if (max > 0) {
        style.addProgressSegment(NotificationCompat.ProgressStyle.Segment(max))
            .setProgress(progress.coerceIn(0, max))
        setShortCriticalText("${progressPercent(progress, max)}%")
    } else {
        style.setProgressIndeterminate(true)
    }
    setStyle(style)
        .setOngoing(true)
    if (usesExplicitPromotedNotificationContract()) {
        setRequestPromotedOngoing(true)
        setColorized(false)
    } else {
        setColorized(true)
    }
    return true
}
