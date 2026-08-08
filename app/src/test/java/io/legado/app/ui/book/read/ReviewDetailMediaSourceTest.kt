package io.legado.app.ui.book.read

import org.junit.Assert.assertFalse
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

    @Test
    fun `reply badges use the shared badge binding path`() {
        val binding = dialogSource()
            .substringAfter("val replyIndent = mainAvatarSize")
            .substringBefore("override fun registerListener")
        val replyBranch = binding.indexOf("\n            if (item.isReply) {")
        val badgeVisibility = binding.indexOf("binding.llBadges.visibility")
        val badgeBinding = binding.indexOf("bindBadges(binding.llBadges, item.badges)")
        val contentVisibility = binding.indexOf("val hasText")

        assertTrue(badgeVisibility in 0 until replyBranch)
        assertTrue(badgeBinding in 0 until replyBranch)
        assertTrue(contentVisibility > replyBranch)
        assertFalse(binding.substring(replyBranch, contentVisibility).contains("llBadges.gone()"))
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
