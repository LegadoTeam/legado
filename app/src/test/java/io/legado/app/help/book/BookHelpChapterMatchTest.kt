package io.legado.app.help.book

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Test

class BookHelpChapterMatchTest {

    @Test
    fun `unique normalized title matches anywhere in the target toc`() {
        val targets = MutableList(20) { index ->
            BookChapter(index = index, title = "第${index + 100}章 其他$index")
        }.apply {
            this[17] = BookChapter(index = 217, title = "第217章 重逢")
        }

        assertEquals(
            ChapterSourceMatch.Unique(17),
            matchChapterSource(
                BookChapter(index = 7, title = "第七章 重逢"),
                targets,
            ),
        )
    }

    @Test
    fun `chapter number is used only when no title matches`() {
        assertEquals(
            ChapterSourceMatch.Unique(1),
            matchChapterSource(
                BookChapter(index = 12, title = "第12章 原名"),
                listOf(
                    BookChapter(index = 30, title = "第11章 其他"),
                    BookChapter(index = 31, title = "第12章 新名"),
                ),
            ),
        )
    }

    @Test
    fun `duplicate titles and chapter numbers stay ambiguous`() {
        assertEquals(
            ChapterSourceMatch.Ambiguous(listOf(0, 1)),
            matchChapterSource(
                BookChapter(index = 8, title = "第八章 重逢"),
                listOf(
                    BookChapter(index = 20, title = "第20章 重逢"),
                    BookChapter(index = 21, title = "第21章 重逢"),
                ),
            ),
        )
        assertEquals(
            ChapterSourceMatch.Ambiguous(listOf(0, 2)),
            matchChapterSource(
                BookChapter(index = 12, title = "第12章 原名"),
                listOf(
                    BookChapter(index = 30, title = "第12章 新名一"),
                    BookChapter(index = 31, title = "第13章 其他"),
                    BookChapter(index = 32, title = "第12话 新名二"),
                ),
            ),
        )
    }

    @Test
    fun `missing matches never fall back to the old index and volumes are ignored`() {
        assertEquals(
            ChapterSourceMatch.Missing,
            matchChapterSource(
                BookChapter(index = 1, title = "第99章 不存在"),
                listOf(
                    BookChapter(index = 0, title = "第99章 不存在", isVolume = true),
                    BookChapter(index = 1, title = "第1章 其他"),
                ),
            ),
        )
    }

    @Test
    fun `titles with the same characters in a different order do not auto match`() {
        assertEquals(
            ChapterSourceMatch.Missing,
            matchChapterSource(
                BookChapter(index = 1, title = "第一章 开始行动"),
                listOf(BookChapter(index = 2, title = "第二章 行动开始")),
            ),
        )
    }
}
