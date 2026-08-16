package io.legado.app.help.book

import io.legado.app.data.entities.Book
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class BookCoverPersistenceTest {

    @Test
    fun `only network cover layers are eligible`() {
        val book = Book(
            bookUrl = "https://books.example/book",
            origin = "https://images.example",
            coverUrl = "https://images.example/source.jpg",
            persistedCoverUrl = "/data/user/0/io.legado.app/files/covers/local.cover",
        )
        assertEquals("https://images.example/source.jpg", book.networkCoverForPersistence())
        assertEquals(book.origin, book.networkCoverSourceOrigin())

        book.customCoverUrl = "https://images.example/custom.jpg"
        assertEquals("https://images.example/custom.jpg", book.networkCoverForPersistence())

        book.customCoverUrl = "/data/user/0/com.legado/covers/local.cover"
        assertNull(book.networkCoverForPersistence())
    }

    @Test
    fun `book migration keeps both cover overrides`() {
        val oldBook = Book(
            bookUrl = "old",
            customCoverUrl = "https://images.example/custom.jpg",
            persistedCoverUrl = "/covers/local.cover",
        )

        val migrated = oldBook.migrateTo(Book(bookUrl = "migrated"), emptyList())
        val updated = oldBook.updateTo(Book(bookUrl = "updated"))

        listOf(migrated, updated).forEach { book ->
            assertEquals(oldBook.customCoverUrl, book.customCoverUrl)
            assertEquals(oldBook.persistedCoverUrl, book.persistedCoverUrl)
        }
    }

    @Test
    fun `empty edit does not clear a persisted-only cover`() {
        assertFalse(hasEditedNetworkCover("", null, null))
        assertFalse(hasEditedNetworkCover(null, null, ""))
        assertTrue(hasEditedNetworkCover("https://images.example/new.jpg", null, null))
    }

    @Test
    fun `legacy persisted cover paths are classified conservatively`() {
        val root = Files.createTempDirectory("legacy-persisted-cover").toFile()
        try {
            val legacy = root.resolve("covers/0123456789abcdef0123456789abcdef.cover")
            val book = Book(customCoverUrl = legacy.absolutePath)

            assertTrue(isLegacyPersistedCoverPath(legacy.absolutePath))
            assertFalse(isLegacyPersistedCoverPath(root.resolve("covers/manual.cover").absolutePath))
            assertFalse(isLegacyPersistedCoverPath(root.resolve("other/${legacy.name}").absolutePath))
            assertFalse(isLegacyPersistedCoverPath("https://images.example/covers/${legacy.name}"))

            book.normalizeLegacyPersistedCover()
            assertEquals(legacy.absolutePath, book.persistedCoverUrl)
            assertNull(book.customCoverUrl)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `persistent cover install is content addressed and leaves no part file`() {
        val root = Files.createTempDirectory("book-cover-persistence").toFile()
        try {
            val source = root.resolve("glide-cache-file").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val first = installPersistentCover(source, root.resolve("covers"))
            val second = installPersistentCover(source, root.resolve("covers"))

            assertEquals(first, second)
            assertArrayEquals(source.readBytes(), first.readBytes())
            assertTrue(root.resolve("covers").listFiles()?.none { it.name.endsWith(".part") } == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed install does not leave a partial cover`() {
        val root = Files.createTempDirectory("book-cover-persistence-failure").toFile()
        try {
            val source = root.resolve("glide-cache-file").apply { writeText("cover") }
            val covers = root.resolve("covers").apply { writeText("not a directory") }

            assertThrows(IOException::class.java) {
                installPersistentCover(source, covers)
            }
            assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `batch download validates before database update`() {
        val source = readAppSource(
            "io/legado/app/ui/book/manage/BookshelfManageViewModel.kt"
        )
        val cancellationIndex = source.indexOf("currentCoroutineContext().ensureActive()")
        val validationIndex = source.indexOf(".submit(1, 1)")
        val updateIndex = source.indexOf("updatePersistedCoverUrlIfUnchanged(")

        assertTrue(source.contains("runInterruptible { target.get() }"))
        assertTrue(cancellationIndex >= 0)
        assertTrue(validationIndex > cancellationIndex)
        assertTrue(updateIndex > validationIndex)
    }

    @Test
    fun `book updates preserve concurrent cover changes`() {
        val dao = readAppSource("io/legado/app/data/dao/BookDao.kt")
        val extensions = readAppSource("io/legado/app/help/book/BookExtensions.kt")
        val infoViewModel = readAppSource("io/legado/app/ui/book/info/BookInfoViewModel.kt")
        val infoActivity = readAppSource("io/legado/app/ui/book/info/BookInfoActivity.kt")

        assertTrue(dao.contains("origin = :expectedOrigin"))
        assertTrue(dao.contains("coverUrl is :expectedCoverUrl"))
        assertTrue(dao.contains("customCoverUrl is :expectedCustomCoverUrl"))
        assertTrue(dao.contains("persistedCoverUrl is :expectedPersistedCoverUrl"))
        assertTrue(
            dao.contains("persistedCoverUrl = getPersistedCoverUrl(book.bookUrl)")
        )
        assertTrue(dao.contains("if (has(newBook.bookUrl))"))
        assertTrue(extensions.contains("updatePreservingCustomCoverUrl(this)"))
        assertTrue(extensions.contains("savePreservingCustomCoverUrl"))
        assertTrue(infoViewModel.contains("preserveCustomCoverUrl: Boolean = true"))
        assertTrue(
            infoViewModel.contains("saveBook(book, preserveCustomCoverUrl = false)")
        )
        assertTrue(
            infoActivity.contains("saveBook(book, preserveCustomCoverUrl = false)")
        )
    }

    @Test
    fun `management exposes both restore levels without copying persisted paths into edits`() {
        val dao = readAppSource("io/legado/app/data/dao/BookDao.kt")
        val manage = readAppSource("io/legado/app/ui/book/manage/BookshelfManageViewModel.kt")
        val edit = readAppSource("io/legado/app/ui/book/info/edit/BookInfoEditActivity.kt")
        val restore = readAppSource("io/legado/app/help/storage/Restore.kt")

        assertTrue(dao.contains("fun clearPersistedCoverUrlIfUnchanged("))
        assertTrue(dao.contains("fun clearCoverOverridesIfUnchanged("))
        assertTrue(manage.contains("fun restoreNetworkCovers("))
        assertTrue(manage.contains("fun restoreSourceCovers("))
        assertTrue(manage.contains("val currentBook = appDb.bookDao.getBook(book.bookUrl)"))
        assertTrue(manage.contains("val operationId = beginCoverOperation()"))
        assertTrue(edit.contains("book.customCoverUrl?.takeIf { it.isNotEmpty() } ?: book.coverUrl"))
        assertTrue(edit.contains("book.persistedCoverUrl = null"))
        assertTrue(restore.contains("book.normalizeLegacyPersistedCover()"))
        assertTrue(restore.contains("book.persistedCoverUrl = book.persistedCoverUrl?.let"))
    }

    private fun readAppSource(path: String): String = sequenceOf(
        File("src/main/java"),
        File("app/src/main/java"),
    ).map { it.resolve(path) }
        .first(File::isFile)
        .readText()
}
