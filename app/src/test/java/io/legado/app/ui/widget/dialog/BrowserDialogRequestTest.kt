package io.legado.app.ui.widget.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDialogRequestTest {

    private val request = BrowserDialogRequest(
        sourceKey = "https://source.example",
        bookType = 0,
        url = "https://source.example/comments",
        html = "<p>Chapter one</p>",
        preloadJs = "initComments(1)",
        config = "{\"height\":500}",
    )

    @Test
    fun identicalRequestMatchesEvenBelowAnotherPage() {
        val openRequests = mutableListOf(request, request.copy(url = "https://other.example"))

        assertTrue(request.copy() in openRequests)
        openRequests.remove(request)
        assertFalse(request.copy() in openRequests)
    }

    @Test
    fun everyInputCanIdentifyADifferentPage() {
        listOf(
            request.copy(sourceKey = "https://other-source.example"),
            request.copy(bookType = 1),
            request.copy(url = "https://source.example/other"),
            request.copy(html = "<p>Chapter two</p>"),
            request.copy(html = null),
            request.copy(preloadJs = "initComments(2)"),
            request.copy(config = "{\"height\":600}"),
        ).forEach { other ->
            assertFalse(other in listOf(request))
        }
    }

    @Test
    fun hashCollisionsDoNotMergeDifferentHtml() {
        val first = request.copy(html = "Aa")
        val second = request.copy(html = "BB")

        assertEquals(first.hashCode(), second.hashCode())
        assertFalse(first == second)
    }
}
