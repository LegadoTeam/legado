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
        assertTrue(clickBlock.contains("it is ReviewDetailDialog && !it.isRemoving"))
        assertTrue(clickBlock.contains("REVIEW_DIALOG_REQUEST_COOLDOWN_MS"))
        assertTrue(clickBlock.contains("dialog.showNow(fragmentManager, reviewDialogTag)"))
        assertTrue(!clickBlock.contains("showDialogFragment(\n"))
    }

    @Test
    fun reviewColumnClickUsesExistingTouchDebounce() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt"
        ).readText()
        val clickBlock = source.substringAfter("fun click(x: Float, y: Float): Boolean")
            .substringBefore("fun longPress(")
        val reviewBlock = clickBlock.substringAfter("is ReviewColumn")
            .substringBefore("is ImageColumn")
        assertTrue(reviewBlock.contains("if (!debounceClick)"))
        assertTrue(reviewBlock.contains("callBack.onReviewClick("))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
