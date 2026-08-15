package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Test

class HelpSectionsTest {

    @Test
    fun includesNestedH3SectionsWhenH2SectionsExist() {
        val sections = parseHelpSections(
            "## One\nIntro\n### First\n1\n### Second\n2\n## Two\n### Only\n3",
        )

        assertEquals(
            listOf(
                0 to "One",
                1 to "First",
                1 to "Second",
                0 to "Two",
            ),
            sections.map { it.depth to it.title },
        )
        assertEquals("### First\n1", sections[1].markdown)
    }

    @Test
    fun parsesSupportedHeadingLayouts() {
        data class Case(
            val name: String,
            val markdown: String,
            val expected: List<Pair<String, String>>,
        )

        val cases = listOf(
            Case(
                name = "H2 priority, CRLF and three-space heading",
                markdown = "# Document\r\nPreamble\r\n   ## One\r\n### Child\r\nA\r\n## Two\r\nB",
                expected = listOf(
                    "One" to "   ## One\n### Child\nA",
                    "Two" to "## Two\nB",
                ),
            ),
            Case(
                name = "H3 fallback when only one H2 exists",
                markdown = "## Document\nIntro\n### One\n1\n### Two\n2",
                expected = listOf(
                    "One" to "### One\n1",
                    "Two" to "### Two\n2",
                ),
            ),
            Case(
                name = "fences require matching marker length and blank suffix",
                markdown = "## One\n````md\n## Fake one\n```\n````not-close\n~~~\n## Fake two\n````\n~~~~\n## Fake three\n~~~\n~~~~\n## Two\n2",
                expected = listOf(
                    "One" to "## One\n````md\n## Fake one\n```\n````not-close\n~~~\n## Fake two\n````\n~~~~\n## Fake three\n~~~\n~~~~",
                    "Two" to "## Two\n2",
                ),
            ),
            Case(
                name = "four-space heading is code, not a section",
                markdown = "## One\n    ## Fake\n1\n## Two\n2",
                expected = listOf(
                    "One" to "## One\n    ## Fake\n1",
                    "Two" to "## Two\n2",
                ),
            ),
            Case(
                name = "four-space fence does not open a fenced block",
                markdown = "## One\n    ```\n## Two\n2",
                expected = listOf(
                    "One" to "## One\n    ```",
                    "Two" to "## Two\n2",
                ),
            ),
            Case(
                name = "fewer than two headings",
                markdown = "## Only\n### Child",
                expected = emptyList(),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                parseHelpSections(case.markdown).map { it.title to it.markdown },
            )
        }
    }
}
