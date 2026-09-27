package io.legado.app.ui.menu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManagePopupActionMigrationTest {

    @Test
    fun `shared popup adds vertical danger styling without losing existing behavior`() {
        val popup = readProjectFile("src/main/java/io/legado/app/ui/widget/PopupAction.kt")
        val builder = readProjectFile("src/main/java/io/legado/app/ui/widget/PopupActionMenu.kt")
        val row = readProjectFile("src/main/res/layout/item_popup_action.xml")

        listOf(
            "applyMd3PopupStyle()",
            "resolveDropDownYOffset(",
            "LinearLayoutManager(context)",
            "private var actionItems: List<PopupActionItem> = emptyList()",
            "items.any { it.icon != null }",
            "items.any { it.checkable }",
            "textView.measuredWidth",
            "textView.minHeight = 48.dpToPx()",
            "textView.setPadding(5.dpToPx(), 5.dpToPx(), 5.dpToPx(), 5.dpToPx())",
            "val enabled = isItemEnabled(item)",
            "item.enabled && item.value !in disabledValues",
            "context.secondaryDisabledTextColor",
            "info.isCheckable = item.checkable",
            "AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE",
            "AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE",
            "imageView.visibility = View.INVISIBLE",
            "takeIf(::isItemEnabled)"
        ).forEach { expected -> assertContains("PopupAction.kt", popup, expected) }
        listOf(
            "@+id/iv_icon",
            "@+id/text_view",
            "@+id/iv_check_end",
            "android:duplicateParentState=\"true\""
        ).forEach { expected -> assertContains("item_popup_action.xml", row, expected) }
        assertFalse(row.contains("app:tint="))

        listOf(
            "setVertical(true)",
            "setDangerValues(dangerValues)",
            "dismiss()",
            "showAsDropDown(anchor, 0, 4.dpToPx())"
        ).forEach { expected -> assertContains("PopupActionMenu.kt", builder, expected) }
    }

    @Test
    fun `toolbar overflow uses exact items and keeps native fallbacks`() {
        val bridge = readProjectFile(
            "src/main/java/io/legado/app/utils/ToolbarOverflowMenuExtensions.kt"
        )

        listOf(
            "getTag(R.id.toolbar_overflow_menu_state) as? OverflowMenuState",
            "setTag(R.id.toolbar_overflow_menu_state, newState)",
            "addOnLayoutChangeListener",
            "onPrepareMenu(menu)",
            "onOpenCustomMenu(menu)",
            "actionItems.hasUnsupportedItems()",
            "item.subMenu != null || item.actionView != null",
            "showOverflowMenu()",
            "actionItems.mapIndexed { index, item ->",
            "value = index.toString()",
            "icon = if (state.showIcons)",
            "enabled = item.isEnabled",
            "checkable = item.isCheckable",
            "checked = item.isChecked",
            "action.toIntOrNull()",
            "actionItems::getOrNull",
            "performItemAction(menuItem, 0)",
            "params.isOverflowButton",
            "abc_action_menu_overflow_description"
        ).forEach { expected ->
            assertContains("ToolbarOverflowMenuExtensions.kt", bridge, expected)
        }
        assertFalse(bridge.contains("WeakHashMap"))
        assertFalse(bridge.contains("setOnHierarchyChangeListener"))
        assertFalse(bridge.contains("performIdentifierAction"))
    }

    @Test
    fun `base activity and fragments install the toolbar overflow bridge`() {
        val activity = readProjectFile("src/main/java/io/legado/app/base/BaseActivity.kt")
        val fragment = readProjectFile("src/main/java/io/legado/app/base/BaseFragment.kt")

        listOf(
            "if (view is Toolbar) view.installMd3OverflowMenu()",
            "?: findViewById(R.id.titleBar)",
            "?.installActivityOverflowMenu()",
            "showIcons = showOpenMenuIcon",
            "onPrepareOptionsMenu(toolbarMenu)",
            "onMenuOpened(Window.FEATURE_OPTIONS_PANEL, toolbarMenu)"
        ).forEach { expected -> assertContains("BaseActivity.kt", activity, expected) }
        assertFalse(activity.contains("if (view is Toolbar) view.installActivityOverflowMenu()"))
        assertContains("BaseFragment.kt", fragment, "it.installMd3OverflowMenu()")
    }

    @Test
    fun `dialog overflow menus use the shared vertical bridge`() {
        listOf(
            "src/main/java/io/legado/app/ui/about/AppLogDialog.kt",
            "src/main/java/io/legado/app/ui/about/CrashLogsDialog.kt",
            "src/main/java/io/legado/app/ui/login/SourceLoginDialog.kt"
        ).forEach { path ->
            val source = readProjectFile(path)
            assertContains(path, source, "installMd3OverflowMenu(")
            assertContains(path, source, "showIcons = true")
            assertContains(
                path,
                source,
                "onOpenCustomMenu = { it.applyOpenTint(requireContext()) }"
            )
        }
    }

    @Test
    fun `dialog menu actions provide icons for the vertical menu`() {
        val menus = mapOf(
            "app_log.xml" to listOf("menu_clear", "menu_export"),
            "crash_log.xml" to listOf("menu_clear"),
            "rss_read_record.xml" to listOf("menu_clear"),
            "source_login.xml" to listOf(
                "menu_ok",
                "menu_show_login_header",
                "menu_del_login_header",
                "menu_clear_login_info",
                "menu_log"
            )
        )
        menus.forEach { (file, ids) ->
            val source = readProjectFile("src/main/res/menu/$file")
            ids.forEach { id ->
                val item = source.substringAfter("android:id=\"@+id/$id\"")
                    .substringBefore("/>")
                assertTrue(
                    "$file/$id should declare an icon",
                    item.contains("android:icon=\"@drawable/")
                )
            }
        }
    }

    @Test
    fun `dialog action icons use the shared toolbar tint`() {
        mapOf(
            "src/main/java/io/legado/app/ui/about/AppLogDialog.kt" to
                "toolBar.menu.applyTint(requireContext())",
            "src/main/java/io/legado/app/ui/about/CrashLogsDialog.kt" to
                "binding.toolBar.menu.applyTint(requireContext())",
            "src/main/java/io/legado/app/ui/rss/article/ReadRecordDialog.kt" to
                "toolBar.menu.applyTint(requireContext())",
        ).forEach { (path, expected) ->
            assertContains(path, readProjectFile(path), expected)
        }
    }

    @Test
    fun `five management adapters use the shared vertical menu`() {
        adapterFiles.forEach { path ->
            val source = readProjectFile(path)
            assertFalse("$path should not import PopupMenu", source.contains("import android.widget.PopupMenu"))
            assertFalse("$path should not import AppCompat PopupMenu", source.contains("import androidx.appcompat.widget.PopupMenu"))
            assertContains(path, source, "popupActionMenu(context)")
            assertContains(path, source, "danger(\"delete\")")
        }
    }

    @Test
    fun `management menu actions keep their current callbacks and side effects`() {
        assertActions(
            AUTO_TASK,
            "\"log\" -> callback.showLog(task)",
            "\"moveUp\" -> callback.move(task, -1)",
            "\"moveDown\" -> callback.move(task, 1)",
            "\"delete\" -> callback.delete(task)"
        )
        assertActions(
            RSS_SOURCE,
            "\"top\" -> callBack.toTop(source)",
            "\"bottom\" -> callBack.toBottom(source)",
            "callBack.del(source)",
            "selected.remove(source)"
        )
        assertActions(RULE_SUB, "callBack.delSubscription(source)")
        assertActions(
            REPLACE_RULE,
            "\"top\" -> callBack.toTop(item)",
            "\"bottom\" -> callBack.toBottom(item)",
            "callBack.delete(item)",
            "selected.remove(item)"
        )
        assertActions(
            TXT_TOC_RULE,
            "\"top\" -> callBack.toTop(source)",
            "\"bottom\" -> callBack.toBottom(source)",
            "callBack.del(source)",
            "selected.remove(source)"
        )
    }

    @Test
    fun `management menu labels keep their previous order`() {
        assertOrdered(
            AUTO_TASK,
            "item(context.getString(R.string.auto_task_log), \"log\")",
            "item(context.getString(R.string.auto_task_move_up), \"moveUp\")",
            "item(context.getString(R.string.auto_task_move_down), \"moveDown\")",
            "item(context.getString(R.string.delete), \"delete\")"
        )
        listOf(RSS_SOURCE, REPLACE_RULE, TXT_TOC_RULE).forEach { path ->
            assertOrdered(
                path,
                "item(context.getString(R.string.to_top), \"top\")",
                "item(context.getString(R.string.to_bottom), \"bottom\")",
                "item(context.getString(R.string.delete), \"delete\")"
            )
        }
        assertOrdered(
            RULE_SUB,
            "item(context.getString(R.string.delete), \"delete\")"
        )
    }

    private fun assertActions(path: String, vararg expected: String) {
        val source = readProjectFile(path)
        expected.forEach { assertContains(path, source, it) }
    }

    private fun assertContains(path: String, source: String, expected: String) {
        assertTrue("$path should contain $expected", source.contains(expected))
    }

    private fun assertOrdered(path: String, vararg expected: String) {
        val source = readProjectFile(path)
        var previous = -1
        expected.forEach { snippet ->
            val current = source.indexOf(snippet, previous + 1)
            assertTrue("$path should contain $snippet after the previous item", current > previous)
            previous = current
        }
    }

    private fun readProjectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()

    private companion object {
        const val AUTO_TASK = "src/main/java/io/legado/app/ui/autoTask/AutoTaskAdapter.kt"
        const val RSS_SOURCE = "src/main/java/io/legado/app/ui/rss/source/manage/RssSourceAdapter.kt"
        const val RULE_SUB = "src/main/java/io/legado/app/ui/rss/subscription/RuleSubAdapter.kt"
        const val REPLACE_RULE = "src/main/java/io/legado/app/ui/replace/ReplaceRuleAdapter.kt"
        const val TXT_TOC_RULE = "src/main/java/io/legado/app/ui/book/toc/rule/TxtTocRuleAdapter.kt"
        val adapterFiles = listOf(AUTO_TASK, RSS_SOURCE, RULE_SUB, REPLACE_RULE, TXT_TOC_RULE)
    }
}
