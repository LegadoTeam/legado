package io.legado.app.ui.rss.article

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RssArticleAdapterReuseTest {

    @Test
    fun `empty image binding clears recycled image state`() {
        val source = readProjectFile("src/main/java/io/legado/app/ui/rss/article/RssArticlesAdapter3.kt")
        val branchStart = source.indexOf("if (imageUrl.isNullOrEmpty())")
        val branchEnd = source.indexOf("return", branchStart)
        require(branchStart >= 0 && branchEnd > branchStart)
        val branch = source.substring(branchStart, branchEnd)

        assertTrue(branch.contains("clearImage(imageView)"))
        assertTrue(branch.contains("layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT"))

        val base = readProjectFile("src/main/java/io/legado/app/ui/rss/article/BaseRssArticlesAdapter.kt")
        assertTrue(base.contains("Glide.with(context).clear(imageView)"))
        assertTrue(base.contains("imageView.setImageDrawable(null)"))

        listOf("", "1", "2", "4").forEach { suffix ->
            val adapter = readProjectFile(
                "src/main/java/io/legado/app/ui/rss/article/RssArticlesAdapter$suffix.kt"
            )
            val imageBranch = adapter.substringAfter("item.image.isNullOrBlank() && !callBack.isGridLayout")
                .substringBefore("} else {")
            assertTrue(imageBranch.contains("clearImage(imageView)"))
        }

        val favorites = readProjectFile(
            "src/main/java/io/legado/app/ui/rss/favorites/RssFavoritesAdapter.kt"
        ).substringAfter("if (item.image.isNullOrBlank())").substringBefore("} else {")
        assertTrue(favorites.contains("Glide.with(context).clear(imageView)"))
        assertTrue(favorites.contains("imageView.setImageDrawable(null)"))
    }

    @Test
    fun `image changes use a full binding`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/rss/article/RssArticlesFragment.kt"
        )
        val payloadStart = source.indexOf("override fun getChangePayload")
        val payloadEnd = source.indexOf("}, true)", payloadStart)
        require(payloadStart >= 0 && payloadEnd > payloadStart)
        val payload = source.substring(payloadStart, payloadEnd)

        assertTrue(payload.contains("return if (oldItem.image != newItem.image) { null }"))
        assertTrue(
            payload.indexOf("oldItem.image != newItem.image") <
                payload.indexOf("oldItem.read != newItem.read")
        )
    }

    private fun readProjectFile(path: String): String =
        sequenceOf(File(path), File("app/$path"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
}
