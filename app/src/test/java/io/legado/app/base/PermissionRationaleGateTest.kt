package io.legado.app.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PermissionRationaleGateTest {

    @Test
    fun onlyFirstPermissionCheckShowsRationale() {
        val gate = PermissionRationaleGate()

        assertTrue(gate.acquire())
        assertFalse(gate.acquire())
        assertFalse(gate.acquire())
    }

    @Test
    fun concurrentPermissionChecksOnlyAcquireOnce() {
        val gate = PermissionRationaleGate()
        val executor = Executors.newFixedThreadPool(8)

        try {
            val results = (1..100).map {
                executor.submit<Boolean> { gate.acquire() }
            }.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it })
        } finally {
            executor.shutdownNow()
        }
    }
}
