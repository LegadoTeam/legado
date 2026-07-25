package io.legado.app.ui.book.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExplorePaginationStateTest {

    @Test
    fun `skip invalidates old request and selected page advances`() {
        val state = ExplorePaginationState()
        val firstPage = state.startNextPage()

        assertTrue(state.skipTo(10))
        val selectedPage = state.startNextPage()

        assertFalse(state.complete(firstPage))
        assertEquals(10, state.nextPage)
        assertTrue(state.complete(selectedPage))
        assertEquals(11, state.nextPage)
    }

    @Test
    fun `top page result does not advance bottom pagination`() {
        val state = ExplorePaginationState()
        state.skipTo(10)
        assertTrue(state.complete(state.startNextPage()))

        assertTrue(state.complete(state.startPage(9)!!))

        assertEquals(11, state.nextPage)
    }

    @Test
    fun `page picker starts selected page explicitly`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/book/explore/ExploreShowActivity.kt"
        ).readText()
        val skip = source.indexOf("viewModel.skipPage(it)")
        val stopTop = source.indexOf("loadMoreViewTop.stopLoad()", skip)
        val startBottom = source.indexOf("loadMoreView.hasMore()", stopTop)
        val clear = source.indexOf("adapter.clearItems()", startBottom)
        val explore = source.indexOf("viewModel.explore()", clear)

        assertTrue(skip >= 0)
        assertTrue(stopTop > skip)
        assertTrue(startBottom > stopTop)
        assertTrue(clear > startBottom)
        assertTrue(explore > clear)
        assertFalse(source.contains("if (!loadMoreView.hasMore)"))
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Missing project file: $pathInApp")
}
