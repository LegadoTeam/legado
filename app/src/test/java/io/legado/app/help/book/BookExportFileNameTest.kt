package io.legado.app.help.book

import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookExportFileNameTest {

    @Test
    fun `normalizes reserved characters in ordinary default export name`() {
        assertEquals(
            "书_名 作者：作_者.txt",
            normalizeExportFileName("书/名 作者：作|者", "txt"),
        )
    }

    @Test
    fun `normalizes reserved characters in custom script export name`() {
        assertEquals(
            "分类_书_名_.epub",
            normalizeExportFileName("分类:书*名?", "epub"),
        )
    }

    @Test
    fun `normalizes reserved characters in split default export name`() {
        assertEquals(
            "书_名 作者：作_者 [2].epub",
            normalizeExportFileName("书/名 作者：作|者 [2]", "epub"),
        )
    }

    @Test
    fun `rejects null undefined and blank script results`() {
        assertNull(parseExportFileNameResult(RhinoScriptEngine.eval("null")))
        assertNull(parseExportFileNameResult(RhinoScriptEngine.eval("undefined")))
        assertNull(parseExportFileNameResult(""))
        assertNull(parseExportFileNameResult("   "))
    }

    @Test
    fun `keeps non-blank script result for normalization`() {
        assertEquals("分类:书名", parseExportFileNameResult("分类:书名"))
        assertEquals("0", parseExportFileNameResult(0))
        assertEquals("false", parseExportFileNameResult(false))
    }

    @Test
    fun `split export falls back when script result is empty`() {
        val book = Book(name = "书/名", author = "作|者")
        val expected = "书_名 作者：作_者 [2].epub"

        listOf("null", "undefined", "''", "'   '").forEach { script ->
            assertEquals(expected, book.getExportFileName("epub", 2, script))
        }
    }

    @Test
    fun `split export keeps valid custom result and normalizes it`() {
        val book = Book(name = "书/名", author = "作|者")

        assertEquals(
            "书_名_作_者_2.epub",
            book.getExportFileName("epub", 2, "name + ':' + author + ':' + epubIndex"),
        )
    }

    @Test
    fun `export rule validation rejects empty results`() {
        assertFalse(tryParesExportFileName("null"))
        assertFalse(tryParesExportFileName("undefined"))
        assertFalse(tryParesExportFileName("''"))
        assertFalse(tryParesExportFileName("'   '"))
        assertTrue(tryParesExportFileName("name + ' ' + author"))
    }

    @Test
    fun `both export overloads normalize defaults scripts and failure fallbacks`() {
        val source = File(
            repositoryRoot,
            "app/src/main/java/io/legado/app/help/book/BookExtensions.kt",
        ).readText()

        assertTrue(
            source.contains(
                "val default = normalizeExportFileName(\"\$name 作者：\${getRealAuthor()}\", suffix)",
            ),
        )
        assertTrue(source.contains("\"\$name 作者：\${getRealAuthor()} [\${epubIndex}]\","))
        assertEquals(
            2,
            source.countOccurrences(
                "val customName = parseExportFileNameResult(RhinoScriptEngine.eval(jsStr, bindings))",
            ),
        )
        assertEquals(2, source.countOccurrences("?: return@runCatching default"))
        assertEquals(2, source.countOccurrences("normalizeExportFileName(customName, suffix)"))
        assertEquals(2, source.countOccurrences("}.getOrDefault(default)"))
    }

    private fun String.countOccurrences(value: String): Int {
        return split(value).size - 1
    }

    private val repositoryRoot: File by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
