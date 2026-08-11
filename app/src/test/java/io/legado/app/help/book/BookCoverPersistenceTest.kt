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
    fun `only network display covers are eligible`() {
        val book = Book(
            bookUrl = "https://books.example/book",
            coverUrl = "https://images.example/source.jpg",
        )
        assertEquals("https://images.example/source.jpg", book.networkCoverForPersistence())

        book.customCoverUrl = "/data/user/0/com.legado/covers/local.cover"
        assertNull(book.networkCoverForPersistence())
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
    fun `batch download checks cancellation before database update`() {
        val source = readAppSource(
            "io/legado/app/ui/book/manage/BookshelfManageViewModel.kt"
        )
        assertTrue(source.contains("runInterruptible { target.get() }"))
        assertTrue(
            source.indexOf("currentCoroutineContext().ensureActive()") <
                source.indexOf("updateCustomCoverUrl(")
        )
    }

    private fun readAppSource(path: String): String = sequenceOf(
        File("src/main/java"),
        File("app/src/main/java"),
    ).map { it.resolve(path) }
        .first(File::isFile)
        .readText()
}
