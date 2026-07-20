package io.legado.app.model.localBook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalBookReaderLifecycleTest {

    @Test
    fun `mobi close does not invoke lazy reader getter`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/model/localBook/MobiFile.kt"
        )
        val closeBlock = source.substringAfter("override fun close()")

        assertTrue(source.contains("private var openedMobiBook: MobiBook? = null"))
        assertTrue(closeBlock.contains("val openedBook = openedMobiBook"))
        assertTrue(closeBlock.contains("openedMobiBook = null"))
        assertFalse(closeBlock.contains("val openedBook = mobiBook"))
    }

    @Test
    fun `pdf close does not invoke lazy renderer getter`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/model/localBook/PdfFile.kt"
        )
        val closeBlock = source.substringAfter("private fun closePdf()")
            .substringBefore("private fun openPdfPage")

        assertTrue(source.contains("private var openedPdfRenderer: PdfRenderer? = null"))
        assertTrue(closeBlock.contains("val renderer = openedPdfRenderer"))
        assertTrue(closeBlock.contains("openedPdfRenderer = null"))
        assertFalse(closeBlock.contains("val renderer = pdfRenderer"))
    }

    @Test
    fun `local book existence check closes probe stream`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt"
        )
        val checkBlock = source.substringAfter("private fun checkLocalBookFileExist")
            .substringBefore("private suspend fun loadBookInfo")

        assertTrue(checkBlock.contains("LocalBook.getBookInputStream(book).use {}"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
