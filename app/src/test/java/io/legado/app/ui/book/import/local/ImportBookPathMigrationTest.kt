package io.legado.app.ui.book.import.local

import io.legado.app.model.localBook.findExactRemoteBook
import io.legado.app.model.localBook.isMissingLocalBookFile
import io.legado.app.model.remote.RemoteBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException

class ImportBookPathMigrationTest {

    @Test
    fun `automatic restore only handles confirmed missing files`() {
        assertTrue(isMissingLocalBookFile(true, false, FileNotFoundException()))
        assertFalse(isMissingLocalBookFile(true, false, IOException()))
        assertFalse(isMissingLocalBookFile(true, false, SecurityException()))
        assertFalse(isMissingLocalBookFile(false, false, CancellationException()))
        assertTrue(isMissingLocalBookFile(false, false, IOException()))
        assertFalse(isMissingLocalBookFile(false, true, FileNotFoundException()))
    }

    @Test
    fun `automatic restore requires an exact remote file name`() {
        val directory = RemoteBook("Book.txt", "dir", 0, 0)
        val otherCase = RemoteBook("book.txt", "other", 1, 0, "txt")
        val match = RemoteBook("Book.txt", "match", 1, 0, "txt")

        assertSame(match, findExactRemoteBook(listOf(directory, otherCase, match), "Book.txt"))
        assertNull(findExactRemoteBook(listOf(directory, otherCase, match), "BOOK.txt"))
    }

    @Test
    fun `automatic restore is opt in and device local`() {
        val preferKey = readProjectFile(
            "src/main/java/io/legado/app/constant/PreferKey.kt"
        )
        val appConfig = readProjectFile(
            "src/main/java/io/legado/app/help/config/AppConfig.kt"
        )
        val backupConfig = readProjectFile(
            "src/main/java/io/legado/app/help/storage/BackupConfig.kt"
        )
        val preferences = readProjectFile("src/main/res/xml/pref_config_backup.xml")

        assertTrue(preferKey.contains("const val webDavBookAutoRestore"))
        assertTrue(
            appConfig.contains(
                "getPrefBoolean(PreferKey.webDavBookAutoRestore, false)"
            )
        )
        assertTrue(backupConfig.contains("PreferKey.webDavBookAutoRestore"))
        assertTrue(preferences.contains("android:key=\"webDavBookAutoRestore\""))
        assertTrue(preferences.contains("android:defaultValue=\"false\""))
    }

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
        assertTrue(inputStream.contains("isMissingLocalBookFile("))
        assertTrue(inputStream.contains("throw readError"))
        assertTrue(inputStream.contains("if (downloadRemoteBook(book))"))
        assertTrue(
            inputStream.indexOf("inputStreamResult.getOrNull()?.let { return it }") <
                    inputStream.indexOf("if (downloadRemoteBook(book))")
        )
        assertTrue(
            inputStream.indexOf("throw readError") <
                    inputStream.indexOf("if (downloadRemoteBook(book))")
        )
        assertFalse(inputStream.contains("importArchiveFile("))
        assertTrue(lastModified.contains("book.getLocalUri()"))
        assertFalse(lastModified.contains("book.bookUrl"))
        assertTrue(deleteBook.contains("FileDoc.fromUri(book.getLocalUri(), false).delete()"))
        assertFalse(deleteBook.contains("book.bookUrl"))
        assertTrue(archiveRestore.contains("book.cacheLocalUri(fileUri)"))
        assertFalse(archiveRestore.contains("book.bookUrl"))
        assertFalse(archiveRestore.contains("book.origin ="))
        assertFalse(archiveRestore.contains("importArchiveFile("))

        val remoteRestore = localBook.substringAfter("fun downloadRemoteBook")
        assertTrue(remoteRestore.contains("restoreArchiveBookFile(localBook, archiveUri)"))
        assertTrue(remoteRestore.contains("localBook.cacheLocalUri(fileUri)"))
        assertTrue(remoteRestore.contains("!AppConfig.webDavBookAutoRestore"))
        assertTrue(remoteRestore.contains("AppWebDav.defaultBookWebDav ?: return false"))
        assertTrue(remoteRestore.contains("bookWebDav.getRemoteBookList(bookWebDav.rootBookUrl)"))
        assertTrue(remoteRestore.contains("findExactRemoteBook(it, fileName)"))
        assertTrue(remoteRestore.contains("localBook.archiveName"))
        assertTrue(remoteRestore.contains("localBook.originName"))
        assertTrue(remoteRestore.contains("WebDav(remoteBook.path, bookWebDav.authorization)"))
        assertFalse(remoteRestore.contains("rootBookUrl}\${localBook"))
        assertFalse(remoteRestore.contains("localBook.bookUrl ="))
        assertFalse(remoteRestore.contains("localBook.save()"))
        assertFalse(remoteRestore.contains("importArchiveFile("))

        val remoteBook = readProjectFile(
            "src/main/java/io/legado/app/model/remote/RemoteBookWebDav.kt"
        )
        assertTrue(remoteBook.contains("val localBookUri = if (book.isArchive)"))
        assertTrue(remoteBook.contains("book.getArchiveUri()"))
        assertTrue(remoteBook.contains("remoteBookUploadFileName(book)"))
        assertFalse(remoteBook.contains("Uri.parse(book.bookUrl)"))

        val bookInfoActivity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt"
        )
        assertTrue(bookInfoActivity.contains("FileDoc.fromUri(book.getLocalUri(), false).size"))
        assertFalse(bookInfoActivity.contains("FileDoc.fromFile(book.bookUrl).size"))

        val bookInfoViewModel = readProjectFile(
            "src/main/java/io/legado/app/ui/book/info/BookInfoViewModel.kt"
        ).substringAfter("fun refreshBook(book: Book)")
            .substringBefore("fun loadBookInfo(")
        assertTrue(bookInfoViewModel.contains("LocalBook.downloadRemoteBook(book)"))
        assertFalse(bookInfoViewModel.contains("downloadRemoteBook(remoteBook)"))
        assertFalse(bookInfoViewModel.contains("book.bookUrl ="))

        val readActivity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/BaseReadBookActivity.kt"
        ).substringAfter("private val selectBookFolderResult")
            .substringBefore("override fun onCreate")
        assertTrue(readActivity.contains("AppConfig.importBookPath = uri.toString()"))
        assertTrue(readActivity.contains("LocalBook.withParserCacheInvalidated(book)"))
        assertTrue(readActivity.contains("book.cacheLocalUri(doc.uri)"))
        assertFalse(readActivity.contains("book.bookUrl ="))
        assertFalse(readActivity.contains("book.save()"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
