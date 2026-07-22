package io.legado.app.ui.book.info

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookIntroCollapseTest {

    @Test
    fun `new introductions are expanded by default and remain manually collapsible`() {
        val activity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt"
        )
        val newIntroState = activity
            .substringAfter("if (intro != introContent) {")
            .substringBefore("}")

        assertTrue(newIntroState.contains("introExpanded = true"))
        assertTrue(activity.contains("introExpanded = !introExpanded"))
    }

    @Test
    fun `short and exact four line introductions stay expanded`() {
        assertFalse(hasOverflow(lineCount = 3, lastLineEnd = 30, textLength = 30))
        assertFalse(hasOverflow(lineCount = 4, lastLineEnd = 40, textLength = 40))
    }

    @Test
    fun `ellipsis and hidden text expose the toggle`() {
        assertTrue(
            hasOverflow(
                lineCount = 4,
                lastLineEllipsisCount = 2,
                lastLineEnd = 38,
                textLength = 40,
            )
        )
        assertTrue(hasOverflow(lineCount = 4, lastLineEnd = 36, textLength = 40))
    }

    @Test
    fun `expanded content remains collapsible only when it exceeds the limit`() {
        assertTrue(hasOverflow(expanded = true, lineCount = 5, lastLineEnd = 50, textLength = 50))
        assertFalse(hasOverflow(expanded = true, lineCount = 4, lastLineEnd = 40, textLength = 40))
    }

    @Test
    fun `large inline content is detected by rendered height`() {
        assertTrue(
            hasOverflow(
                expanded = true,
                lineCount = 1,
                lastLineEnd = 1,
                textLength = 1,
                contentHeight = 240,
                collapsedContentHeight = 80,
            )
        )
    }

    @Test
    fun `height limiting preserves TextView maxLines mode and touch target`() {
        val activity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt"
        )
        val scrollTextView = readProjectFile(
            "src/main/java/io/legado/app/ui/widget/text/ScrollTextView.kt"
        )

        assertTrue(activity.contains("tvIntro.maxLines ="))
        assertTrue(activity.contains("tvIntro.maxMeasuredHeight ="))
        assertFalse(activity.contains("tvIntro.maxHeight ="))
        assertTrue(scrollTextView.contains("var maxMeasuredHeight: Int? = null"))
        assertTrue(scrollTextView.contains("var isMeasuredHeightLimited = false"))
        assertTrue(scrollTextView.contains("setMeasuredDimension(measuredWidth, heightLimit)"))
        assertTrue(scrollTextView.contains("if (internalScrollEnabled) min(y, mOffsetHeight) else 0"))

        listOf(
            "src/main/res/layout/activity_book_info.xml",
            "src/main/res/layout-land/activity_book_info.xml",
        ).forEach { layoutPath ->
            val layout = readProjectFile(layoutPath)
            val toggleStart = layout.indexOf("@+id/tv_intro_toggle")
            val toggleEnd = layout.indexOf("/>", toggleStart)
            val toggle = layout.substring(toggleStart, toggleEnd)

            assertTrue(toggle.contains("android:layout_height=\"48dp\""))
            assertTrue(toggle.contains("android:minWidth=\"48dp\""))
        }
    }

    private fun hasOverflow(
        expanded: Boolean = false,
        lineCount: Int,
        lastLineEllipsisCount: Int = 0,
        lastLineEnd: Int,
        textLength: Int,
        contentHeight: Int = 80,
        collapsedContentHeight: Int = 80,
    ) = BookIntroCollapse.hasOverflow(
        expanded = expanded,
        lineCount = lineCount,
        lastLineEllipsisCount = lastLineEllipsisCount,
        lastLineEnd = lastLineEnd,
        textLength = textLength,
        contentHeight = contentHeight,
        collapsedContentHeight = collapsedContentHeight,
    )

    private fun readProjectFile(pathInApp: String): String {
        val candidates = listOf(File(pathInApp), File("app/$pathInApp"))
        return candidates.first { it.isFile }.readText()
    }
}
