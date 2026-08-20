package io.legado.app.ui.book.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadPaddingBehaviorSourceTest {

    @Test
    fun `detail seek bar preserves final callback order`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/widget/DetailSeekBar.kt"
        ).readText().normalizeLines()

        assertTrue(source.contains("if (fromUser) onDragging?.invoke(progress)"))
        assertTrue(source.contains("onTrackingStart?.invoke()"))
        val stopBlock = source.substringAfter("override fun onStopTrackingTouch")
            .substringBefore("\n    }")
        assertTrue(stopBlock.indexOf("onChanged?.invoke") < stopBlock.indexOf("onTrackingStop?.invoke"))
    }

    @Test
    fun `detail seek bar owns its saved progress state`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/widget/DetailSeekBar.kt"
        ).readText().normalizeLines()

        assertTrue(source.contains("dispatchFreezeSelfOnly(container)"))
        assertTrue(source.contains("dispatchThawSelfOnly(container)"))
        assertTrue(source.contains("putInt(STATE_PROGRESS, progress)"))
        assertTrue(source.contains("progress = state.getInt(STATE_PROGRESS)"))
    }

    @Test
    fun `pending body edits stay scoped and are not discarded`() {
        val source = paddingDialogSource()

        assertTrue(source.contains("val region: Region"))
        assertTrue(source.contains("private var pendingBodyEdit: PaddingEdit?"))
        assertTrue(source.contains("private val bodyThrottle = throttle<Unit>(150, leading = false)"))
        assertTrue(source.contains("finishPendingBodyEditIfDifferent(edit)"))
        assertTrue(source.contains("finishPendingBodyEdit()\n        curRegion = region"))
        assertTrue(source.contains("override fun onDestroyView() {\n        finishPendingBodyEdit()"))
        assertTrue(source.contains("override fun onDismiss(dialog: DialogInterface) {\n        finishPendingBodyEdit()"))
    }

    @Test
    fun `tracking blocks region actions and reset uses config defaults`() {
        val source = paddingDialogSource()

        assertTrue(source.contains("setPanelActionsEnabled(false)"))
        assertTrue(source.contains("setPanelActionsEnabled(true)"))
        assertTrue(source.contains("private var activeTrackingCount = 0"))
        assertTrue(source.contains("private val defaultConfig = ReadBookConfig.Config()"))
        assertTrue(source.contains("lockLR && region == curRegion"))
        assertTrue(source.contains("ReadBookConfig.save()"))
        assertTrue(source.contains("bottomDialog++"))
        assertTrue(source.contains("bottomDialog--"))
    }

    @Test
    fun `padding panel is centered without covering the page footer`() {
        val source = paddingDialogSource()

        assertTrue(source.contains("gravity = Gravity.CENTER"))
        assertFalse(source.contains("gravity = Gravity.BOTTOM"))
        assertTrue(source.contains("setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)"))
        assertTrue(source.contains("cornerRadius = radius"))
    }

    private fun paddingDialogSource(): String = projectFile(
        "src/main/java/io/legado/app/ui/book/read/config/PaddingConfigDialog.kt"
    ).readText().normalizeLines()

    private fun String.normalizeLines(): String = replace("\r\n", "\n")

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
