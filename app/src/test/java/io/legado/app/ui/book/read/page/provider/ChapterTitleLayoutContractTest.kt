package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChapterTitleLayoutContractTest {

    @Test
    fun `split titles preserve text positions and review ownership`() {
        val layout = projectFile("TextChapterLayout.kt")
        val provider = projectFile("ChapterProvider.kt")

        assertTrue(layout.contains("if (splitTitle == null) 1 else 2"))
        assertTrue(layout.contains("titleLines.indexOfFirst { !it.second }"))
        assertTrue(layout.contains("isParagraphEnd = true"))
        assertFalse(layout.contains("splitTitle == null ||"))
        assertTrue(provider.contains("isTitle = line.isReviewTitle"))
        assertTrue(provider.contains("isTitle = textLine.isReviewTitle"))
    }

    private fun projectFile(name: String): String {
        val relative = "src/main/java/io/legado/app/ui/book/read/page/provider/$name"
        return sequenceOf(File(relative), File("app/$relative"))
            .first(File::isFile)
            .readText()
    }
}
