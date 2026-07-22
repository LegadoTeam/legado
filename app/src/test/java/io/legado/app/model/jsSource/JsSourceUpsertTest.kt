package io.legado.app.model.jsSource

import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class JsSourceUpsertTest {

    @Test
    fun `payload validation uses UTF-8 byte size`() {
        assertEquals(
            JsSourceUpsert.PayloadIssue.EMPTY,
            JsSourceUpsert.validatePayload(null),
        )
        assertEquals(
            JsSourceUpsert.PayloadIssue.EMPTY,
            JsSourceUpsert.validatePayload("   "),
        )
        val asciiLimit = "a".repeat(JsSourceUpsert.MAX_SOURCE_BYTES)
        assertNull(JsSourceUpsert.validatePayload(asciiLimit))
        assertEquals(
            JsSourceUpsert.PayloadIssue.TOO_LARGE,
            JsSourceUpsert.validatePayload(asciiLimit + "b"),
        )
        val multibyteOverLimit = "你".repeat(JsSourceUpsert.MAX_SOURCE_BYTES / 3 + 1)
        assertEquals(
            JsSourceUpsert.PayloadIssue.TOO_LARGE,
            JsSourceUpsert.validatePayload(multibyteOverLimit),
        )
    }

    @Test
    fun `changed source preserves user state and stamps script`() {
        val old = BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "old",
            bookSourceGroup = "local",
            customOrder = 9,
            enabled = false,
            enabledExplore = false,
            lastUpdateTime = 100,
            respondTime = 321,
            weight = 7,
        )
        val source = BookSource(
            bookSourceUrl = old.bookSourceUrl,
            bookSourceName = "new",
            mainJs = "var config = { lastUpdateTime: Date.now() };",
        )

        val changed = JsSourceUpsert.prepareForSave(source, old, stamp = 456)

        assertTrue(changed)
        assertEquals(false, source.enabled)
        assertEquals(false, source.enabledExplore)
        assertEquals(9, source.customOrder)
        assertEquals(7, source.weight)
        assertEquals(321L, source.respondTime)
        assertEquals("local", source.bookSourceGroup)
        assertEquals(456, source.lastUpdateTime)
        assertTrue(source.mainJs.orEmpty().contains("lastUpdateTime: 456"))
    }

    @Test
    fun `reposting original script keeps previous update time and stamped script`() {
        val old = BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "same",
            mainJs = "var config = { lastUpdateTime: 100 };",
            lastUpdateTime = 100,
        )
        val source = old.copy(
            mainJs = "var config = { lastUpdateTime: 0 };",
            lastUpdateTime = 0,
        )

        val changed = JsSourceUpsert.prepareForSave(source, old, stamp = 456)

        assertFalse(changed)
        assertEquals(100, source.lastUpdateTime)
        assertEquals(old.mainJs, source.mainJs)
    }

    @Test
    fun `content change still stamps a reposted original script`() {
        val old = BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "old",
            mainJs = "var config = { lastUpdateTime: 100 };",
            lastUpdateTime = 100,
        )
        val source = old.copy(
            bookSourceName = "new",
            mainJs = "var config = { lastUpdateTime: 0 };",
            lastUpdateTime = 0,
        )

        val changed = JsSourceUpsert.prepareForSave(source, old, stamp = 456)

        assertTrue(changed)
        assertEquals(456, source.lastUpdateTime)
        assertEquals("var config = { lastUpdateTime: 456 };", source.mainJs)
    }

    @Test
    fun `script-only fields participate in idempotence checks`() {
        val old = BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "same",
            exploreScreen = "legacy",
            eventListener = false,
            customButton = false,
            mainJs = "var config = { lastUpdateTime: 100 };",
            lastUpdateTime = 100,
        )
        val source = old.copy(
            exploreScreen = "script",
            eventListener = true,
            customButton = true,
            mainJs = "var config = { lastUpdateTime: 0 };",
            lastUpdateTime = 0,
        )

        val changed = JsSourceUpsert.prepareForSave(source, old, stamp = 456)

        assertTrue(changed)
        assertEquals(456, source.lastUpdateTime)
    }

    @Test
    fun `script group overrides local fallback`() {
        val old = BookSource(bookSourceGroup = "local")
        val source = BookSource(bookSourceGroup = "script")

        JsSourceUpsert.preserveUserState(source, old)

        assertEquals("script", source.bookSourceGroup)
    }

    @Test
    fun `renaming does not overwrite an existing target source`() {
        val opened = BookSource(bookSourceUrl = "https://old.example")
        val target = BookSource(bookSourceUrl = "https://new.example")

        assertTrue(
            JsSourceUpsert.hasTargetConflict(
                opened,
                target,
                target.bookSourceUrl,
            )
        )
    }

    @Test(timeout = 5_000)
    fun `infinite script reaches save timeout before database changes`() {
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                JsSourceUpsert.save("while (true) {}", timeoutMillis = 100)
            }
        }
    }

    @Test(timeout = 5_000)
    fun `save lock serializes concurrent writers`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            JsSourceUpsert.withSaveLock {
                firstEntered.complete(Unit)
                releaseFirst.await()
                "first"
            }
        }
        firstEntered.await()
        val second = async(Dispatchers.Default) {
            JsSourceUpsert.withSaveLock {
                secondEntered.complete(Unit)
                "second"
            }
        }

        assertNull(withTimeoutOrNull(200) { secondEntered.await() })
        releaseFirst.complete(Unit)
        withTimeout(1_000) { secondEntered.await() }
        assertEquals("first", first.await())
        assertEquals("second", second.await())
    }
}
