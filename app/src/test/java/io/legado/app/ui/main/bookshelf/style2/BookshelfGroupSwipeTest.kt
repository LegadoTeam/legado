package io.legado.app.ui.main.bookshelf.style2

import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.widget.recycler.horizontalSwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookshelfGroupSwipeTest {

    private val groups = listOf(
        BookGroup(groupId = 1, groupName = "First"),
        BookGroup(groupId = 2, groupName = "Second"),
        BookGroup(groupId = 4, groupName = "Third"),
    )

    @Test
    fun `swiping left selects the next visible group`() {
        assertEquals(4L, adjacentBookshelfGroupId(groups, 2, 1))
    }

    @Test
    fun `swiping right selects the previous visible group`() {
        assertEquals(1L, adjacentBookshelfGroupId(groups, 2, -1))
    }

    @Test
    fun `root missing and edge groups leave the gesture to the parent pager`() {
        assertNull(adjacentBookshelfGroupId(groups, BookGroup.IdRoot, 1))
        assertNull(adjacentBookshelfGroupId(groups, 1, -1))
        assertNull(adjacentBookshelfGroupId(groups, 4, 1))
    }

    @Test
    fun `horizontal swipe direction respects paging slop and vertical movement`() {
        assertEquals(1, horizontalSwipeDirection(100, 100, 70, 105, 20))
        assertEquals(-1, horizontalSwipeDirection(100, 100, 130, 95, 20))
        assertEquals(0, horizontalSwipeDirection(100, 100, 85, 100, 20))
        assertEquals(0, horizontalSwipeDirection(100, 100, 70, 140, 20))
    }

    @Test
    fun `group swipe commits on up and cancel only resets state`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/widget/recycler/RecyclerViewAtPager2.kt",
        ).readText().replace("\r\n", "\n")
        val move = source.substringAfter("MotionEvent.ACTION_MOVE ->")
            .substringBefore("MotionEvent.ACTION_UP ->")
        val up = source.substringAfter("MotionEvent.ACTION_UP ->")
            .substringBefore("MotionEvent.ACTION_CANCEL ->")
        val cancel = source.substringAfter("MotionEvent.ACTION_CANCEL ->")

        assertTrue(move.contains("canHandleHorizontalSwipe?.invoke(direction)"))
        assertFalse(move.contains("onHorizontalSwipe?.invoke"))
        assertTrue(up.contains("direction == capturedSwipeDirection"))
        assertTrue(up.contains("onHorizontalSwipe?.invoke(capturedSwipeDirection)"))
        assertTrue(cancel.contains("capturedSwipeDirection = 0"))
        assertFalse(cancel.contains("onHorizontalSwipe?.invoke"))

        val fragment = projectFile(
            "src/main/java/io/legado/app/ui/main/bookshelf/style2/BookshelfFragment2.kt",
        ).readText().replace("\r\n", "\n")
        assertTrue(fragment.contains("onHorizontalSwipe = ::switchBookGroup"))
        assertTrue(fragment.contains("onHorizontalSwipe = null"))
    }

    @Test
    fun `folder group exposes toolbar back navigation`() {
        val fragment = projectFile(
            "src/main/java/io/legado/app/ui/main/bookshelf/style2/BookshelfFragment2.kt",
        ).readText().replace("\r\n", "\n")

        assertTrue(fragment.contains("setNavigationOnClickListener { back() }"))
        assertTrue(fragment.contains("private fun initBooksData() {\n        upNavigationIcon()"))
        assertTrue(fragment.contains("navigationIcon = if (groupId == BookGroup.IdRoot)"))
        assertTrue(fragment.contains("abc_ic_ab_back_material"))
        assertTrue(fragment.contains("navigationContentDescription = getString(R.string.back)"))
        assertTrue(fragment.contains("transparentBar = binding.titleBar.usesTransparentForeground"))
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
    }
}
