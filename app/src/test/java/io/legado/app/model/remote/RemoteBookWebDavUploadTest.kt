package io.legado.app.model.remote

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RemoteBookWebDavUploadTest {

    @Test
    fun `archive upload keeps the archive file name`() {
        val archiveBook = Book(
            origin = "${BookType.localTag}::source.zip",
            originName = "chapter.txt",
            type = BookType.text or BookType.local or BookType.archive,
        )

        assertEquals("source.zip", remoteBookUploadFileName(archiveBook))
        assertEquals("chapter.txt", remoteBookUploadFileName(Book(originName = "chapter.txt")))
    }

    @Test
    fun `download upload choice is remembered and checks before overwrite`() {
        val localConfig = readProjectFile(
            "src/main/java/io/legado/app/help/config/LocalConfig.kt"
        )
        val activity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt"
        )
        val remoteBook = readProjectFile(
            "src/main/java/io/legado/app/model/remote/RemoteBookWebDav.kt"
        )

        assertTrue(localConfig.contains("var uploadImportedBookToWebDav: Boolean"))
        assertTrue(activity.contains("isChecked = LocalConfig.uploadImportedBookToWebDav"))
        assertTrue(activity.contains("LocalConfig.uploadImportedBookToWebDav = isChecked"))
        assertTrue(activity.contains("viewModel.getBook()?.let { confirmAndUploadBook(it) }"))
        assertTrue(activity.contains("confirmAndUploadBook(book, onFinished)"))
        assertTrue(activity.contains("bookWebDav.hasRemoteBook(book)"))
        assertTrue(activity.contains("R.string.webdav_book_exists_confirm"))
        assertTrue(remoteBook.contains("findExactRemoteBook(getRemoteBookList(rootBookUrl), fileName)"))
        val importedBook = activity.substringAfter("private fun onWebBookImported")
            .substringBefore("private fun showDecompressFileImportAlert")
        assertFalse(importedBook.contains("book.bookUrl ="))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
