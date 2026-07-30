package io.legado.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CacheBookReadRetryContractTest {

    private val source by lazy {
        listOf(
            File("src/main/java/io/legado/app/model/CacheBook.kt"),
            File("app/src/main/java/io/legado/app/model/CacheBook.kt")
        ).first { it.isFile }.readText().replace("\r\n", "\n")
    }

    @Test
    fun `reading downloads never enter the explicit cache retry queue`() {
        val readError = section("private fun onReadError", "private fun onCancel")
        val readCancel = section("private fun onReadCancel", "private fun onFinally")
        val explicitDownload = section(
            "fun download(scope: CoroutineScope, context: CoroutineContext)",
            "suspend fun downloadAwait"
        )
        val awaitDownload = section("suspend fun downloadAwait", "@Synchronized\n        fun download(")
        val readDownload = section(
            "@Synchronized\n        fun download(\n            scope: CoroutineScope,\n            chapter",
            "private fun downloadFinish"
        )

        assertTrue(readError.contains("onDownloadSet.remove(chapter.index)"))
        assertFalse(readError.contains("waitDownloadSet"))
        assertTrue(readCancel.contains("onDownloadSet.remove(index)"))
        assertFalse(readCancel.contains("waitDownloadSet"))

        assertTrue(explicitDownload.contains("onPreError(chapter, it)"))
        assertTrue(explicitDownload.contains("onPostError(chapter, it)"))
        assertTrue(explicitDownload.contains("onCancel(chapterIndex)"))

        assertTrue(awaitDownload.contains("catch (e: CancellationException)"))
        assertTrue(awaitDownload.contains("onReadCancel(chapter.index)"))
        assertTrue(awaitDownload.contains("throw e"))
        assertTrue(awaitDownload.contains("onReadError(chapter, e)"))

        assertTrue(readDownload.contains("onReadError(chapter, it)"))
        assertTrue(readDownload.contains("onReadCancel(chapter.index)"))
        assertFalse(readDownload.contains("onError(chapter, it)"))
        assertFalse(readDownload.contains("onCancel(chapter.index)"))
    }

    private fun section(startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start)
        require(start >= 0 && end > start)
        return source.substring(start, end)
    }
}
