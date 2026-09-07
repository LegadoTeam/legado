package io.legado.app.ui.book.read.page

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadPositionVersionTest {

    @Test
    fun `a late automatic reload loses reset permission after user movement`() {
        val version = ReadPositionVersion()
        val requestVersion = version.snapshot()

        assertTrue(version.isCurrent(requestVersion))
        version.markChanged()
        assertFalse(version.isCurrent(requestVersion))
    }
}
