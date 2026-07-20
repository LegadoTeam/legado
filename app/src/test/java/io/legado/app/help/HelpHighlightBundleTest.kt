package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HelpHighlightBundleTest {

    @Test
    fun `bundle registers only help page languages and aliases`() {
        val registrations = registrations(bundleFile().readText())

        assertEquals(setOf("java", "javascript", "plaintext", "xml"), registrations.keys)
        assertTrue("js alias is missing", "js" in aliases(registrations.getValue("javascript")))
        assertTrue("html alias is missing", "html" in aliases(registrations.getValue("xml")))
        assertTrue("txt alias is missing", "txt" in aliases(registrations.getValue("plaintext")))
    }

    @Test
    fun `all help markdown fence languages remain supported`() {
        val markdownRoot = File(repositoryRoot(), HELP_MARKDOWN_DIRECTORY)
        val languages = markdownRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
            .flatMap { file ->
                FENCE_LANGUAGE.findAll(file.readText())
                    .map { match -> match.groupValues[1].lowercase() }
            }
            .toSet()

        assertEquals(setOf("html", "java", "js", "txt", "xml"), languages)
    }

    @Test
    fun `bundle stays within the focused size budget`() {
        val bundle = bundleFile()
        val content = bundle.readText()

        assertTrue("Highlight bundle is unexpectedly empty", bundle.length() > 20_000)
        assertTrue(
            "Highlight bundle grew beyond $MAX_BUNDLE_BYTES bytes: ${bundle.length()}",
            bundle.length() <= MAX_BUNDLE_BYTES,
        )
        assertTrue(content.contains("Highlight.js 10.7.0 (7ec45af1)"))
    }

    private fun registrations(bundle: String): Map<String, String> {
        val matches = REGISTRATION.findAll(bundle).toList()
        return matches.mapIndexed { index, match ->
            val end = matches.getOrNull(index + 1)?.range?.first ?: bundle.length
            match.groupValues[1] to bundle.substring(match.range.first, end)
        }.toMap()
    }

    private fun aliases(registration: String): Set<String> {
        val aliasList = ALIASES.find(registration)?.groupValues?.get(1).orEmpty()
        return QUOTED_VALUE.findAll(aliasList)
            .map { match -> match.groupValues[1] }
            .toSet()
    }

    private fun bundleFile(): File {
        return File(repositoryRoot(), HIGHLIGHT_BUNDLE)
    }

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }

    private companion object {
        const val MAX_BUNDLE_BYTES = 40 * 1024L
        const val HELP_MARKDOWN_DIRECTORY = "app/src/main/assets/web/help/md"
        const val HIGHLIGHT_BUNDLE =
            "app/src/main/assets/web/help/js/highlight.min.js"
        val REGISTRATION = Regex("""hljs\.registerLanguage\(["']([^"']+)["']""")
        val ALIASES = Regex("""aliases:\[([^]]*)]""")
        val QUOTED_VALUE = Regex("""["']([^"']+)["']""")
        val FENCE_LANGUAGE = Regex(
            """(?m)^[\t ]*```([A-Za-z0-9_+-]+)[\t ]*\r?$""",
        )
    }
}
