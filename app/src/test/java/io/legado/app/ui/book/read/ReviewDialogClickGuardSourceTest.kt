package io.legado.app.ui.book.read

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReviewDialogClickGuardSourceTest {

    @Test
    fun reviewClickUsesOneSynchronousTaggedDialog() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText()
        val clickBlock = activity.substringAfter("override fun onReviewClick(")
            .substringBefore("private fun loadReviewSummaryIfNeeded(")

        assertTrue(clickBlock.contains("fragmentManager.isStateSaved"))
        assertTrue(clickBlock.contains("fragmentManager.findFragmentByTag(reviewDialogTag)"))
        assertTrue(clickBlock.contains("dialog.showNow(fragmentManager, reviewDialogTag)"))
        assertTrue(!clickBlock.contains("showDialogFragment(\n"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
