package io.legado.app.ci

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UploadBookAssetTest {

    private val uploadBookDir by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir)) { it.parentFile }
            .map { File(it, "app/src/main/assets/web/uploadBook") }
            .first { it.isDirectory }
    }

    private val pageText by lazy { asset("index.html") }
    private val commonScript by lazy { asset("js/common.js") }
    private val html5Script by lazy { asset("js/html5_fun.js") }
    private val document by lazy { Jsoup.parse(pageText) }

    private fun asset(relativePath: String): String {
        return File(uploadBookDir, relativePath).readText().replace("\r\n", "\n")
    }

    @Test
    fun `upload page excludes browser extension injections`() {
        val extensionMarkers = listOf(
            "Toothbrush",
            "Evernote",
            "Skitch",
            "remove-web-limits",
            "<tbdiv",
        )

        extensionMarkers.forEach { marker ->
            assertFalse("Unexpected browser extension marker: $marker", pageText.contains(marker, true))
        }
        assertEquals(1, document.select("html").size)
        assertEquals(1, document.select("head").size)
        assertEquals(1, document.select("body").size)
    }

    @Test
    fun `upload page retains its controls formats and scripts`() {
        val requiredSelectors = listOf(
            "input#click[multiple]",
            "input#tap",
            "#drag",
            "#mask_1",
            "#mask_2",
            "link[href=./css/wifi_send.css]",
            "script[src=./js/common.js]",
            "script[src=./js/html5_fun.js]",
        )

        requiredSelectors.forEach { selector ->
            assertEquals("Missing upload page selector: $selector", 1, document.select(selector).size)
        }
        assertTrue(document.body().text().contains("TXT、EPUB、UMD、PDF、MOBI、AZW3、AZW"))
    }

    @Test
    fun `upload page uses native dom without jquery`() {
        assertFalse(pageText.contains("jquery", ignoreCase = true))
        assertFalse(File(uploadBookDir, "js/jquery-1.4.2.min.js").exists())

        listOf(pageText, commonScript, html5Script).forEach { source ->
            assertFalse(source.contains("${'$'}("))
        }
        assertTrue(commonScript.contains("textContent"))
        assertTrue(commonScript.contains("innerText"))
        assertTrue(commonScript.contains("createUploadItemRow"))
        assertTrue(html5Script.contains("var finished = false"))
        assertTrue(html5Script.contains("if(finished)"))
        assertTrue(html5Script.contains("uploadError(file)"))

        val scriptSources = document.select("script[src]").map { it.attr("src") }
        val commonIndex = scriptSources.indexOf("./js/common.js")
        val html5Index = scriptSources.indexOf("./js/html5_fun.js")
        assertTrue(commonIndex in 0 until html5Index)
    }
}
