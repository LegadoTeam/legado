package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BatteryIconGeometryTest {

    @Test
    fun `fill width follows clamped battery level`() {
        assertEquals(0, BatteryIconGeometry.fillWidth(20, -1))
        assertEquals(0, BatteryIconGeometry.fillWidth(20, 0))
        assertEquals(10, BatteryIconGeometry.fillWidth(20, 50))
        assertEquals(20, BatteryIconGeometry.fillWidth(20, 100))
        assertEquals(20, BatteryIconGeometry.fillWidth(20, 101))
    }

    @Test
    fun `battery spans compare by level`() {
        assertEquals(BatteryLevelSpan(50), BatteryLevelSpan(50))
        assertNotEquals(BatteryLevelSpan(50), BatteryLevelSpan(51))
    }

}
