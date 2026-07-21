package io.legado.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioOfflineCachePlaybackTest {

    @Test
    fun `playback checks persistent cache before consuming preloaded url`() {
        val source = projectFile(
            "src/main/java/io/legado/app/model/AudioPlay.kt"
        ).readText()
        val loadBody = source.substringAfter("private fun loadPlayUrl()")
            .substringBefore("private fun loadRemotePlayUrl(")

        assertTrue(
            loadBody.indexOf("AudioCacheManager.getCachedAudio") <
                    loadBody.indexOf("loadRemotePlayUrl")
        )
        assertTrue(loadBody.contains("durPlayUrl = cachedAudio.playUrl"))
        assertTrue(loadBody.contains("durMediaUrl = cachedAudio.mediaUri"))
    }

    @Test
    fun `media cache supports content uri upstream data sources`() {
        val source = projectFile(
            "src/main/java/io/legado/app/help/exoplayer/ExoPlayerHelper.kt"
        ).readText()

        assertTrue(source.contains("DefaultDataSource.Factory(appCtx, okhttpDataFactory)"))
        assertTrue(source.contains("setUpstreamDataSourceFactory(defaultDataSourceFactory)"))

        val serviceSource = projectFile(
            "src/main/java/io/legado/app/service/AudioPlayService.kt"
        ).readText()
        assertTrue(serviceSource.contains("url = AudioPlay.durMediaUrl"))
        assertTrue(serviceSource.contains("localMediaItem(url) ?: analyzeUrl.getMediaItem()"))
        assertTrue(serviceSource.contains("url.startsWith(\"content://\", true)"))
    }

    @Test
    fun `persistent cache requires a completed sidecar marker`() {
        val source = projectFile(
            "src/main/java/io/legado/app/help/audio/AudioCacheManager.kt"
        ).readText()

        assertTrue(source.contains("private const val COMPLETE_SUFFIX = \".complete\""))
        assertTrue(source.contains("createCompleteMarker(folder, installedFile, playUrl)"))
        assertTrue(source.contains("committedCacheFiles(folder"))
        assertTrue(source.contains("cleanupUncommittedFiles(folder, key)"))
        assertTrue(source.contains("tmp_${'$'}{key.value}_"))
        assertTrue(source.contains("AudioCacheMetadata.encode(playUrl)"))
    }

    @Test
    fun `cache events from an old folder do not update the current toc`() {
        val activitySource = projectFile(
            "src/main/java/io/legado/app/ui/book/audio/AudioPlayActivity.kt"
        ).readText()
        val selectionBody = activitySource.substringAfter("if (available) {")
            .substringBefore("pendingAudioCacheAction = null")
        assertTrue(
            selectionBody.indexOf("AudioCacheService.stop") <
                    selectionBody.indexOf("AppConfig.audioCacheTreeUri = treeUri")
        )

        val tocSource = projectFile(
            "src/main/java/io/legado/app/ui/book/toc/ChapterListFragment.kt"
        ).readText()
        assertTrue(
            tocSource.contains("if (event.treeUri != AppConfig.audioCacheTreeUri)")
        )
        assertTrue(tocSource.contains("if (treeUri == currentTreeUri) break"))
        assertTrue(tocSource.contains("pendingAudioCacheChanges.clear()"))
    }

    @Test
    fun `broken persistent cache retries before reporting a terminal error`() {
        val source = projectFile(
            "src/main/java/io/legado/app/service/AudioPlayService.kt"
        ).readText()
        val errorBody = source.substringAfter("override fun onPlayerError")
            .substringBefore("private fun setTimer")

        assertTrue(
            errorBody.indexOf("retryAfterCachedPlaybackError") <
                    errorBody.indexOf("AudioPlay.status = Status.STOP")
        )

        val playSource = projectFile(
            "src/main/java/io/legado/app/model/AudioPlay.kt"
        ).readText()
        val retryBody = playSource.substringAfter("fun retryAfterCachedPlaybackError")
            .substringBefore("private fun findNextPlayableChapter")
        assertTrue(retryBody.contains("val cachedKey = playingCacheKey ?: return false"))
        val removeCall = retryBody.substringAfter("AudioCacheManager.removeCachedChapter(")
            .substringBefore(")")
        assertTrue(removeCall.contains("cacheTreeUri"))
        assertTrue(removeCall.contains("cachedBookUrl"))
        assertTrue(removeCall.contains("cachedKey"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
