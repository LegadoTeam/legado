package io.legado.app.ui.widget.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeyboardToolRowsTest {

    @Test
    fun `row count changes wait for recycler layout to finish`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/widget/keyboard/KeyboardToolPop.kt"
        ).readText()
        val observer = source
            .substringAfter("observeEvent<Int>(PreferKey.showBoardLine)")
            .substringBefore("contentView.measure(")
        val update = source
            .substringAfter("private fun updateRowCount(")
            .substringBefore("override fun onGlobalLayout()")
        val guardIndex = update.indexOf("if (recyclerView.isComputingLayout)")
        val postIndex = update.indexOf("recyclerView.post { updateRowCount(rows) }")
        val returnIndex = update.indexOf("return")
        val updateIndex = update.indexOf("spanCount = rows.coerceIn(1, 5)")

        assertTrue(observer.contains("updateRowCount(rows)"))
        assertTrue(guardIndex in 0..<postIndex)
        assertTrue(postIndex < returnIndex)
        assertTrue(returnIndex < updateIndex)
    }

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
}
