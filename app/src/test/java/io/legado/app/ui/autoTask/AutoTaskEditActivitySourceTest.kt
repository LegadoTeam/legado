package io.legado.app.ui.autoTask

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AutoTaskEditActivitySourceTest {

    @Test
    fun `unsaved edits prompt only after a task has been bound`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/autoTask/AutoTaskEditActivity.kt"
        ).readText().replace("\r\n", "\n")
        val bindBlock = source.substringAfter("private fun bind(")
            .substringBefore("private fun save(")
        val saveBlock = source.substringAfter("private fun save(")
            .substringBefore("private fun buildRule(")
        val finishBlock = source.substringAfter("override fun finish()")
            .substringBefore("private fun textOrNull(")

        assertTrue(bindBlock.contains("originTask = buildDraft()"))
        assertTrue(
            bindBlock.indexOf("originTask = buildDraft()") <
                bindBlock.indexOf("applyPendingEditResult()")
        )
        assertTrue(saveBlock.contains("originTask = saved"))
        assertTrue(finishBlock.contains("originTask?.let { it != buildDraft() } == true"))
        assertTrue(finishBlock.contains("setMessage(R.string.exit_no_save)"))
        assertTrue(finishBlock.contains("super.finish()"))
    }

    @Test
    fun `full editor routes supported code fields and keeps result state`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/autoTask/AutoTaskEditActivity.kt"
        ).readText().replace("\r\n", "\n")
        val menu = projectFile("src/main/res/menu/auto_task_edit.xml")
            .readText().replace("\r\n", "\n")

        assertTrue(source.contains("StartActivityContract(CodeEditActivity::class.java)"))
        assertTrue(source.contains("pendingEditViewId"))
        assertTrue(source.contains("applyPendingEditResult()"))
        assertTrue(source.contains("if (originTask == null)"))
        assertTrue(source.contains("pendingEditText = view.text.toString()"))
        assertTrue(source.contains("getStringExtra(\"text\")?.let { pendingEditText = it }"))
        assertTrue(source.contains("putExtra(\"returnUnchangedText\", true)"))
        assertTrue(source.contains("pendingEditCursor.coerceIn(0, text.length)"))
        listOf("script", "header", "js_lib", "login_ui", "login_check_js").forEach { id ->
            assertTrue(source.contains("R.id.et_$id ->"))
        }
        assertTrue(menu.contains("android:id=\"@+id/menu_fullscreen_edit\""))
        assertTrue(menu.contains("android:icon=\"@drawable/ic_code\""))
    }

    @Test
    fun `copy and paste keep task identity and leave pasted values unsaved`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/autoTask/AutoTaskEditActivity.kt"
        ).readText().replace("\r\n", "\n")
        val copyBlock = source.substringAfter("private fun copyRule()")
            .substringBefore("private fun pasteRule()")
        val pasteBlock = source.substringAfter("private fun pasteRule()")
            .substringBefore("private fun save(")
        val menu = projectFile("src/main/res/menu/auto_task_edit.xml").readText()

        assertTrue(copyBlock.contains("val draft = buildDraft()"))
        assertTrue(copyBlock.contains("withContext(Dispatchers.Default)"))
        assertTrue(copyBlock.contains("AutoTask.exportJson(listOf(draft))"))
        assertTrue(pasteBlock.contains("withContext(Dispatchers.Default)"))
        assertTrue(pasteBlock.contains("fromJsonObject<AutoTaskRule>"))
        assertTrue(pasteBlock.contains("fromJsonArray<AutoTaskRule>"))
        assertTrue(pasteBlock.contains("singleOrNull()"))
        assertTrue(pasteBlock.contains("applyRule(pasted)"))
        assertTrue(!pasteBlock.contains("task = pasted"))
        assertTrue(!pasteBlock.contains("originTask = buildDraft()"))
        assertTrue(menu.contains("@+id/menu_copy_rule"))
        assertTrue(menu.contains("@+id/menu_paste_rule"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
