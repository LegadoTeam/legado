package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioCacheServiceQueueTest {

    @Test
    fun `old worker stops only after the service is truly idle`() {
        assertNull(audioCacheIdleStopId(7, hasWorker = true, hasQueuedTask = false))
        assertNull(audioCacheIdleStopId(7, hasWorker = false, hasQueuedTask = true))
        assertEquals(7, audioCacheIdleStopId(7, hasWorker = false, hasQueuedTask = false))
    }
}
