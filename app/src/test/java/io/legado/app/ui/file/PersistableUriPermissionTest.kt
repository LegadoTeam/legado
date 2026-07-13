package io.legado.app.ui.file

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PersistableUriPermissionTest {

    @Test
    fun persistsEveryRequestedGrantIndependently() {
        val requested = mutableListOf<Int>()

        val persisted = takePersistableUriPermissions(READ_WRITE) { flag ->
            requested += flag
        }

        assertEquals(listOf(READ, WRITE), requested)
        assertEquals(READ_WRITE, persisted)
    }

    @Test
    fun keepsReadGrantWhenProviderRejectsWrite() {
        val persisted = takePersistableUriPermissions(READ_WRITE) { flag ->
            if (flag == WRITE) {
                throw SecurityException("Write access was not granted")
            }
        }

        assertEquals(READ, persisted)
    }

    @Test
    fun returnsZeroWhenProviderRejectsEveryGrant() {
        val persisted = takePersistableUriPermissions(READ_WRITE) {
            throw SecurityException("Persistable access was not granted")
        }

        assertEquals(0, persisted)
    }

    @Test
    fun ignoresFlagsThatWereNotRequested() {
        val requested = mutableListOf<Int>()

        val persisted = takePersistableUriPermissions(READ) { flag ->
            requested += flag
        }

        assertEquals(listOf(READ), requested)
        assertEquals(READ, persisted)
    }

    @Test
    fun unrelatedFailuresStillEscape() {
        assertThrows(IllegalStateException::class.java) {
            takePersistableUriPermissions(READ) {
                throw IllegalStateException("Unexpected resolver failure")
            }
        }
    }

    companion object {
        private const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
        private const val WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        private const val READ_WRITE = READ or WRITE
    }
}
