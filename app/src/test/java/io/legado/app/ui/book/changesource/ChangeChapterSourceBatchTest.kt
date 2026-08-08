package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangeChapterSourceBatchTest {

    @Test
    fun `selected target chapters keep toc order and next urls`() {
        val chapters = listOf(
            BookChapter(index = 10, url = "volume", isVolume = true),
            BookChapter(index = 11, url = "part-1"),
            BookChapter(index = 12, url = "part-2"),
            BookChapter(index = 13, url = "next"),
        )

        assertEquals(
            listOf("part-1" to "part-2", "part-2" to "next"),
            selectedChapterSourceItems(chapters, setOf(12, 10, 11)).map { (chapter, next) ->
                chapter.url to next
            },
        )
    }

    @Test
    fun `merged content only inserts a line break after sentence punctuation`() {
        assertEquals(
            "第一段。\n第二段第三段！\n第四段",
            mergeChapterSourceContents(
                listOf("第一段。", "第二段", "第三段！", "第四段")
            ),
        )
        assertEquals(
            "第一段。\n第二段",
            mergeChapterSourceContents(listOf("第一段。\n", "第二段")),
        )
        assertEquals(
            "第一段。\n 第二段",
            mergeChapterSourceContents(listOf("第一段。\n ", "第二段")),
        )
    }

    @Test
    fun `next original chapter skips volume rows`() {
        val chapters = listOf(
            BookChapter(index = 8, title = "当前章"),
            BookChapter(index = 9, title = "第二卷", isVolume = true),
            BookChapter(index = 10, title = "下一章"),
        )

        assertEquals(10, nextChapterSourceOriginal(chapters, 8)?.index)
        assertNull(nextChapterSourceOriginal(chapters, 10))
    }

    @Test
    fun `batch cache cancels reader loads before its final save`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/book/changesource/ChangeChapterSourceViewModel.kt"
        ).readText()
        val merged = source.indexOf("val mergedContent = mergeChapterSourceContents(contents)")
        val cancel = source.indexOf("ReadBook.cancelContentLoading()")
        val ensure = source.indexOf("ensureActive()", cancel)
        val save = source.indexOf("BookHelp.saveText(", ensure)

        assertTrue(merged >= 0)
        assertTrue(cancel > merged)
        assertTrue(ensure > cancel)
        assertTrue(save > ensure)
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
    }
}
