package io.legado.app.model.localBook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CloseableCacheTest {

    @Test
    fun `matching value is reused`() {
        val cache = CloseableCache<FakeCloseable>()
        val first = cache.getOrCreate({ it.key == "a" }) { FakeCloseable("a") }
        val second = cache.getOrCreate({ it.key == "a" }) { FakeCloseable("a") }

        assertSame(first, second)
        assertFalse(first.closed)
    }

    @Test
    fun `replacing value closes previous instance`() {
        val cache = CloseableCache<FakeCloseable>()
        val first = cache.getOrCreate({ it.key == "a" }) { FakeCloseable("a") }
        val second = cache.getOrCreate({ it.key == "b" }) { FakeCloseable("b") }

        assertNotSame(first, second)
        assertTrue(first.closed)
        assertFalse(second.closed)
    }

    @Test
    fun `clear closes value and removes it from cache`() {
        val cache = CloseableCache<FakeCloseable>()
        val first = cache.getOrCreate({ true }) { FakeCloseable("a") }

        cache.clear()
        val second = cache.getOrCreate({ true }) { FakeCloseable("b") }

        assertTrue(first.closed)
        assertEquals("b", second.key)
    }

    @Test
    fun `clearIf only closes matching value`() {
        val cache = CloseableCache<FakeCloseable>()
        val first = cache.getOrCreate({ true }) { FakeCloseable("a") }

        cache.clearIf { it.key == "b" }
        assertFalse(first.closed)

        cache.clearIf { it === first }
        val second = cache.getOrCreate({ true }) { FakeCloseable("b") }

        assertTrue(first.closed)
        assertEquals("b", second.key)
    }

    private class FakeCloseable(val key: String) : AutoCloseable {
        var closed = false

        override fun close() {
            closed = true
        }
    }
}
