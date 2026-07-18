package io.legado.app.ui.login

import com.script.rhino.RhinoInterruptError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class LoginUiScriptEvaluatorTest {

    @Test
    fun `ordinary script exceptions are reported as failures`() = runBlocking {
        val error = IllegalStateException("broken login UI")
        var reported: Throwable? = null

        val result = evaluateLoginUiScript<String>(
            block = { throw error },
            onFailure = { reported = it },
        )

        assertTrue(result.isFailure)
        assertSame(error, result.exceptionOrNull())
        assertSame(error, reported)
    }

    @Test
    fun `fatal errors are not swallowed`() {
        val error = AssertionError("fatal login UI error")

        val thrown = assertThrows(AssertionError::class.java) {
            runBlocking {
                evaluateLoginUiScript<Unit>(
                    block = { throw error },
                    onFailure = { fail("Fatal errors must not be reported as script exceptions") },
                )
            }
        }

        assertSame(error, thrown)
    }

    @Test
    fun `cancellation exceptions are not swallowed`() {
        val cancellation = CancellationException("cancel login UI")

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                evaluateLoginUiScript<Unit>(
                    block = { throw cancellation },
                    onFailure = { fail("Cancellation must not be reported as a script error") },
                )
            }
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `Rhino interruption errors are not swallowed`() {
        val interruption = RhinoInterruptError(CancellationException("cancel Rhino"))

        val thrown = assertThrows(RhinoInterruptError::class.java) {
            runBlocking {
                evaluateLoginUiScript<Unit>(
                    block = { throw interruption },
                    onFailure = { fail("Rhino interruption must not be reported as a script error") },
                )
            }
        }

        assertSame(interruption, thrown)
    }

    @Test
    fun `cancelled coroutine is rechecked before reporting another error`() {
        var reported = false

        assertThrows(CancellationException::class.java) {
            runBlocking {
                evaluateLoginUiScript<Unit>(
                    block = {
                        currentCoroutineContext().cancel(
                            CancellationException("cancel before script error")
                        )
                        throw IllegalStateException("script stopped after cancellation")
                    },
                    onFailure = { reported = true },
                )
            }
        }

        assertFalse(reported)
    }

    @Test
    fun `failed rerender keeps current rows while successful empty layout replaces them`() {
        val currentRows = listOf("typed user", "typed password")
        val failed = resolveLoginUiRender<List<String>>(
            currentRows,
            Result.failure(IllegalStateException("render failed")),
        )
        val emptySuccess = resolveLoginUiRender<List<String>>(
            currentRows,
            Result.success(emptyList()),
        )

        assertFalse(failed.shouldApply)
        assertSame(currentRows, failed.value)
        assertTrue(emptySuccess.shouldApply)
        assertEquals(emptyList<String>(), emptySuccess.value)
    }

    @Test
    fun `dynamic login layout starts with a hidden loading indicator`() {
        val layout = readProjectFile("src/main/res/layout/dialog_login.xml")
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/login/SourceLoginDialog.kt"
        )
        val loadingStart = layout.indexOf(
            "<io.legado.app.ui.widget.anima.RotateLoading"
        )
        require(loadingStart >= 0) { "Missing login UI loading indicator" }
        val loadingEnd = layout.indexOf("/>", loadingStart)
        require(loadingEnd > loadingStart) { "Unclosed login UI loading indicator" }
        val loadingView = layout.substring(loadingStart, loadingEnd)

        assertTrue(loadingView.contains("android:id=\"@+id/rotate_loading\""))
        assertTrue(loadingView.contains("android:visibility=\"gone\""))
        assertTrue(source.contains("viewLifecycleOwner.lifecycleScope.launch"))
        assertTrue(source.contains("binding.rotateLoading.visible()"))
        assertTrue(source.contains("binding.rotateLoading.gone()"))
        val decision = source.indexOf(
            "val renderDecision = resolveLoginUiRender(rowUis, renderedRowsResult)"
        )
        val failureGuard = source.indexOf(
            "if (!renderDecision.shouldApply) return@launch",
            decision,
        )
        val rowAssignment = source.indexOf("rowUis = renderedRows", failureGuard)
        assertTrue(decision >= 0)
        assertTrue(failureGuard > decision)
        assertTrue(rowAssignment > failureGuard)
    }

    @Test
    fun `toolbar actions remain available when initial dynamic layout fails`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/login/SourceLoginDialog.kt"
        )
        val menuInflation = source.indexOf(
            "binding.toolBar.inflateMenu(R.menu.source_login)"
        )
        val actionInstallation = source.indexOf(
            "installToolbarActions(source)",
            menuInflation,
        )

        assertTrue(menuInflation >= 0)
        assertTrue(actionInstallation > menuInflation)
        assertTrue(source.contains("if (!isRenderingLoginUi && rowUis != null)"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
