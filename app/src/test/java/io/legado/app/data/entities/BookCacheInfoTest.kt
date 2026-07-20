package io.legado.app.data.entities

import io.legado.app.constant.BookType
import io.legado.app.help.book.getFolderNameNoCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookCacheInfoTest {

    @Test
    fun `cache projection keeps the existing folder naming behavior`() {
        val names = listOf(
            "",
            "0123456789abcdef",
            "书/名:*?\"<>|.",
            "三体全集",
        )

        names.forEachIndexed { index, name ->
            val cacheInfo = cacheInfo(
                bookUrl = "https://example.com/book/$index",
                name = name,
            )
            val book = Book(bookUrl = cacheInfo.bookUrl, name = cacheInfo.name)

            assertEquals(name, book.getFolderNameNoCache(), cacheInfo.getFolderName())
        }
    }

    @Test
    fun `legacy local and webdav books keep case insensitive epub detection`() {
        assertTrue(cacheInfo(origin = BookType.localTag, originName = "BOOK.EPUB").isEpub)
        assertTrue(cacheInfo(origin = "${BookType.webDavTag}folder", originName = "book.EpUb").isEpub)
        assertFalse(cacheInfo(origin = "https://example.com", originName = "book.epub").isEpub)
    }

    @Test
    fun `non legacy books require the local type flag for epub detection`() {
        assertTrue(
            cacheInfo(
                origin = "https://example.com",
                originName = "book.EPUB",
                type = BookType.text or BookType.local,
            ).isEpub,
        )
        assertFalse(
            cacheInfo(
                origin = BookType.localTag,
                originName = "book.epub",
                type = BookType.text,
            ).isEpub,
        )
    }

    @Test
    fun `cache cleanup queries stay projected transactional and flag aware`() {
        val daoSource = source("app/src/main/java/io/legado/app/data/dao/BookDao.kt")
        val helpSource = source("app/src/main/java/io/legado/app/help/book/BookHelp.kt")

        assertTrue(
            daoSource.contains(
                "SELECT bookUrl, name, origin, originName, type FROM books",
            ),
        )
        assertTrue(daoSource.contains("WHERE (type & \${BookType.image}) > 0"))
        assertTrue(
            Regex("""@Transaction\s+fun getCacheCleanupSnapshot\(""")
                .containsMatchIn(daoSource),
        )
        assertTrue(
            daoSource.contains(
                "if (includeImageBooks) getCacheCleanupImageBooks() else emptyList()",
            ),
        )
        assertTrue(helpSource.contains("includeImageBooks = AppConfig.imageRetainNum > 0"))
        assertFalse(helpSource.contains("appDb.bookDao.all"))
    }

    private fun cacheInfo(
        bookUrl: String = "book-url",
        name: String = "book-name",
        origin: String = BookType.localTag,
        originName: String = "book.txt",
        type: Int = 0,
    ): BookCacheInfo {
        return BookCacheInfo(
            bookUrl = bookUrl,
            name = name,
            origin = origin,
            originName = originName,
            type = type,
        )
    }

    private fun source(relativePath: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, relativePath).readText().replace("\r\n", "\n")
    }
}
