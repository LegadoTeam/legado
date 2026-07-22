package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DebugCallbackOwnershipTest {

    @Test
    fun existingCallbackCannotBeOverwritten() {
        val ui = callback()
        val mcp = callback()
        assertTrue(Debug.tryAcquireCallback(ui))
        try {
            assertFalse(Debug.tryAcquireCallback(mcp))
            assertSame(ui, Debug.callback)
        } finally {
            Debug.cancelDebug(ui)
        }
    }

    @Test
    fun onlyOwnerCanReleaseOrCancel() {
        val owner = callback()
        val other = callback()
        assertTrue(Debug.tryAcquireCallback(owner))
        try {
            assertFalse(Debug.cancelDebug(other))
            assertSame(owner, Debug.callback)
            assertTrue(Debug.cancelDebug(owner))
            assertTrue(Debug.callback == null)
        } finally {
            Debug.cancelDebug(owner)
        }
    }

    @Test
    fun activeOwnerCannotBeReplaced() {
        val owner = callback()
        val replacement = callback()
        assertTrue(Debug.tryAcquireCallback(owner))
        try {
            assertFalse(Debug.tryAcquireCallback(replacement))
            assertSame(owner, Debug.callback)
        } finally {
            Debug.cancelDebug(owner)
        }
    }

    @Test
    fun sourceCheckingAndDebugCallbackAreMutuallyExclusive() {
        val owner = callback()
        assertTrue(Debug.tryStartChecking())
        try {
            assertFalse(Debug.tryStartChecking())
            assertFalse(Debug.tryAcquireCallback(owner))
        } finally {
            Debug.finishChecking()
        }
        assertTrue(Debug.tryAcquireCallback(owner))
        try {
            assertFalse(Debug.tryStartChecking())
        } finally {
            Debug.cancelDebug(owner)
        }
    }

    @Test
    fun concurrentAcquireHasSingleOwner() {
        val first = callback()
        val second = callback()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val acquired = AtomicInteger()
        val winner = AtomicReference<Debug.Callback>()

        listOf(first, second).forEach { candidate ->
            Thread {
                start.await()
                if (Debug.tryAcquireCallback(candidate)) {
                    acquired.incrementAndGet()
                    winner.set(candidate)
                }
                done.countDown()
            }.start()
        }

        try {
            start.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(1, acquired.get())
            assertSame(winner.get(), Debug.callback)
        } finally {
            winner.get()?.let { Debug.cancelDebug(it) }
        }
    }

    private fun callback() = object : Debug.Callback {
        override fun printLog(state: Int, msg: String) = Unit
    }
}
