package io.legado.app.ui.rss.source.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class RssSourceEditLayoutTest {

    @Test
    fun `options use a compact card with a dedicated accessible header`() {
        val document = parse(LAYOUT_PATH)
        val card = document.elementById("options_card")
        val header = document.elementById("options_header")
        val summary = document.elementById("tv_options_summary")

        assertEquals(CARD_VIEW, card.tagName)
        assertEquals("@color/background_card", card.appAttribute("cardBackgroundColor"))
        assertEquals("8dp", card.appAttribute("cardCornerRadius"))
        assertFalse(card.hasAttributeNS(ANDROID_NAMESPACE, "clickable"))
        assertSame(card, header.parentNode?.parentNode)
        assertEquals("48dp", header.androidAttribute("minHeight"))
        assertEquals("true", header.androidAttribute("clickable"))
        assertEquals("true", header.androidAttribute("focusable"))
        assertEquals("true", summary.androidAttribute("singleLine"))
        assertEquals("end", summary.androidAttribute("ellipsize"))
        val arrow = document.elementById("iv_options_expand")
        assertEquals("@color/secondaryText", arrow.appAttribute("tint"))
        assertEquals("no", arrow.androidAttribute("importantForAccessibility"))
    }

    @Test
    fun `four existing switches wrap inside content that starts collapsed`() {
        val document = parse(LAYOUT_PATH)
        val content = document.elementById("options_content")
        val switches = document.elementById("options_switches")

        assertEquals("gone", content.androidAttribute("visibility"))
        assertEquals(FLEXBOX, switches.tagName)
        assertEquals("wrap", switches.appAttribute("flexWrap"))
        assertSame(content, switches.parentNode)
        listOf(
            "cb_is_enable",
            "cb_single_url",
            "cb_is_enable_cookie",
            "cb_is_enable_preload"
        ).forEach { id ->
            val checkBox = document.elementById(id)
            assertEquals(THEME_CHECK_BOX, checkBox.tagName)
            assertSame(switches, checkBox.parentNode)
            assertEquals("48dp", checkBox.androidAttribute("minHeight"))
        }
    }

    @Test
    fun `type selectors stay outside the collapsed option card`() {
        val document = parse(LAYOUT_PATH)
        val card = document.elementById("options_card")

        listOf("sp_type", "ly_type").forEach { id ->
            val selector = document.elementById(id)
            assertFalse(selector.hasAncestorWithId("options_card"))
            assertTrue(card.compareDocumentPosition(selector).toInt() and DOCUMENT_POSITION_FOLLOWING != 0)
        }
    }

    @Test
    fun `activity keeps summary and accessibility state in one update path`() {
        val source = File(repositoryRoot, ACTIVITY_PATH).readText()
        val initPanel = source.section("private fun initOptionPanel()", "private fun updateOptionPanel(")
        val updatePanel = source.section("private fun updateOptionPanel(", "private fun setEditEntities(")

        assertTrue(Regex("optionsHeader\\.setOnClickListener\\s*\\{").containsMatchIn(initPanel))
        CHECK_BOX_BINDINGS.forEach { binding ->
            assertTrue("$binding must refresh the summary", initPanel.contains(binding))
            assertTrue("$binding must be represented in the summary", updatePanel.contains(binding))
        }
        assertTrue(Regex("optionsContent\\.visibility\\s*=\\s*if \\(expanded\\)").containsMatchIn(updatePanel))
        assertTrue(Regex("ivOptionsExpand\\.setImageResource\\(").containsMatchIn(updatePanel))
        assertTrue(Regex("optionsHeader\\.contentDescription\\s*=").containsMatchIn(updatePanel))
    }

    private fun parse(path: String): Document =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File(repositoryRoot, path))

    private fun Document.elementById(id: String): Element {
        val nodes = getElementsByTagName("*")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .first { it.androidAttribute("id").endsWith("/$id") }
    }

    private fun Element.hasAncestorWithId(id: String): Boolean =
        generateSequence(parentNode) { it.parentNode }
            .filterIsInstance<Element>()
            .any { it.androidAttribute("id").endsWith("/$id") }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private fun Element.appAttribute(name: String): String =
        getAttributeNS(APP_NAMESPACE, name)

    private fun String.section(start: String, end: String): String =
        substringAfter(start).substringBefore(end)

    private val repositoryRoot: File by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }

    private companion object {
        const val LAYOUT_PATH = "app/src/main/res/layout/activity_rss_source_edit.xml"
        const val ACTIVITY_PATH =
            "app/src/main/java/io/legado/app/ui/rss/source/edit/RssSourceEditActivity.kt"
        const val CARD_VIEW = "androidx.cardview.widget.CardView"
        const val FLEXBOX = "com.google.android.flexbox.FlexboxLayout"
        const val THEME_CHECK_BOX = "io.legado.app.lib.theme.view.ThemeCheckBox"
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"
        const val DOCUMENT_POSITION_FOLLOWING = 4
        val CHECK_BOX_BINDINGS = listOf(
            "binding.cbIsEnable",
            "binding.cbSingleUrl",
            "binding.cbIsEnableCookie",
            "binding.cbIsEnablePreload"
        )
    }
}
