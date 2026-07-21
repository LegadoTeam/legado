package io.legado.app.ui.book.read

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReviewDetailMediaSourceTest {

    @Test
    fun `review images and preview keep the captured source`() {
        val source = dialogSource()

        assertTrue(
            source.contains(
                "RequestOptions().set(OkHttpModelLoader.sourceOriginOption, sourceKey)"
            )
        )
        listOf("item.avatar", "item.imageUrl", "badge").forEach { image ->
            val model = Regex.escape(image)
            assertTrue(
                Regex(
                    """ImageLoader\.load\(context, $model\)\s*""" +
                        """\.apply\(sourceImageOptions\)"""
                ).containsMatchIn(source)
            )
        }
        assertTrue(source.contains("PhotoDialog(imageUrl, sourceKey)"))
    }

    @Test
    fun `review audio reuses source aware media item`() {
        val source = dialogSource()
        val toggleBlock = source.substringAfter("private fun toggleAudioPlayback(")
            .substringBefore("private fun releaseAudioPlayer(")

        assertTrue(toggleBlock.contains("val source = reviewSource ?: return"))
        assertTrue(toggleBlock.contains("source = source"))
        assertTrue(toggleBlock.contains(").getMediaItem()"))
        assertTrue(source.contains("val source: BaseSource"))
        assertTrue(source.contains("result?.source?.let { reviewSource = it }"))
    }

    private fun dialogSource(): String = projectFile(
        "src/main/java/io/legado/app/ui/book/read/ReviewDetailDialog.kt"
    ).readText().replace("\r\n", "\n")

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
