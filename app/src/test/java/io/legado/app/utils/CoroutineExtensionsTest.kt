package io.legado.app.utils

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CoroutineExtensionsTest {

    @Test
    fun `successful block returns its value`() {
        val result = runCatchingCancellable { "success" }

        assertEquals("success", result.getOrThrow())
    }

    @Test
    fun `ordinary exception is captured as failure`() {
        val exception = IllegalStateException("failed")

        val result = runCatchingCancellable<Unit> {
            throw exception
        }

        assertSame(exception, result.exceptionOrNull())
    }

    @Test
    fun `cancellation exception is rethrown`() {
        val exception = CancellationException("cancelled")

        val thrown = assertThrows(CancellationException::class.java) {
            runCatchingCancellable<Unit> {
                throw exception
            }
        }

        assertSame(exception, thrown)
    }
}
