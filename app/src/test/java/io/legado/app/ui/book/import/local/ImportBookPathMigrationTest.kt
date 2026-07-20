package io.legado.app.ui.book.import.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImportBookPathMigrationTest {

    @Test
    fun `existing local book path is migrated explicitly`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/book/import/local/ImportBookActivity.kt"
        )
        val startRead = source.substringAfter("override fun startRead(fileDoc: FileDoc)")

        assertTrue(startRead.contains("startReadJob?.isActive == true"))
        assertTrue(startRead.contains("startReadJob = lifecycleScope.launch(IO)"))
        assertTrue(startRead.contains("appDb.bookDao.getBook(filePath)"))
        assertTrue(startRead.contains("appDb.bookDao.getBookByFileName(fileDoc.name)"))
        assertTrue(startRead.contains("val oldBook = book.copy()"))
        assertTrue(startRead.contains("val pathChanged = oldBook.bookUrl != filePath"))
        assertTrue(startRead.contains("oldBook.removeLocalUriCache()"))
        assertTrue(startRead.contains("book.bookUrl = filePath"))
        assertTrue(startRead.contains("appDb.bookDao.replace(oldBook, book)"))
        assertTrue(startRead.contains("BookHelp.updateCacheFolder(oldBook, book)"))
        assertTrue(startRead.contains("LocalBook.withParserCacheInvalidated("))
        assertTrue(startRead.contains("withContext(Main)"))
        assertTrue(startRead.contains("if (!isFinishing && !isDestroyed)"))
        assertTrue(
            startRead.indexOf("appDb.bookDao.getBook(filePath)") <
                    startRead.indexOf("appDb.bookDao.getBookByFileName(fileDoc.name)")
        )
        assertTrue(
            startRead.indexOf("oldBook.removeLocalUriCache()") <
                    startRead.indexOf("book.bookUrl = filePath")
        )
        assertTrue(
            startRead.indexOf("appDb.bookDao.replace(oldBook, book)") <
                    startRead.indexOf("BookHelp.updateCacheFolder(oldBook, book)")
        )
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
