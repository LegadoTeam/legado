package io.legado.app.ci

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebHomeAssetTest {

    private val webRoot by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir)) { it.parentFile }
            .map { File(it, "app/src/main/assets/web") }
            .first { File(it, "index.html").isFile }
    }

    private val pageText by lazy { File(webRoot, "index.html").readText() }

    private val document by lazy { Jsoup.parse(pageText) }

    @Test
    fun `web home retains the supported navigation targets`() {
        assertEquals(1, document.select("html").size)
        assertEquals(1, document.select("head").size)
        assertEquals(1, document.select("body").size)

        val expectedTargets = setOf(
            "vue/index.html",
            "vue/index.html#/bookSource",
            "uploadBook/index.html",
            "vue/index.html#/rssSource",
        )
        val navigationLinks = document.select("nav.nav-grid > a.nav-item")

        assertEquals(expectedTargets, navigationLinks.map { it.attr("href") }.toSet())
        assertEquals(expectedTargets.size, navigationLinks.size)
        navigationLinks.forEach { link ->
            assertEquals("_blank", link.attr("target"))
            assertTrue(link.attr("rel").split(' ').containsAll(listOf("noopener", "noreferrer")))
        }
        assertTrue(File(webRoot, "vue/index.html").isFile)
        assertTrue(File(webRoot, "uploadBook/index.html").isFile)
    }

    @Test
    fun `web home remains script free and self contained`() {
        assertEquals(0, document.select("script").size)
        assertEquals(0, document.select("link[rel=stylesheet]").size)
        assertEquals(0, document.select("a[href^=\"javascript:\"]").size)
        assertEquals(0, document.select("iframe, object, embed").size)
        assertEquals(0, document.select("base, meta[http-equiv=refresh]").size)
        assertEquals(1, document.select("style").size)
        assertEquals(1, document.select("link[rel=icon][href=vue/favicon.ico]").size)
        assertFalse(pageText.contains("@import", ignoreCase = true))
        document.allElements.forEach { element ->
            element.attributes().forEach { attribute ->
                assertFalse(
                    "Inline event handler found: ${attribute.key}",
                    attribute.key.startsWith("on", ignoreCase = true),
                )
            }
        }
        val externalUrl = Regex("(?i)(?:https?:)?//")
        document.select("[src], [srcset], [poster], link[href]").forEach { element ->
            listOf("src", "srcset", "poster", "href").forEach { attribute ->
                if (element.hasAttr(attribute)) {
                    val resource = element.attr(attribute).trim()
                    assertFalse("External resource found: $resource", externalUrl.containsMatchIn(resource))
                }
            }
        }
        document.select("style").forEach { style ->
            assertFalse("External CSS resource found", externalUrl.containsMatchIn(style.data()))
        }
    }

    @Test
    fun `retired web home assets stay removed`() {
        val retiredAssets = listOf(
            "assets/css/main.css",
            "assets/js/dist.js",
            "assets/js/md5.js",
            "images/bg.jpg",
        )

        retiredAssets.forEach { relativePath ->
            assertFalse("Retired asset was restored: $relativePath", File(webRoot, relativePath).exists())
        }
        assertTrue(File(webRoot, "vue/favicon.ico").isFile)
    }
}
