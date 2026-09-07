package io.legado.app.ui.book.toc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TocExpansionPersistenceLifecycleTest {

    @Test
    fun `toc preference outlives the directory view model and updates active readers`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/book/toc/TocViewModel.kt"
        ).readText()
        val setBlock = source.substringAfter("fun setTocExpanded")
            .substringBefore("fun startChapterListSearch")

        assertTrue(setBlock.contains("globalExecutor.execute"))
        assertTrue(setBlock.contains("bookDao.updateTocExpanded(book.bookUrl, expanded)"))
        assertTrue(setBlock.contains("updateActiveReaderBooks(book.bookUrl, expanded)"))
        assertTrue(source.contains("ReadBook.book"))
        assertTrue(source.contains("ReadManga.book"))
        assertTrue(source.contains("AudioPlay.book"))
        assertTrue(source.contains("VideoPlay.book"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
