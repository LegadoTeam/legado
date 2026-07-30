package io.legado.app.ui.book.source.edit

import io.legado.app.ui.code.scriptSourceIndex
import io.legado.app.ui.code.shouldShowDebugSourceAction
import io.legado.app.ui.code.shouldShowJavaScriptSyntaxAction
import io.legado.app.ui.code.shouldShowLoginSourceAction
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
    fun `optional login action requires writable editor and explicit request`() {
        assertFalse(shouldShowLoginSourceAction(writable = false, requested = false))
        assertFalse(shouldShowLoginSourceAction(writable = false, requested = true))
        assertFalse(shouldShowLoginSourceAction(writable = true, requested = false))
        assertTrue(shouldShowLoginSourceAction(writable = true, requested = true))
    }

    @Test
    fun `syntax action requires the explicit JavaScript editor mode`() {
        assertFalse(
            shouldShowJavaScriptSyntaxAction(useSafeEditor = false, requested = false)
        )
        assertFalse(shouldShowJavaScriptSyntaxAction(useSafeEditor = true, requested = true))
        assertTrue(shouldShowJavaScriptSyntaxAction(useSafeEditor = false, requested = true))
        assertEquals(7, scriptSourceIndex("ok\r\nbad(", lineNumber = 2, columnNumber = 4))
    }

    @Test
    fun `editor result selects the correct save flow`() {
        assertEquals(JsSourceEditStage.SAVING, stageForEditorResult(debugRequested = false))
        assertEquals(
            JsSourceEditStage.SAVING_FOR_DEBUG,
            stageForEditorResult(debugRequested = true),
        )
        assertEquals(
            JsSourceEditStage.SAVING_FOR_LOGIN,
            stageForEditorResult(debugRequested = false, loginRequested = true),
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
        assertEquals(
            JsSourceEditRestoreAction.SAVE_FOR_LOGIN,
            JsSourceEditStage.SAVING_FOR_LOGIN.restoreAction(),
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
        assertEquals(
            JsSourceEditRestoreAction.AWAIT_RESULT,
            JsSourceEditStage.LOGIN_OPEN.restoreAction(),
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
        assertEquals(
            JsSourceEditStage.LOGIN_READY,
            JsSourceEditStage.SAVING_FOR_LOGIN.afterSuccessfulSave(),
        )
        assertEquals(
            JsSourceEditRestoreAction.LAUNCH_LOGIN,
            JsSourceEditStage.LOGIN_READY.restoreAction(),
        )
    }

    @Test
    fun `debug result returns the flow to editing readiness`() {
        assertEquals(
            JsSourceEditStage.READY,
            JsSourceEditStage.DEBUG_OPEN.afterDebugResult(),
        )
        assertEquals(
            JsSourceEditStage.READY,
            JsSourceEditStage.LOGIN_OPEN.afterLoginResult(),
        )
    }

    @Test
    fun `source editor wires hidden optional action and persisted flow state`() {
        val codeEditorMenu = projectFile("app/src/main/res/menu/code_edit_activity.xml").readText()
        val sourceEditor = projectFile(
            "app/src/main/java/io/legado/app/ui/book/source/edit/JsSourceEditActivity.kt"
        ).readText()
        val debugLauncher = sourceEditor.indexOf("private val debugResult")
        val editorLauncher = sourceEditor.indexOf("private val editorResult")
        val loginLauncher = sourceEditor.indexOf("private val loginResult")
        val debugMenuItem = codeEditorMenu
            .substringAfter("android:id=\"@+id/menu_debug_source\"")
            .substringBefore("/>")
        val loginMenuItem = codeEditorMenu
            .substringAfter("android:id=\"@+id/menu_login\"")
            .substringBefore("/>")
        val syntaxMenuItem = codeEditorMenu
            .substringAfter("android:id=\"@+id/menu_check_javascript_syntax\"")
            .substringBefore("/>")

        assertTrue(codeEditorMenu.contains("android:id=\"@+id/menu_debug_source\""))
        assertTrue(codeEditorMenu.contains("android:id=\"@+id/menu_login\""))
        assertTrue(codeEditorMenu.contains("android:id=\"@+id/menu_check_javascript_syntax\""))
        assertTrue(debugMenuItem.contains("android:visible=\"false\""))
        assertTrue(loginMenuItem.contains("android:visible=\"false\""))
        assertTrue(syntaxMenuItem.contains("android:visible=\"false\""))
        assertTrue(debugLauncher >= 0 && debugLauncher < editorLauncher)
        assertTrue(editorLauncher < loginLauncher)
        assertTrue(sourceEditor.contains("putExtra(CodeEditActivity.EXTRA_SHOW_DEBUG_SOURCE, true)"))
        assertTrue(
            sourceEditor.contains(
                "putExtra(CodeEditActivity.EXTRA_CHECK_JAVASCRIPT_SYNTAX, true)"
            )
        )
        assertTrue(sourceEditor.contains("putExtra(CodeEditActivity.EXTRA_SHOW_LOGIN_SOURCE, true)"))
        assertTrue(sourceEditor.contains("StartActivityContract(SourceLoginActivity::class.java)"))
        assertTrue(sourceEditor.contains("if (source.hasLogin())"))
        assertTrue(sourceEditor.contains("toastOnUi(R.string.source_no_login)"))
        assertTrue(sourceEditor.contains("putExtra(\"type\", \"bookSource\")"))
        assertTrue(sourceEditor.contains("putExtra(\"key\", sourceUrl)"))
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
