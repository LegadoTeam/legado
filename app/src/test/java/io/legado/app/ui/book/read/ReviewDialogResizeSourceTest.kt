package io.legado.app.ui.book.read

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReviewDialogResizeSourceTest {

    @Test
    fun `review dialog exposes an isolated bounded resize handle`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReviewDetailDialog.kt"
        ).readText().normalizeLines()
        val layout = projectFile(
            "src/main/res/layout/dialog_recycler_view.xml"
        ).readText().normalizeLines()

        assertTrue(source.contains("binding.dragHandle.visible()"))
        assertTrue(source.contains("binding.dragHandle.setOnClickListener"))
        assertTrue(source.contains("binding.dragHandle.setOnTouchListener"))
        assertTrue(source.contains("MotionEvent.ACTION_MOVE"))
        assertTrue(source.contains("heightPx.coerceIn(minHeight, maxHeight)"))
        assertTrue(source.contains("setLayout(1f, clampedHeight)"))
        assertTrue(source.contains("if (!hasDraggedHeight) handle.performClick()"))
        assertTrue(source.contains("lastHeightRatio = clampedHeight.toFloat() / windowHeight"))
        assertTrue(layout.contains("@+id/drag_handle"))
        assertTrue(layout.contains("android:visibility=\"gone\""))
        assertTrue(layout.contains("@string/review_resize_handle"))
    }

    private fun String.normalizeLines(): String = replace("\r\n", "\n")

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
