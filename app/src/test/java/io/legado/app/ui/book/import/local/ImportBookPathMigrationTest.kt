package io.legado.app.ui.book.import.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImportBookPathMigrationTest {

    @Test
    fun `existing local book path is rebound without changing book identity`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/book/import/local/ImportBookActivity.kt"
        )
        val startRead = source.substringAfter("override fun startRead(fileDoc: FileDoc)")

        assertTrue(startRead.contains("startReadJob?.isActive == true"))
        assertTrue(startRead.contains("startReadJob = lifecycleScope.launch(IO)"))
        assertTrue(startRead.contains("appDb.bookDao.getBook(filePath)"))
        assertTrue(startRead.contains("appDb.bookDao.getBookByFileName(fileDoc.name)"))
        assertTrue(startRead.contains("book.removeLocalUriCache()"))
        assertTrue(startRead.contains("book.cacheLocalUri(fileDoc.uri)"))
        assertFalse(startRead.contains("book.bookUrl = filePath"))
        assertFalse(startRead.contains("appDb.bookDao.replace("))
        assertFalse(startRead.contains("BookHelp.updateCacheFolder("))
        assertTrue(startRead.contains("LocalBook.withParserCacheInvalidated("))
        assertTrue(startRead.contains("withContext(Main)"))
        assertTrue(startRead.contains("if (!isFinishing && !isDestroyed)"))
        assertTrue(
            startRead.indexOf("appDb.bookDao.getBook(filePath)") <
                    startRead.indexOf("appDb.bookDao.getBookByFileName(fileDoc.name)")
        )
        assertTrue(
            startRead.indexOf("book.removeLocalUriCache()") <
                    startRead.indexOf("book.cacheLocalUri(fileDoc.uri)")
        )
    }

    @Test
    fun `local file consumers use the rebound uri`() {
        val extensions = readProjectFile(
            "src/main/java/io/legado/app/help/book/BookExtensions.kt"
        ).substringAfter("fun Book.getLocalUri(): Uri")
            .substringBefore("fun Book.getArchiveUri(): Uri?")
        assertFalse(extensions.contains("bookUrl = fileDoc.toString()"))
        assertFalse(extensions.contains("save()"))
        assertEquals(
            2,
            Regex("cacheLocalUri\\(fileDoc\\.uri\\)").findAll(extensions).count()
        )

        val localBook = readProjectFile(
            "src/main/java/io/legado/app/model/localBook/LocalBook.kt"
        )
        val inputStream = localBook.substringAfter("fun getBookInputStream(book: Book)")
            .substringBefore("fun getLastModified(book: Book)")
        val lastModified = localBook.substringAfter("fun getLastModified(book: Book)")
            .substringBefore("@Throws(TocEmptyException::class)")
        val deleteBook = localBook.substringAfter("fun deleteBook(book: Book")
            .substringBefore("suspend fun saveBookFile(")
        val archiveRestore = localBook.substringAfter("private fun restoreArchiveBookFile")
            .substringBefore("//文件类书源")
        assertTrue(inputStream.contains("restoreArchiveBookFile(book, localArchiveUri)"))
        assertFalse(inputStream.contains("importArchiveFile("))
        assertTrue(lastModified.contains("book.getLocalUri()"))
        assertFalse(lastModified.contains("book.bookUrl"))
        assertTrue(deleteBook.contains("FileDoc.fromUri(book.getLocalUri(), false).delete()"))
        assertFalse(deleteBook.contains("book.bookUrl"))
        assertTrue(archiveRestore.contains("book.cacheLocalUri(fileUri)"))
        assertFalse(archiveRestore.contains("book.bookUrl"))
        assertFalse(archiveRestore.contains("book.origin ="))
        assertFalse(archiveRestore.contains("importArchiveFile("))

        val remoteRestore = localBook.substringAfter("private fun downloadRemoteBook")
        assertTrue(remoteRestore.contains("restoreArchiveBookFile(localBook, archiveUri)"))
        assertTrue(remoteRestore.contains("localBook.cacheLocalUri(fileUri)"))
        assertFalse(remoteRestore.contains("localBook.bookUrl ="))
        assertFalse(remoteRestore.contains("localBook.save()"))
        assertFalse(remoteRestore.contains("importArchiveFile("))

        val remoteBook = readProjectFile(
            "src/main/java/io/legado/app/model/remote/RemoteBookWebDav.kt"
        )
        assertTrue(remoteBook.contains("val localBookUri = book.getLocalUri()"))
        assertFalse(remoteBook.contains("Uri.parse(book.bookUrl)"))

        val bookInfo = readProjectFile(
            "src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt"
        )
        assertTrue(bookInfo.contains("FileDoc.fromUri(book.getLocalUri(), false).size"))
        assertFalse(bookInfo.contains("FileDoc.fromFile(book.bookUrl).size"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
