package io.legado.app.ui.book.changesource

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeSourceOperationStateTest {

    @Test
    fun `manual operation invalidates an older measurement refresh`() {
        val state = ChangeSourceOperationState()
        val measurement = state.reserveMeasurementRefresh(true, true)!!
        val manual = state.reserveOperation()

        assertFalse(state.runIfCurrent(measurement) {})
        assertTrue(state.runIfCurrent(manual) {})
    }

    @Test
    fun `pending refresh waits until preparation finishes`() {
        val state = ChangeSourceOperationState()
        val operation = state.reserveOperation()
        state.startTaskIfCurrent(operation) {}

        assertNull(state.reserveMeasurementRefresh(true, true))
        assertNull(state.finishTask(operation))
        assertNotNull(state.finishPreparation(operation))
        assertNull(state.finishTask(operation))
    }

    @Test
    fun `older task completion cannot consume a newer pending refresh`() {
        val state = ChangeSourceOperationState()
        val older = state.reserveOperation()
        state.startTaskIfCurrent(older) {}
        state.finishPreparation(older)
        val newer = state.reserveOperation()

        assertNull(state.reserveMeasurementRefresh(true, true))
        assertTrue(state.startTaskIfCurrent(newer) {})
        assertNull(state.finishPreparation(newer))
        assertNull(state.finishTask(older))
        assertTrue(state.hasPendingMeasurementRefresh())
        assertNotNull(state.finishTask(newer))
    }

    @Test
    fun `pending refresh waits for every reserved preparation`() {
        val state = ChangeSourceOperationState()
        val older = state.reserveOperation()
        val newer = state.reserveOperation()

        assertNull(state.reserveMeasurementRefresh(true, true))
        assertNull(state.finishPreparation(newer))
        assertNotNull(state.finishPreparation(older))
    }

    @Test
    fun `stop invalidates a consumed pending refresh`() {
        val state = ChangeSourceOperationState()
        val operation = state.reserveOperation()
        state.startTaskIfCurrent(operation) {}
        state.finishPreparation(operation)
        state.reserveMeasurementRefresh(true, true)
        val refresh = state.finishTask(operation)!!

        state.cancel {}

        assertFalse(state.runIfCurrent(refresh) {})
    }

    @Test
    fun `stopping invalidates preparation and clears pending refresh`() {
        val state = ChangeSourceOperationState()
        val operation = state.reserveOperation()
        state.reserveMeasurementRefresh(true, true)

        state.cancel {}

        assertFalse(state.runIfCurrent(operation) {})
        assertFalse(state.hasPendingMeasurementRefresh())
        assertFalse(state.isRunning())
    }
}
