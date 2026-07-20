package io.legado.app.config

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class StaticAssetOptimizationTest {

    @Test
    fun `textmate json assets are parseable and canonically compact`() {
        val root = repositoryRoot()
        val textMateRoot = File(root, TEXTMATE_ROOT)
        val files = textMateRoot.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()
        val relativePaths = files.map { it.relativeTo(textMateRoot).invariantSeparatorsPath }

        assertEquals(TEXTMATE_JSON_FILES, relativePaths)
        files.forEach { file ->
            val content = file.readText(Charsets.UTF_8)
            val compactJson = JsonParser.parseString(content).toString()
            assertEquals(
                "${file.relativeTo(root).invariantSeparatorsPath} is not compact JSON",
                compactJson,
                content,
            )
        }
    }

    private fun repositoryRoot(): File {
        val userDirectory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        return generateSequence(userDirectory) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }

    private companion object {
        const val TEXTMATE_ROOT = "app/src/main/assets/textmate"

        val TEXTMATE_JSON_FILES = listOf(
            "d_abyss.json",
            "d_modern.json",
            "d_monokai.json",
            "d_monokai_dimmed.json",
            "d_solarized.json",
            "html/language-configuration.json",
            "html/syntaxes/html.tmLanguage.json",
            "javascript/language-configuration.json",
            "javascript/syntaxes/javascript.tmLanguage.json",
            "l_modern.json",
            "l_quiet.json",
            "l_solarized.json",
            "languages.json",
            "markdown/language-configuration.json",
            "markdown/syntaxes/markdown.tmLanguage.json",
        )
    }
}
