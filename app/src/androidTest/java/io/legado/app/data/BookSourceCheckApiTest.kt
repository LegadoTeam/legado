package io.legado.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.api.controller.BookSourceCheckController
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourceCheckState
import io.legado.app.utils.GSON
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookSourceCheckApiTest {
    private val id = UUID.randomUUID().toString()
    private val sources = (0..1).map { BookSource(
        bookSourceUrl = "https://check-api.invalid/$id/$it", bookSourceName = "Check API $it",
    ) }

    @Before fun setup() = appDb.bookSourceDao.insert(*sources.toTypedArray())
    @After fun cleanup() = appDb.bookSourceDao.delete(*sources.toTypedArray())

    @Test fun savedSourceReadReturnsOnlyRequestedSourcesWithTheirCurrentRevision() {
        val dao = appDb.bookSourceDao
        val source = sources[0].copy(searchUrl = "https://saved.invalid/search")
        dao.update(source)
        val queued = dao.beginCheck(listOf(dao.getBookSourcePart(source.bookSourceUrl)!!)).single()
        assertTrue(dao.completeCheck(queued, true, "", 1))
        val result = BookSourceCheckController.sources(mapOf("urls" to listOf(GSON.toJson(listOf(
            source.bookSourceUrl, source.bookSourceUrl, "https://missing.invalid/$id",
        )))))
        assertTrue(result.isSuccess)
        val payload = result.data as Map<*, *>
        val returned = (payload["sources"] as List<*>).filterIsInstance<BookSource>()
        val states = (payload["states"] as List<*>).filterIsInstance<BookSourceCheckState>()
        assertEquals(listOf(source.bookSourceUrl), returned.map { it.bookSourceUrl })
        assertEquals(source.searchUrl, returned.single().searchUrl)
        assertEquals(source.bookSourceUrl, states.single().bookSourceUrl)
        assertEquals(queued.sourceRevision, states.single().sourceRevision)
        assertEquals("PASSED", states.single().status)
        assertFalse(GSON.toJson(returned).contains("sourceRevision"))
    }

    @Test fun emptySelectionDoesNotReturnEverythingAndInvalidSelectionIsRejected() {
        val empty = BookSourceCheckController.sources(mapOf("urls" to listOf("[]")))
        assertTrue(empty.isSuccess)
        val payload = empty.data as Map<*, *>
        assertTrue((payload["sources"] as List<*>).isEmpty())
        assertTrue((payload["states"] as List<*>).isEmpty())
        listOf("null", "[null]", "{}", "bad json").forEach {
            assertFalse(BookSourceCheckController.sources(mapOf("urls" to listOf(it))).isSuccess)
        }
    }
}
