package io.legado.app.model.analyzeRule

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AnalyzeByJSoupDomTest {

    @Test
    fun `reading a book name does not replace its link with the author link`() {
        val document = Jsoup.parse(
            """
                <div class="row">
                    <span><a href="/posts/11946">Book title</a></span>
                    <span class="pull-right smaller-5"><a href="/users/456925">Author</a></span>
                </div>
            """.trimIndent(),
        )
        val original = document.outerHtml()
        val row = document.selectFirst(".row")!!
        val parser = AnalyzeByJSoup(row)

        repeat(2) {
            assertEquals("Book title", parser.getString("tag.span.0@tag.a.0@text"))
            assertEquals("Author", parser.getString("class.pull-right smaller-5@text"))
            assertEquals("/posts/11946", parser.getString("class.row@tag.span.0@tag.a.-1@href"))
            assertEquals(original, document.outerHtml())
        }
    }

    @Test
    fun `reading a forum title leaves its thread link available`() {
        val document = Jsoup.parse(
            """
                <table><tr>
                    <th><a href="/forum.php?mod=forumdisplay&amp;fid=19">Category</a>
                        <a class="bui_thlist_title" href="/forum.php?mod=viewthread&amp;tid=5276">Thread title</a>
                    </th>
                    <td class="by"><a href="/home.php?mod=space&amp;uid=2411">Author</a></td>
                </tr></table>
            """.trimIndent(),
        )
        val original = document.outerHtml()
        val parser = AnalyzeByJSoup(document.selectFirst("tr")!!)

        assertEquals("Thread title", parser.getString("th@.bui_thlist_title@text||th@a.0@text"))
        assertEquals("Author", parser.getString(".by.0@a.0@text||a.2@text"))
        assertEquals("/forum.php?mod=viewthread&tid=5276", parser.getString("th@a.1@href"))
        assertEquals(original, document.outerHtml())
    }

    @Test
    fun `chained element selection preserves the original document and parents`() {
        val document = Jsoup.parse("<div class=list><p><a href='/book'>Book</a></p></div>")
        val original = document.outerHtml()
        val link = document.selectFirst("a")!!
        val parent = link.parent()
        val parser = AnalyzeByJSoup(document)

        repeat(2) {
            val selected = parser.getElements("class.list@tag.p@tag.a")
            assertEquals(1, selected.size)
            assertSame(link, selected[0])
            assertSame(parent, selected[0].parent())
            assertEquals(original, document.outerHtml())
        }
    }

    @Test
    fun `excluding indexes filters results without removing document nodes`() {
        val rules = mapOf(
            "tag.a!0:2" to listOf("b"),
            "tag.a[!0,-1]" to listOf("b"),
            "tag.a!1" to listOf("a", "c"),
        )
        rules.forEach { (rule, expected) ->
            val document = Jsoup.parse("<p><a id=a>A</a><a id=b>B</a><a id=c>C</a></p>")
            val original = document.outerHtml()
            val parser = AnalyzeByJSoup(document)

            repeat(2) {
                assertEquals(rule, expected, parser.getElements(rule).map { it.id() })
                assertEquals(rule, original, document.outerHtml())
                assertEquals(listOf("a", "b", "c"), parser.getElements("tag.a").map { it.id() })
            }
        }
    }

    @Test
    fun `positive and negative indexes keep selection order and node identity`() {
        val document = Jsoup.parse("<p><a id=a>A</a><a id=b>B</a><a id=c>C</a></p>")
        val original = document.outerHtml()
        val parser = AnalyzeByJSoup(document)

        assertEquals(listOf("c", "a"), parser.getElements("tag.a[-1,0]").map { it.id() })
        assertSame(document.getElementById("a"), parser.getElements("tag.a.0")[0])
        assertEquals(original, document.outerHtml())
    }
}
