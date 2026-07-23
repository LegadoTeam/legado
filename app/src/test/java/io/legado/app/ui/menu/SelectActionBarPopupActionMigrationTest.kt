package io.legado.app.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SelectActionBarPopupActionMigrationTest {

    @Test
    fun `select action bar uses the themed popup and preserves listener behavior`() {
        val source = readProjectFile(SELECT_ACTION_BAR)

        listOf(
            "private var selMenu: Menu? = null",
            "private var menuItemClickListener: PopupMenu.OnMenuItemClickListener? = null",
            "MenuBuilder(context)",
            "SupportMenuInflater(context).inflate(resId, this)",
            "private fun showMoreMenu()",
            "setVertical(true)",
            "setDangerValues(",
            "setDisabledValues(",
            "if (item.isVisible)",
            "it.isEnabled && it.itemId.toString() == action",
            "menuItemClickListener?.onMenuItemClick(menuItem)",
            "R.id.menu_del_selection",
            "R.id.menu_del"
        ).forEach { expected ->
            assertTrue("SelectActionBar should contain $expected", source.contains(expected))
        }

        assertFalse(source.contains("selMenu?.show()"))
        assertFalse(source.contains("PopupMenu(context"))
        assertFalse(source.contains("selMenu?.setOnMenuItemClickListener"))
        assertFalse(source.contains("import android.view.MenuInflater"))
    }

    @Test
    fun `themed popup preserves disabled menu items`() {
        val source = readProjectFile(POPUP_ACTION)

        listOf(
            "private var disabledValues: Set<String> = emptySet()",
            "fun setDisabledValues(values: Set<String>)",
            "holder.itemView.isEnabled = item.value !in disabledValues",
            "context.secondaryDisabledTextColor"
        ).forEach { expected ->
            assertTrue("PopupAction should contain $expected", source.contains(expected))
        }
    }

    @Test
    fun `selection menus retain their item order`() {
        expectedMenuIds.forEach { (path, expected) ->
            assertEquals(path, expected, readMenuItemIds(path))
        }
    }

    @Test
    fun `unused popup reflection helper is removed`() {
        val source = readProjectFile(VIEW_EXTENSIONS)

        assertFalse(source.contains("MenuPopupHelper"))
        assertFalse(source.contains("fun PopupMenu.show"))
        assertFalse(source.contains("java.lang.reflect.Field"))
    }

    @Test
    fun `migration is recorded in the current update log`() {
        val log = readProjectAsset("updateLog.md")
        assertTrue(log.contains("**2026/07/23**"))
        assertTrue(log.contains("批量选择栏的展开菜单改为主题化纵向菜单"))
    }

    private fun readMenuItemIds(path: String): List<String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val document = factory
            .newDocumentBuilder()
            .parse(resolveFile("src/main/res/menu/$path"))
        val items = document.getElementsByTagName("item")
        return (0 until items.length).map { index ->
            (items.item(index) as Element).getAttributeNS(ANDROID_NS, "id")
        }
    }

    private fun readProjectFile(path: String): String =
        resolveFile(path).takeIf(File::isFile)?.readText().orEmpty()

    private fun readProjectAsset(path: String): String =
        resolveFile("src/main/assets/$path").takeIf(File::isFile)?.readText().orEmpty()

    private fun resolveFile(path: String): File =
        sequenceOf(File(path), File("app/$path"))
            .firstOrNull(File::isFile)
            ?: File("app/$path")

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val SELECT_ACTION_BAR = "src/main/java/io/legado/app/ui/widget/SelectActionBar.kt"
        const val POPUP_ACTION = "src/main/java/io/legado/app/ui/widget/PopupAction.kt"
        const val VIEW_EXTENSIONS = "src/main/java/io/legado/app/utils/ViewExtensions.kt"

        val expectedMenuIds = mapOf(
            "import_book_sel.xml" to listOf("@+id/menu_del_selection"),
            "bookshelf_menage_sel.xml" to listOf(
                "@+id/menu_del_selection",
                "@+id/menu_update_enable",
                "@+id/menu_update_disable",
                "@+id/menu_add_to_group",
                "@+id/menu_remove_to_group",
                "@+id/menu_change_source",
                "@+id/menu_clear_cache",
                "@+id/menu_check_selected_interval",
                "@+id/menu_update_toc"
            ),
            "book_source_sel.xml" to listOf(
                "@+id/menu_enable_selection",
                "@+id/menu_disable_selection",
                "@+id/menu_add_group",
                "@+id/menu_remove_group",
                "@+id/menu_enable_explore",
                "@+id/menu_disable_explore",
                "@+id/menu_top_sel",
                "@+id/menu_bottom_sel",
                "@+id/menu_export_selection",
                "@+id/menu_share_source",
                "@+id/menu_check_source",
                "@+id/menu_check_selected_interval"
            ),
            "txt_toc_rule_sel.xml" to listOf(
                "@+id/menu_enable_selection",
                "@+id/menu_disable_selection",
                "@+id/menu_export_selection"
            ),
            "dict_rule_sel.xml" to listOf(
                "@+id/menu_enable_selection",
                "@+id/menu_disable_selection",
                "@+id/menu_export_selection"
            ),
            "replace_rule_sel.xml" to listOf(
                "@+id/menu_enable_selection",
                "@+id/menu_disable_selection",
                "@+id/menu_top_sel",
                "@+id/menu_bottom_sel",
                "@+id/menu_export_selection"
            ),
            "rss_source_sel.xml" to listOf(
                "@+id/menu_enable_selection",
                "@+id/menu_disable_selection",
                "@+id/menu_add_group",
                "@+id/menu_remove_group",
                "@+id/menu_top_sel",
                "@+id/menu_bottom_sel",
                "@+id/menu_export_selection",
                "@+id/menu_share_source",
                "@+id/menu_check_selected_interval"
            )
        )
    }
}
