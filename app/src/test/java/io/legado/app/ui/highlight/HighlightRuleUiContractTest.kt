package io.legado.app.ui.highlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class HighlightRuleUiContractTest {

    @Test
    fun `rule manager is registered and reachable from reading`() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        val readMenu = parseXml("src/main/res/menu/book_read.xml")
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText()

        assertTrue(manifest.contains(".ui.highlight.HighlightRuleActivity"))
        assertEquals(1, readMenu.elementsWithAndroidId("@+id/menu_highlight_rule"))
        assertTrue(activity.contains("R.id.menu_highlight_rule -> startActivity<HighlightRuleActivity>()"))
    }

    @Test
    fun `reading actions use the themed popup menu`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText()

        assertTrue(activity.contains("ACTION_HIGHLIGHT_CREATE_RULE"))
        assertTrue(activity.contains("popupActionMenu(this)"))
        assertFalse(activity.contains("private var highlightActionMenu:"))
        assertFalse(activity.contains("HighlightRulePopup(this"))
    }

    @Test
    fun `rule editor reuses the current style host`() {
        val editor = projectFile(
            "src/main/java/io/legado/app/ui/highlight/edit/HighlightRuleEditDialog.kt"
        ).readText()
        val layout = parseXml("src/main/res/layout/dialog_highlight_rule_edit.xml")

        assertTrue(editor.contains("HighlightStyleDialog.StyleHost"))
        assertTrue(editor.contains("setColorPickerDialogListener(this)"))
        assertEquals(1, layout.elementsWithAndroidId("@+id/cb_apply_to_body"))
        assertEquals(1, layout.elementsWithAndroidId("@+id/cb_apply_to_title"))
    }

    @Test
    fun `rule list has empty state and row actions`() {
        val activity = parseXml("src/main/res/layout/activity_highlight_rule.xml")
        val item = parseXml("src/main/res/layout/item_highlight_rule.xml")
        val menu = parseXml("src/main/res/menu/highlight_rule.xml")

        assertEquals(1, activity.elementsWithAndroidId("@+id/tv_empty_msg"))
        assertEquals(1, activity.elementsWithAndroidId("@+id/select_action_bar"))
        assertEquals(1, item.elementsWithAndroidId("@+id/cb_name"))
        assertEquals(1, item.elementsWithAndroidId("@+id/swt_enabled"))
        assertEquals(1, item.elementsWithAndroidId("@+id/iv_edit"))
        assertEquals(1, item.elementsWithAndroidId("@+id/iv_menu_more"))
        assertEquals(1, menu.elementsWithAndroidId("@+id/menu_import_local"))
        assertEquals(1, menu.elementsWithAndroidId("@+id/menu_export_all"))
    }

    private fun parseXml(pathInApp: String): Element =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(projectFile(pathInApp)).documentElement

    private fun Element.elementsWithAndroidId(id: String): Int {
        val elements = getElementsByTagName("*")
        return (0 until elements.length)
            .map { elements.item(it) as Element }
            .count { it.getAttributeNS(ANDROID_NAMESPACE, "id") == id }
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Missing project file: $pathInApp")

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
