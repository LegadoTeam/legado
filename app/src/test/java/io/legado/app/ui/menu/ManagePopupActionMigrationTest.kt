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

        listOf(
            "applyMd3PopupStyle()",
            "resolveDropDownYOffset(",
            "LinearLayoutManager(context)",
            "textView.minHeight = 48.dpToPx()",
            "textView.minWidth = 160.dpToPx()",
            "R.color.error else R.color.primaryText"
        ).forEach { expected -> assertContains("PopupAction.kt", popup, expected) }

        listOf(
            "setVertical(true)",
            "setDangerValues(dangerValues)",
            "dismiss()",
            "showAsDropDown(anchor, 0, 4.dpToPx())"
        ).forEach { expected -> assertContains("PopupActionMenu.kt", builder, expected) }
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
