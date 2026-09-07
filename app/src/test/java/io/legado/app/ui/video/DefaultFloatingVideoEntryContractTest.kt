package io.legado.app.ui.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DefaultFloatingVideoEntryContractTest {

    @Test
    fun `floating service enters foreground before an early return`() {
        val service = source("app/src/main/java/io/legado/app/service/VideoPlayService.kt")
        val onStart = service.substringAfter("override fun onStartCommand")
            .substringBefore("@SuppressLint(\"UnspecifiedImmutableFlag\")")
        val foreground = onStart.indexOf("super.onStartCommand(intent, flags, startId)")

        assertTrue(foreground >= 0)
        assertTrue(foreground < onStart.indexOf("Settings.canDrawOverlays(this)"))
        assertTrue(foreground < onStart.indexOf("if (intent == null)"))
        assertTrue(foreground < onStart.indexOf("VideoPlay.initSource("))
        assertTrue(onStart.contains("if (startResult == START_NOT_STICKY) return startResult"))
        val nullIntent = onStart.substringAfter("if (intent == null) {").substringBefore("}")
        assertTrue(nullIntent.contains("stopSelfResult(startId)"))
        val missingSource = onStart.substringAfter("if (!VideoPlay.initSource(")
            .substringBefore("}")
        assertTrue(missingSource.contains("stopSelfResult(startId)"))
        val newPlayer = onStart.substringAfter("if (isNew) {").substringBefore("} else {")
        val stopLoading = newPlayer.indexOf("VideoPlay.stopLoading()")
        val releasePlayer = newPlayer.indexOf("VideoPlay.releaseAllVideos()")
        val initSource = newPlayer.indexOf("VideoPlay.initSource(")
        val notificationGate = newPlayer.indexOf("mediaNotificationReady = false")
        assertTrue(notificationGate in 0 until stopLoading)
        assertTrue(stopLoading in 0 until releasePlayer)
        assertTrue(releasePlayer in 0 until initSource)
        assertTrue(newPlayer.contains("VideoPlay.videoUrl = videoUrl"))
        assertTrue(newPlayer.contains("VideoPlay.singleUrl = videoUrl != null"))
        assertTrue(newPlayer.indexOf("mediaNotificationReady = false") in
                0 until newPlayer.indexOf("startForegroundNotification()"))
        val clonedPlayer = onStart.substringAfter("if (!isNew) {").substringBefore("}")
        assertTrue(clonedPlayer.contains("mediaNotificationReady = true"))
        assertTrue(clonedPlayer.contains("upVideoPlayNotification()"))

        val notificationUpdate = service.substringAfter("private fun upVideoPlayNotification()")
            .substringBefore("override fun startForegroundNotification()")
        assertTrue(notificationUpdate.contains("if (!mediaNotificationReady) return"))
        assertTrue(notificationUpdate.contains("upNotificationJob?.cancel()"))
        assertTrue(notificationUpdate.contains("withContext(Main)"))
        val onPrepared = service.substringAfter("override fun onPrepared")
            .substringBefore("override fun onClickStartIcon")
        assertTrue(onPrepared.indexOf("mediaNotificationReady = true") in
                0 until onPrepared.indexOf("upVideoPlayNotification()"))

        val baseService = source("app/src/main/java/io/legado/app/base/BaseService.kt")
        val baseOnStart = baseService.substringAfter("override fun onStartCommand")
            .substringBefore("override fun onTaskRemoved")
        assertTrue(baseOnStart.contains("tryStartForegroundNotification()"))
        val foregroundStart = baseService.substringAfter("private fun tryStartForegroundNotification()")
            .substringBefore("fun checkFloatPermission()")
        assertTrue(foregroundStart.contains("startForegroundNotification()"))

        val foregroundNotification = service.substringAfter("override fun startForegroundNotification()")
            .substringBefore("private fun initBroadcastReceiver()")
        assertTrue(foregroundNotification.contains("setContentTitle(getString(R.string.video))"))
        assertTrue(foregroundNotification.contains("startForeground(NotificationId.VideoPlayService"))
        assertFalse(foregroundNotification.contains("VideoPlay.videoTitle"))
        assertFalse(foregroundNotification.contains("catch"))

        val videoPlay = source("app/src/main/java/io/legado/app/model/VideoPlay.kt")
        val missingVideoSource = videoPlay.substringAfter("if (source == null) {")
            .substringBefore("return false")
        assertTrue(missingVideoSource.contains("isLoading = false"))
        val stopVideoLoading = videoPlay.substringAfter("fun stopLoading()")
            .substringBefore("fun initSource(")
        assertTrue(stopVideoLoading.indexOf("cancelChildren()") in
                0 until stopVideoLoading.indexOf("isLoading = false"))
        assertTrue(videoPlay.contains("videoTitle = rssArticle.title"))
        assertTrue(videoPlay.contains("videoTitle = chapter.title"))
        val saveRead = videoPlay.substringAfter("fun saveRead(durPos: Int? = null)")
            .substringBefore("fun getDisplayCover()")
        assertFalse(saveRead.contains("videoTitle ="))
    }

    @Test
    fun `default floating window covers normal entries without overriding explicit choice`() {
        val activity = source("app/src/main/java/io/legado/app/ui/video/VideoPlayerActivity.kt")
        val routing = activity.substringAfter("override fun onActivityCreated")
            .substringBefore("playerView.enlargeImageRes")
        assertTrue(routing.contains("isNew &&"))
        assertTrue(routing.contains("intent.action == null"))
        assertTrue(routing.contains("VideoPlay.defaultFloatWindow"))
        assertTrue(routing.contains("!intent.getBooleanExtra(\"forceNormalPlayer\", false)"))
        assertTrue(routing.contains("Intent(intent).setClass(this, VideoPlayService::class.java)"))
        assertTrue(routing.contains("forwardedToFloatingWindow = true"))
        assertTrue(routing.contains("intent.putExtra(\"forwardedToFloatingWindow\", true)"))
        assertTrue(routing.contains("playerView.needDestroy = false"))
        assertTrue(routing.contains("super.finish()"))

        val service = source("app/src/main/java/io/legado/app/service/VideoPlayService.kt")
        assertTrue(
            service.contains(
                "!activity.intent.getBooleanExtra(\"forwardedToFloatingWindow\", false)"
            )
        )

        val destroy = activity.substringAfter("override fun onDestroy()")
        val cleanup = destroy.substringAfter("if (!forwardedToFloatingWindow) {")
            .substringBefore("}")
        assertTrue(cleanup.contains("VideoPlay.saveRead()"))
        assertTrue(cleanup.contains("VideoPlay.stopLoading()"))
        assertTrue(cleanup.contains("playerView.getCurrentPlayer().release()"))

        val sourceHelp = source("app/src/main/java/io/legado/app/help/source/SourceHelp.kt")
        val explicitNormal = sourceHelp.substringAfter("fun openVideoPlayer(")
            .substringAfter("} else {")
        assertTrue(explicitNormal.contains("putExtra(\"forceNormalPlayer\", true)"))
    }

    private fun source(relativePath: String): String {
        return File(repositoryRoot(), relativePath).readText()
    }

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
