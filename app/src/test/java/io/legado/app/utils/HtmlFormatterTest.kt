package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HtmlFormatterTest {

    @Test
    fun `regular formatter keeps paragraph indentation`() {
        listOf(
            null,
            "",
            "第一段\n第二段",
            "<p>第一段</p><p>第二段</p>",
            " \n<div>A&nbsp;&nbsp;</div><!-- hidden --><div>B</div>\n"
        ).forEach { input ->
            assertEquals(legacyFormat(input), HtmlFormatter.format(input))
        }

        val keepImagesRegex = "</?(?!img)[a-zA-Z]+(?=[ >])[^<>]*>".toRegex()
        val imageHtml = "<p>正文</p><img src=\"cover.jpg\"><p>结尾</p>"
        assertEquals(
            legacyFormat(imageHtml, keepImagesRegex),
            HtmlFormatter.format(imageHtml, keepImagesRegex)
        )
    }

    @Test
    fun `intro formatter keeps paragraphs left aligned`() {
        assertEquals(
            "第一段\n第二段",
            HtmlFormatter.formatIntro("<p>第一段</p><p>第二段</p>")
        )
        assertEquals("第一段\n第二段", HtmlFormatter.formatIntro("第一段\n第二段"))
    }

    @Test
    fun `intro formatter cleans markup without inventing indentation`() {
        val intro = " \n<div>别名：A&nbsp;&nbsp;</div><!-- hidden --><div>主角：B</div>\n"

        assertEquals("别名：A\n主角：B", HtmlFormatter.formatIntro(intro))
        assertEquals("　　手动缩进\n第二行", HtmlFormatter.formatIntro("　　手动缩进\n第二行"))
        assertEquals("", HtmlFormatter.formatIntro(null))
    }

    @Test
    fun `book introduction ingestion uses intro formatting only`() {
        val introSources = listOf(
            "app/src/main/java/io/legado/app/model/webBook/BookInfo.kt",
            "app/src/main/java/io/legado/app/model/webBook/BookList.kt",
            "app/src/main/java/io/legado/app/model/localBook/MobiFile.kt"
        )
        introSources.forEach { path ->
            assertTrue(File(repositoryRoot, path).readText().contains("HtmlFormatter.formatIntro("))
        }

        val bookInfo = File(repositoryRoot, introSources.first()).readText()
        assertTrue(bookInfo.contains("startsWith(\"<usehtml>\")"))
        assertTrue(bookInfo.contains("startsWith(\"<md>\")"))
        assertTrue(bookInfo.contains("startsWith(\"<useweb>\")"))
    }

    private val repositoryRoot: File by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }

    private fun legacyFormat(
        html: String?,
        otherRegex: Regex = "</?[a-zA-Z]+(?=[ >])[^<>]*>".toRegex()
    ): String {
        html ?: return ""
        return html.replace("(&nbsp;)+".toRegex(), " ")
            .replace("(&ensp;|&emsp;)".toRegex(), " ")
            .replace("(&thinsp;|&zwnj;|&zwj;|\u2009|\u200C|\u200D)".toRegex(), "")
            .replace("</?(?:div|p|br|hr|h\\d|article|dd|dl)[^>]*>".toRegex(), "\n")
            .replace("<!--[^>]*-->".toRegex(), "")
            .replace(otherRegex, "")
            .replace("\\s*\\n+\\s*".toRegex(), "\n　　")
            .replace("^[\\n\\s]+".toRegex(), "　　")
            .replace("[\\n\\s]+$".toRegex(), "")
    }
}
