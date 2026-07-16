package io.legado.app.ci

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UploadBookAssetTest {

    private val pageText by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val pageFile = generateSequence(File(userDir)) { it.parentFile }
            .map { File(it, "app/src/main/assets/web/uploadBook/index.html") }
            .first { it.isFile }
        pageFile.readText().replace("\r\n", "\n")
    }

    private val document by lazy { Jsoup.parse(pageText) }

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
            "script[src=./js/jquery-1.4.2.min.js]",
            "script[src=./js/html5_fun.js]",
            "script[src=./js/common.js]",
        )

        requiredSelectors.forEach { selector ->
            assertEquals("Missing upload page selector: $selector", 1, document.select(selector).size)
        }
        assertTrue(document.body().text().contains("TXT、EPUB、UMD、PDF、MOBI、AZW3、AZW"))
    }
}
