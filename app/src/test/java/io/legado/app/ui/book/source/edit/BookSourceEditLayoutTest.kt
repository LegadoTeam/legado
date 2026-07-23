package io.legado.app.ui.book.source.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BookSourceEditLayoutTest {

    @Test
    fun `options use a compact card with one accessible header`() {
        val document = parse(LAYOUT_PATH)
        val card = document.elementById("options_card")
        val header = document.elementById("options_header")
        val summary = document.elementById("tv_options_summary")
        val arrow = document.elementById("iv_options_expand")

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
        assertEquals("no", summary.androidAttribute("importantForAccessibility"))
        assertEquals("@color/secondaryText", arrow.appAttribute("tint"))
        assertEquals("no", arrow.androidAttribute("importantForAccessibility"))
    }

    @Test
    fun `type and six existing switches start inside collapsed content`() {
        val document = parse(LAYOUT_PATH)
        val content = document.elementById("options_content")
        val typeRow = document.elementById("options_type")
        val spinner = document.elementById("sp_type")
        val switches = document.elementById("options_switches")

        assertEquals("gone", content.androidAttribute("visibility"))
        assertSame(content, typeRow.parentNode)
        assertEquals("48dp", typeRow.androidAttribute("minHeight"))
        assertTrue(spinner.hasAncestorWithId("options_content"))
        assertEquals("@array/book_type", spinner.androidAttribute("entries"))
        assertEquals(FLEXBOX, switches.tagName)
        assertEquals("wrap", switches.appAttribute("flexWrap"))
        assertSame(content, switches.parentNode)
        CHECK_BOX_IDS.forEach { (id, checked) ->
            val checkBox = document.elementById(id)
            assertEquals(THEME_CHECK_BOX, checkBox.tagName)
            assertSame(switches, checkBox.parentNode)
            assertEquals("48dp", checkBox.androidAttribute("minHeight"))
            assertEquals(checked, checkBox.androidAttribute("checked"))
        }
    }

    @Test
    fun `activity refreshes summary and keeps every source option mapping`() {
        val source = File(repositoryRoot, ACTIVITY_PATH).readText()
        val initPanel = source.section("private fun initOptionPanel()", "private fun updateOptionPanel(")
        val updatePanel = source.section("private fun updateOptionPanel(", "override fun finish()")
        val upSource = source.section("private fun upSourceView(", "private fun applyPendingEditResult(")
        val getSource = source.section("private fun getSource(): BookSource", "sourceEntities.forEach")

        assertTrue(initPanel.contains("spType.onItemSelectedListener"))
        assertTrue(updatePanel.contains("spType.selectedItem"))
        assertTrue(upSource.contains("spType.setSelection"))
        assertTrue(getSource.contains("source.bookSourceType = when (binding.spType.selectedItemPosition)"))
        listOf(
            "4 -> BookSourceType.video",
            "3 -> BookSourceType.file",
            "2 -> BookSourceType.image",
            "1 -> BookSourceType.audio",
            "else -> BookSourceType.default"
        ).forEach { mapping ->
            assertTrue("missing type mapping: $mapping", getSource.contains(mapping))
        }
        listOf(
            "BookSourceType.video -> 4",
            "BookSourceType.file -> 3",
            "BookSourceType.image -> 2",
            "BookSourceType.audio -> 1",
            "else -> 0"
        ).forEach { mapping ->
            assertTrue("missing type restore mapping: $mapping", upSource.contains(mapping))
        }
        CHECK_BOX_BINDINGS.forEach { binding ->
            assertTrue("$binding must refresh the summary", initPanel.contains(binding))
            assertTrue("$binding must appear in the summary", updatePanel.contains(binding))
            assertTrue("$binding must be restored", upSource.contains(binding))
        }
        SAVE_MAPPINGS.forEach { mapping ->
            assertTrue("missing save mapping: $mapping", source.contains(mapping))
        }
        assertTrue(upSource.contains("updateOptionPanel()"))
        assertTrue(updatePanel.contains("optionsHeader.contentDescription"))
    }

    @Test
    fun `ported panel stays independent from legadoT theme stack`() {
        val source = listOf(LAYOUT_PATH, ACTIVITY_PATH)
            .joinToString("\n") { File(repositoryRoot, it).readText() }

        listOf(
            "Widget.Material3",
            "M3ColorScheme",
            "AppColorScheme",
            "FieldNavBar",
            "skin_"
        ).forEach { dependency ->
            assertFalse("unexpected dependency: $dependency", source.contains(dependency))
        }
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
        const val LAYOUT_PATH = "app/src/main/res/layout/activity_book_source_edit.xml"
        const val ACTIVITY_PATH =
            "app/src/main/java/io/legado/app/ui/book/source/edit/BookSourceEditActivity.kt"
        const val CARD_VIEW = "androidx.cardview.widget.CardView"
        const val FLEXBOX = "com.google.android.flexbox.FlexboxLayout"
        const val THEME_CHECK_BOX = "io.legado.app.lib.theme.view.ThemeCheckBox"
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"
        val CHECK_BOX_IDS = listOf(
            "cb_is_enable" to "true",
            "cb_is_enable_explore" to "true",
            "cb_is_enable_cookie" to "true",
            "cb_is_enable_review" to "false",
            "cb_is_event_listener" to "false",
            "cb_is_custom_button" to "false"
        )
        val CHECK_BOX_BINDINGS = listOf(
            "binding.cbIsEnable",
            "binding.cbIsEnableExplore",
            "binding.cbIsEnableCookie",
            "binding.cbIsEnableReview",
            "binding.cbIsEventListener",
            "binding.cbIsCustomButton"
        )
        val SAVE_MAPPINGS = listOf(
            "source.enabled = binding.cbIsEnable.isChecked",
            "source.enabledExplore = binding.cbIsEnableExplore.isChecked",
            "source.enabledCookieJar = binding.cbIsEnableCookie.isChecked",
            "reviewRule.enabled = binding.cbIsEnableReview.isChecked",
            "source.eventListener = binding.cbIsEventListener.isChecked",
            "source.customButton = binding.cbIsCustomButton.isChecked"
        )
    }
}
