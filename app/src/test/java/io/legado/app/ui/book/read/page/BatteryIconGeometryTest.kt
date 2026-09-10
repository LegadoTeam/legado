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
    fun `icon center follows visible digit bounds`() {
        assertEquals(24f, BatteryIconGeometry.centerY(30, -10, -2), 0f)
        assertEquals(30f, BatteryIconGeometry.centerY(30, -12, 12), 0f)
    }

    @Test
    fun `battery spans compare by level`() {
        assertEquals(BatteryLevelSpan(50), BatteryLevelSpan(50))
        assertNotEquals(BatteryLevelSpan(50), BatteryLevelSpan(51))
        assertNotEquals(BatteryLevelSpan(50), BatteryLevelSpan(50, true))
    }

}
