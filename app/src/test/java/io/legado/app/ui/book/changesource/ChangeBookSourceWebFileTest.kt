package io.legado.app.ui.book.changesource

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangeBookSourceWebFileTest {

    @Test
    fun `web file source refreshes missing download urls before change`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt",
        ).readText()
        val webFileBranch = source
            .substringAfter("if (book.isWebFile) {")
            .substringBefore("} else {")

        assertTrue(webFileBranch.contains("if (book.downloadUrls.isNullOrEmpty())"))
        assertTrue(webFileBranch.contains("WebBook.getBookInfoAwait(source, book)"))
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
    }
}
