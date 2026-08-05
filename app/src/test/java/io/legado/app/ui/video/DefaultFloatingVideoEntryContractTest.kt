package io.legado.app.ui.video

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DefaultFloatingVideoEntryContractTest {

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
