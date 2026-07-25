package io.legado.app.ui.rss.article

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RssArticleAdapterReuseTest {

    @Test
    fun `empty image binding clears recycled image state`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/rss/article/RssArticlesAdapter3.kt"
        )
        val branchStart = source.indexOf("if (imageUrl.isNullOrEmpty())")
        val branchEnd = source.indexOf("return", branchStart)
        require(branchStart >= 0 && branchEnd > branchStart)
        val branch = source.substring(branchStart, branchEnd)

        assertTrue(branch.contains("Glide.with(context).clear(imageView)"))
        assertTrue(branch.contains("imageView.setImageDrawable(null)"))
        assertTrue(branch.contains("layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT"))
    }

    private fun readProjectFile(path: String): String =
        sequenceOf(File(path), File("app/$path"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
}
