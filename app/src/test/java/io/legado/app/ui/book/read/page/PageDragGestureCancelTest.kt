package io.legado.app.ui.book.read.page

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PageDragGestureCancelTest {

    @Test
    fun `content refresh keeps an in-progress page drag`() {
        val activity = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        listOf("override fun upContent(", "override suspend fun upContentAwait(").forEach { name ->
            val body = activity.substringAfter(name).substringBefore("    override ")
            assertTrue(name, body.contains("binding.readView.cancelTouchGesturesOnContentUpdate()"))
            assertFalse(name, body.contains("binding.readView.cancelTouchGestures()"))
        }

        val readView = source("app/src/main/java/io/legado/app/ui/book/read/page/ReadView.kt")
        assertTrue(
            readView.contains(
                "private val isPageDragging: Boolean get() = " +
                    "pressDown && pageDelegate?.isMoved == true"
            )
        )
        val onContentUpdate = readView.substringAfter("fun cancelTouchGesturesOnContentUpdate()")
            .substringBefore("internal fun cancelNonPdfGestures()")
        assertOrder(onContentUpdate, "if (isPageDragging) return", "cancelTouchGestures()")
    }

    @Test
    fun `hard gesture cancel releases a page drag before dropping the touch`() {
        val readView = source("app/src/main/java/io/legado/app/ui/book/read/page/ReadView.kt")
        val cancel = readView.substringAfter("internal fun cancelNonPdfGestures()")
            .substringBefore("private fun resetPullBookmarkGesture")
        assertOrder(
            cancel,
            "if (isPageDragging)",
            "pageDelegate?.isCancel = true",
            "pageDelegate?.abortAnim()",
            "invalidate()",
            "pressDown = false",
        )

        val activity = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val keyPage = activity.substringAfter("private fun keyPage(")
            .substringBefore("override fun upMenuView()")
        assertOrder(
            keyPage,
            "binding.readView.cancelTouchGestures()",
            "binding.readView.pageDelegate?.keyTurnPage(direction)",
        )
    }

    private fun assertOrder(source: String, vararg expected: String) {
        var position = -1
        expected.forEach { text ->
            val next = source.indexOf(text, position + 1)
            assertTrue("Missing or out of order: $text", next > position)
            position = next
        }
    }

    private fun source(relativePath: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, relativePath).readText().replace("\r\n", "\n")
    }
}
