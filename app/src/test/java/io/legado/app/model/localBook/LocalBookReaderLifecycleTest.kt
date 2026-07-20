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

    @Test
    fun `parser caches support targeted invalidation`() {
        listOf("EpubFile", "MobiFile", "PdfFile").forEach { parser ->
            val source = readProjectFile(
                "src/main/java/io/legado/app/model/localBook/$parser.kt"
            )
            val clearBlock = source.substringAfter("fun clear(bookUrl: String)")
                .substringBefore("\n        }")

            assertTrue(source.contains("private val openedBookUrl = book.bookUrl"))
            assertTrue(source.contains("matches = { it.openedBookUrl == book.bookUrl }"))
            assertTrue(clearBlock.contains("cache.clearIf"))
            assertTrue(clearBlock.contains("it.openedBookUrl == bookUrl"))
        }

        val umdSource = readProjectFile(
            "src/main/java/io/legado/app/model/localBook/UmdFile.kt"
        )
        val umdClearBlock = umdSource.substringAfter("fun clear(bookUrl: String)")
            .substringBefore("\n        }")
        assertTrue(umdSource.contains("private val openedBookUrl = book.bookUrl"))
        assertTrue(umdSource.contains("uFile?.openedBookUrl != book.bookUrl"))
        assertTrue(umdClearBlock.contains("uFile?.openedBookUrl == bookUrl"))
        assertTrue(umdClearBlock.contains("uFile = null"))
    }

    @Test
    fun `local book refresh invalidates parser before parsing again`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/model/localBook/LocalBook.kt"
        )
        val importFileBlock = source.substringAfter("fun importFile(uri: Uri)")
            .substringBefore("fun upBookInfo(book: Book)")
        val existingBookBlock = importFileBlock.substringAfter("} else {")

        assertTrue(existingBookBlock.contains("withParserCacheInvalidated(book)"))
        assertTrue(existingBookBlock.contains("deleteBook(book, false)"))
        assertTrue(existingBookBlock.contains("upBookInfo(book)"))
        assertTrue(
            existingBookBlock.indexOf("deleteBook(book, false)") <
                    existingBookBlock.indexOf("upBookInfo(book)")
        )
    }

    @Test
    fun `local book files invalidate parser before overwrite`() {
        val localBookSource = readProjectFile(
            "src/main/java/io/legado/app/model/localBook/LocalBook.kt"
        )
        assertInvalidationBeforeOutput(
            localBookSource,
            "withParserCacheInvalidated(doc.uri, fileName)",
            "appCtx.contentResolver.openOutputStream(doc.uri)"
        )
        assertInvalidationBeforeOutput(
            localBookSource,
            "withParserCacheInvalidated(Uri.fromFile(file), fileName)",
            "FileOutputStream(file)"
        )

        val associationSource = readProjectFile(
            "src/main/java/io/legado/app/ui/association/FileAssociationActivity.kt"
        )
        assertInvalidationBeforeOutput(
            associationSource,
            "LocalBook.withParserCacheInvalidated(doc.uri, name)",
            "contentResolver.openOutputStream(doc.uri)"
        )
        assertInvalidationBeforeOutput(
            associationSource,
            "LocalBook.withParserCacheInvalidated(Uri.fromFile(file), name)",
            "FileOutputStream(file)"
        )
    }

    @Test
    fun `umd parsing closes source stream after eager read`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/model/localBook/UmdFile.kt"
        )
        val readBlock = source.substringAfter("private fun readUmd()")
            .substringBefore("private fun upBookCover")

        assertTrue(readBlock.contains("LocalBook.getBookInputStream(book).use"))
        assertTrue(readBlock.contains("UmdReader().read(it)"))
    }

    private fun assertInvalidationBeforeOutput(
        source: String,
        invalidation: String,
        output: String,
    ) {
        val invalidationIndex = source.indexOf(invalidation)
        val outputIndex = source.indexOf(output, invalidationIndex)

        assertTrue("Missing parser invalidation: $invalidation", invalidationIndex >= 0)
        assertTrue("Missing output after parser invalidation: $output", outputIndex > invalidationIndex)
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
