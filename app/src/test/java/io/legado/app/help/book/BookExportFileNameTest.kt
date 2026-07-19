package io.legado.app.help.book

import org.junit.Assert.assertEquals
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
                "normalizeExportFileName(RhinoScriptEngine.eval(jsStr, bindings).toString(), suffix)",
            ),
        )
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
