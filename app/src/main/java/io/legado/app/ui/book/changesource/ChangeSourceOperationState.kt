package io.legado.app.ui.book.changesource

internal class ChangeSourceOperationState {

    private var generation = 0L
    private val preparing = mutableSetOf<Long>()
    private var activeTask: Long? = null
    private var pendingMeasurementRefresh = false

    @Synchronized
    fun reserveOperation(): Long {
        val operation = ++generation
        preparing.add(operation)
        return operation
    }

    @Synchronized
    fun reserveMeasurementRefresh(
        enabled: Boolean,
        hasResults: Boolean,
    ): Long? {
        return when {
            !enabled -> {
                pendingMeasurementRefresh = false
                null
            }

            preparing.isNotEmpty() || activeTask != null -> {
                pendingMeasurementRefresh = true
                null
            }

            !hasResults -> {
                pendingMeasurementRefresh = false
                null
            }

            else -> {
                pendingMeasurementRefresh = false
                reserveOperation()
            }
        }
    }

    @Synchronized
    fun runIfCurrent(operation: Long, block: () -> Unit): Boolean {
        if (operation != generation) return false
        block()
        return true
    }

    @Synchronized
    fun startTaskIfCurrent(operation: Long, block: () -> Unit): Boolean {
        if (operation != generation) return false
        activeTask = operation
        try {
            block()
        } catch (e: Throwable) {
            activeTask = null
            throw e
        }
        return true
    }

    @Synchronized
    fun finishPreparation(operation: Long): Long? {
        if (!preparing.remove(operation)) return null
        return takePendingRefresh()
    }

    @Synchronized
    fun finishTask(operation: Long): Long? {
        if (activeTask != operation) return null
        activeTask = null
        return takePendingRefresh()
    }

    @Synchronized
    fun cancel(block: () -> Unit) {
        generation++
        preparing.clear()
        activeTask = null
        pendingMeasurementRefresh = false
        block()
    }

    @Synchronized
    fun isRunning(): Boolean = preparing.isNotEmpty() || activeTask != null

    @Synchronized
    fun hasPendingMeasurementRefresh(): Boolean = pendingMeasurementRefresh

    private fun takePendingRefresh(): Long? {
        if (preparing.isNotEmpty() || activeTask != null || !pendingMeasurementRefresh) {
            return null
        }
        pendingMeasurementRefresh = false
        return reserveOperation()
    }
}
