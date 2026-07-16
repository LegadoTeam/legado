package io.legado.app.help

import io.legado.app.exception.NoStackTraceException
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

object SourceLock {

    private const val MAX_WAIT_MS = 300_000L
    private const val MAX_COUNTERS = 4096

    private open class LockEntry {
        val lock = ReentrantLock()
        var users = 0
    }

    private class FlightEntry : LockEntry() {
        @Volatile
        var epoch = 0L
    }

    private val flights = ConcurrentHashMap<String, FlightEntry>()
    private val locks = ConcurrentHashMap<String, LockEntry>()

    fun singleFlight(key: String, waitMs: Long, action: () -> Unit) {
        validateWait(waitMs)
        withEntry(flights, key, ::FlightEntry) { flight ->
            if (flight.lock.isHeldByCurrentThread) return@withEntry
            val entryEpoch = flight.epoch
            if (!flight.lock.tryLock(waitMs, TimeUnit.MILLISECONDS)) {
                throw NoStackTraceException("singleFlight 等待超时: $key (${waitMs}ms)")
            }
            try {
                if (flight.epoch != entryEpoch) return@withEntry
                action()
                flight.epoch++
            } finally {
                flight.lock.unlock()
            }
        }
    }

    fun lock(key: String, waitMs: Long, action: () -> Unit) {
        validateWait(waitMs)
        withEntry(locks, key, ::LockEntry) { entry ->
            if (!entry.lock.tryLock(waitMs, TimeUnit.MILLISECONDS)) {
                throw NoStackTraceException("lock 等待超时: $key (${waitMs}ms)")
            }
            try {
                action()
            } finally {
                entry.lock.unlock()
            }
        }
    }

    private fun <T : LockEntry> withEntry(
        entries: ConcurrentHashMap<String, T>,
        key: String,
        create: () -> T,
        action: (T) -> Unit
    ) {
        val entry = entries.compute(key) { _, current ->
            (current ?: create()).also { it.users++ }
        }!!
        try {
            action(entry)
        } finally {
            entries.computeIfPresent(key) { _, current ->
                if (current !== entry) {
                    current
                } else {
                    current.users--
                    current.takeIf { it.users > 0 }
                }
            }
        }
    }

    private val counterLock = Any()
    private val counters = object : LinkedHashMap<String, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean {
            return size > MAX_COUNTERS
        }
    }

    fun tick(key: String): Int = synchronized(counterLock) {
        val value = counters[key] ?: 0
        counters[key] = if (value == Int.MAX_VALUE) 0 else value + 1
        value
    }

    private fun validateWait(waitMs: Long) {
        if (waitMs !in 0..MAX_WAIT_MS) {
            throw NoStackTraceException("timeoutMs 必须在 0..$MAX_WAIT_MS 之间")
        }
    }
}
