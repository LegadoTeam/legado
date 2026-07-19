package io.legado.app.service

import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.TocEmptyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CheckSourceChapterSelectionTest {

    @Test
    fun `all volume headings are treated as an empty toc`() {
        val error = assertThrows(TocEmptyException::class.java) {
            selectCheckSourceChapter(
                chapters = listOf(
                    volume("第一卷"),
                    volume("第二卷"),
                ),
                emptyMessage = "目录为空",
            )
        }

        assertEquals("目录为空", error.message)
    }

    @Test
    fun `mixed toc skips volume headings and selects readable chapters`() {
        val firstChapter = chapter("第一章", "/chapter/1")
        val secondChapter = chapter("第二章", "/chapter/2")

        val selection = selectCheckSourceChapter(
            chapters = listOf(
                volume("第一卷"),
                firstChapter,
                volume("第二卷"),
                secondChapter,
            ),
            emptyMessage = "目录为空",
        )

        assertSame(firstChapter, selection.chapter)
        assertEquals(secondChapter.url, selection.nextChapterUrl)
    }

    @Test
    fun `volume chapter with an independent url remains readable`() {
        val volumeChapter = BookChapter(
            title = "第一卷",
            url = "/chapter/volume-1",
            isVolume = true,
        )

        val selection = selectCheckSourceChapter(
            chapters = listOf(volumeChapter),
            emptyMessage = "目录为空",
        )

        assertSame(volumeChapter, selection.chapter)
        assertEquals(volumeChapter.url, selection.nextChapterUrl)
    }

    @Test
    fun `normal single chapter toc reuses the chapter url as next url`() {
        val firstChapter = chapter("第一章", "/chapter/1")

        val selection = selectCheckSourceChapter(
            chapters = listOf(firstChapter),
            emptyMessage = "目录为空",
        )

        assertSame(firstChapter, selection.chapter)
        assertEquals(firstChapter.url, selection.nextChapterUrl)
    }

    private fun volume(title: String) = BookChapter(
        title = title,
        url = title,
        isVolume = true,
    )

    private fun chapter(title: String, url: String) = BookChapter(
        title = title,
        url = url,
    )
}
