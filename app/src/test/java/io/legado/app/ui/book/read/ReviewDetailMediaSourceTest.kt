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
        listOf("item.avatar", "badge").forEach { image ->
            val model = Regex.escape(image)
            assertTrue(
                Regex(
                    """ImageLoader\.load\(context, $model\)\s*""" +
                        """\.apply\(sourceImageOptions\)"""
                ).containsMatchIn(source)
            )
        }
        assertTrue(source.contains("bindReviewImage(binding, item.imageUrl)"))
        assertTrue(
            Regex(
                """ImageLoader\.load\(context, imageUrl\)\s*""" +
                    """\.apply\(sourceImageOptions\)"""
            ).containsMatchIn(source)
        )
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

    @Test
    fun `reply target keeps the commenter header and prefixes the body`() {
        val binding = dialogSource()
            .substringAfter("binding.llBadges.visibility")
            .substringBefore("val hasText")

        assertTrue(binding.contains("binding.tvName.text = item.name.orEmpty()"))
        assertTrue(binding.contains("val replyToName = item.replyToName.orEmpty().trim()"))
        assertTrue(binding.contains("val prefix = \"\u56de\u590d \$replyToName\uff1a\""))
        assertFalse(binding.contains("binding.tvName.gone()"))
    }

    @Test
    fun `reply likes are shown only for positive counts`() {
        val binding = dialogSource()
            .substringAfter("binding.tvTime.text = item.time.orEmpty()")
            .substringBefore("private fun bindReviewImage")

        assertTrue(
            binding.contains(
                "val showLikeArea = !item.isReply || (likeCount != null && likeCount > 0)"
            )
        )
        assertTrue(binding.contains("if (showLikeArea)"))
        assertTrue(binding.contains("binding.llLikeArea.visible()"))
        assertTrue(binding.contains("binding.llLikeArea.gone()"))
    }

    @Test
    fun `embedded replies are expanded without loading paged replies`() {
        val source = dialogSource()
        val flatten = source.substringAfter("private fun flattenItems(")
            .substringBefore("private fun renderUiItems()")

        assertTrue(flatten.contains("val isExpanded = loadedReplyCount > 0 ||"))
        assertTrue(flatten.contains("expandedReplyParentKeys.contains(parentKey)"))
        assertTrue(flatten.contains("val canLoadMore = hasReplyUrl"))

        val listener = source.substringAfter("override fun registerListener(")
            .substringBefore("private fun bindAudioState")
        assertTrue(listener.contains("loadReplies(parentKey)"))
        assertFalse(listener.contains("detail.replies.isNotEmpty()"))
    }

    @Test
    fun `review image restores its cached aspect ratio before reloading`() {
        val binding = dialogSource()
            .substringAfter("private fun bindReviewImage(")
            .substringBefore("override fun registerListener")
        val clearRequest = binding.indexOf("Glide.with(context).clear(imageView)")
        val resetHeight = binding.indexOf("height = ViewGroup.LayoutParams.WRAP_CONTENT")
        val emptyImage = binding.indexOf("if (imageUrl.isNullOrBlank())")
        val cachedSize = binding.indexOf("imageSizes[imageUrl]?.let")
        val intoImage = binding.indexOf("imageRequest.into(imageView)")

        assertTrue(clearRequest in 0 until emptyImage)
        assertTrue(resetHeight in 0 until emptyImage)
        assertTrue(cachedSize in emptyImage until intoImage)
        assertFalse(binding.contains("imageView.width"))
        assertTrue(binding.contains("val width = resource.intrinsicWidth"))
        assertTrue(binding.contains("val height = resource.intrinsicHeight"))
        assertTrue(binding.contains("if (width > 0 && height > 0)"))
        assertTrue(binding.contains("imageSizes[imageUrl] = Size(width, height)"))
        assertTrue(binding.contains("intrinsicWidth = size.width"))
        assertTrue(binding.contains("intrinsicHeight = size.height"))
        assertTrue(binding.contains("imageRequest.placeholder(placeholder).error(placeholder)"))
    }

    @Test
    fun `review list only applies diff updates while resumed`() {
        val source = dialogSource()
        val resumeBlock = source.substringAfter("override fun onResume()")
            .substringBefore("override fun onPause()")
        val pauseBlock = source.substringAfter("override fun onPause()")
            .substringBefore("override fun onFragmentCreated")

        assertTrue(resumeBlock.contains("adapter.upResumed(true)"))
        assertTrue(pauseBlock.contains("adapter.upResumed(false)"))
        assertTrue(pauseBlock.indexOf("adapter.upResumed(false)") < pauseBlock.indexOf("super.onPause()"))
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
