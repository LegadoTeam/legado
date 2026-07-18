package io.legado.app.ui.book.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookIntroIndentTest {

    @Test
    fun `indents every non-empty unindented paragraph`() {
        val intro = "第一段\n第二段\n\n第三段"

        assertEquals(
            listOf("第一段\n", "第二段\n", "第三段"),
            introIndentRanges(intro).map { intro.substring(it.start, it.endExclusive) },
        )
    }

    @Test
    fun `keeps manual indentation and blank paragraphs unchanged`() {
        val intro = "　　全角缩进\n 普通空格\n\n未缩进"

        assertEquals(
            listOf("未缩进"),
            introIndentRanges(intro).map { intro.substring(it.start, it.endExclusive) },
        )
    }

    @Test
    fun `book info applies indentation only in plain text branch`() {
        val source = File(
            repositoryRoot,
            "app/src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt",
        ).readText()

        assertTrue(source.contains("setPlainBookIntro(tvIntro, intro)"))
        assertTrue(source.contains("LeadingMarginSpan.Standard(indentWidth, 0)"))
        assertTrue(source.contains("intro.startsWith(\"<useweb>\")"))
        assertTrue(source.contains("intro.startsWith(\"<usehtml>\")"))
        assertTrue(source.contains("intro.startsWith(\"<md>\")"))
    }

    private val repositoryRoot: File by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
