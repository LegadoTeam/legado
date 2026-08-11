package io.legado.app.help.book

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSaveFenceTest {

    @Test
    fun `authoritative replacement rejects an older network write`() {
        val fence = ContentSaveFence()
        val key = ContentSaveKey("book", 3)
        val requestVersion = fence.state(key).version
        var content = ""

        fence.replace(key, "replacement.nb") { content = "replacement" }

        assertFalse(
            fence.writeIfCurrent(key, requestVersion, "stale.nb") {
                content = "stale network content"
            }
        )
        assertEquals("replacement", content)
        assertEquals("replacement.nb", fence.state(key).fileName)
    }

    @Test
    fun `failed authoritative replacement still rejects an older network write`() {
        val fence = ContentSaveFence()
        val key = ContentSaveKey("book", 3)
        val requestVersion = fence.state(key).version
        var content = ""

        assertThrows(IllegalStateException::class.java) {
            fence.replace(key, "replacement.nb") {
                content = "replacement"
                error("metadata update failed")
            }
        }

        assertFalse(
            fence.writeIfCurrent(key, requestVersion, "stale.nb") {
                content = "stale network content"
            }
        )
        assertEquals("replacement", content)
        assertEquals(requestVersion + 1L, fence.state(key).version)
        assertEquals(null, fence.state(key).fileName)
    }

    @Test
    fun `initial network write does not retain fence state`() {
        val fence = ContentSaveFence()
        val key = ContentSaveKey("book", 3)
        var content = ""

        assertTrue(
            fence.writeIfCurrent(key, 0L, "refreshed.nb") {
                content = "refreshed"
            }
        )
        assertEquals("refreshed", content)
        assertEquals(null, fence.state(key).fileName)
    }

    @Test
    fun `authoritative replacement restores metadata after an earlier network write`() {
        val fence = ContentSaveFence()
        val key = ContentSaveKey("book", 3)
        var title = "original"

        fence.writeIfCurrent(key, 0L, "network.nb") { title = "network" }
        fence.replace(key, "replacement.nb") { title = "original" }

        assertEquals("original", title)
        assertEquals("replacement.nb", fence.state(key).fileName)
    }

    @Test
    fun `chapter metadata persists only inside accepted content write`() {
        val bookContent = readProjectFile(
            "src/main/java/io/legado/app/model/webBook/BookContent.kt"
        )
        val bookHelp = readProjectFile("src/main/java/io/legado/app/help/book/BookHelp.kt")
        val webBook = readProjectFile("src/main/java/io/legado/app/model/webBook/WebBook.kt")
        val chapterDao = readProjectFile(
            "src/main/java/io/legado/app/data/dao/BookChapterDao.kt"
        )
        val changeSource = readProjectFile(
            "src/main/java/io/legado/app/ui/book/changesource/ChangeChapterSourceViewModel.kt"
        )
        val fencedWrite = bookHelp.substringAfter(
            "val saved = contentSaveFence.writeIfCurrent("
        ).substringBefore("if (saved)")
        val replacementWrite = bookHelp.substringAfter("fun saveText(")
            .substringBefore("internal fun contentSaveToken")

        assertFalse(bookContent.contains("bookChapter.update()"))
        assertTrue(bookContent.contains("saveChapterMetadata = true"))
        assertTrue(fencedWrite.contains("if (saveChapterMetadata)"))
        assertTrue(fencedWrite.contains("updateContentMetadata"))
        assertTrue(replacementWrite.contains("if (saveChapterMetadata)"))
        assertTrue(replacementWrite.contains("updateContentMetadata"))
        assertTrue(webBook.contains("saveChapterMetadata = true"))
        assertTrue(changeSource.contains("saveChapterMetadata = true"))
        assertTrue(chapterDao.contains("bookUrl = :bookUrl and `index` = :index"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
