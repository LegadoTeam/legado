package io.legado.app.ui.book.source.edit

import io.legado.app.ui.code.shouldShowDebugSourceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JsSourceDirectDebugTest {

    @Test
    fun `optional debug action requires writable editor and explicit request`() {
        assertFalse(shouldShowDebugSourceAction(writable = false, requested = false))
        assertFalse(shouldShowDebugSourceAction(writable = false, requested = true))
        assertFalse(shouldShowDebugSourceAction(writable = true, requested = false))
        assertTrue(shouldShowDebugSourceAction(writable = true, requested = true))
    }

    @Test
    fun `editor result selects the correct save flow`() {
        assertEquals(JsSourceEditStage.SAVING, stageForEditorResult(debugRequested = false))
        assertEquals(
            JsSourceEditStage.SAVING_FOR_DEBUG,
            stageForEditorResult(debugRequested = true),
        )
    }

    @Test
    fun `saving states resume their interrupted operation after recreation`() {
        assertEquals(
            JsSourceEditRestoreAction.SAVE_AND_FINISH,
            JsSourceEditStage.SAVING.restoreAction(),
        )
        assertEquals(
            JsSourceEditRestoreAction.SAVE_FOR_DEBUG,
            JsSourceEditStage.SAVING_FOR_DEBUG.restoreAction(),
        )
    }

    @Test
    fun `open child activities wait for restored activity result`() {
        assertEquals(
            JsSourceEditRestoreAction.AWAIT_RESULT,
            JsSourceEditStage.EDITOR_OPEN.restoreAction(),
        )
        assertEquals(
            JsSourceEditRestoreAction.AWAIT_RESULT,
            JsSourceEditStage.DEBUG_OPEN.restoreAction(),
        )
    }

    @Test
    fun `successful debug save waits for foreground before launch`() {
        assertEquals(
            JsSourceEditStage.DEBUG_READY,
            JsSourceEditStage.SAVING_FOR_DEBUG.afterSuccessfulSave(),
        )
        assertEquals(
            JsSourceEditRestoreAction.LAUNCH_DEBUG,
            JsSourceEditStage.DEBUG_READY.restoreAction(),
        )
        assertEquals(
            JsSourceEditStage.READY,
            JsSourceEditStage.SAVING.afterSuccessfulSave(),
        )
    }

    @Test
    fun `debug result returns the flow to editing readiness`() {
        assertEquals(
            JsSourceEditStage.READY,
            JsSourceEditStage.DEBUG_OPEN.afterDebugResult(),
        )
    }

    @Test
    fun `source editor wires hidden optional action and persisted flow state`() {
        val codeEditorMenu = projectFile("app/src/main/res/menu/code_edit_activity.xml").readText()
        val sourceEditor = projectFile(
            "app/src/main/java/io/legado/app/ui/book/source/edit/JsSourceEditActivity.kt"
        ).readText()

        assertTrue(codeEditorMenu.contains("android:id=\"@+id/menu_debug_source\""))
        assertTrue(codeEditorMenu.contains("android:visible=\"false\""))
        assertTrue(sourceEditor.contains("putExtra(CodeEditActivity.EXTRA_SHOW_DEBUG_SOURCE, true)"))
        assertTrue(sourceEditor.contains("outState.putString(STATE_STAGE, stage.name)"))
        assertTrue(sourceEditor.contains("catch (error: CancellationException)"))
        assertTrue(sourceEditor.contains("withStateAtLeast(Lifecycle.State.RESUMED)"))
    }

    private fun projectFile(path: String): File {
        val userDirectory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val repositoryRoot = generateSequence(userDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
        requireNotNull(repositoryRoot) { "Repository root not found from $userDirectory" }
        return File(repositoryRoot, path).also {
            require(it.isFile) { "Project file not found: $it" }
        }
    }
}
