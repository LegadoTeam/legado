package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class DropDownPositionTest {

    @Test
    fun `menu stays below when it fits`() {
        assertEquals(
            12,
            resolveDropDownYOffset(200, 80, 300, 0, 2000, 12),
        )
    }

    @Test
    fun `menu flips above a bottom anchor`() {
        assertEquals(
            -392,
            resolveDropDownYOffset(1850, 80, 300, 0, 2000, 12),
        )
    }

    @Test
    fun `menu stays below when above has less room`() {
        assertEquals(
            12,
            resolveDropDownYOffset(50, 80, 1900, 0, 2000, 12),
        )
    }

    @Test
    fun `visible frame top is excluded from space above`() {
        assertEquals(
            8,
            resolveDropDownYOffset(140, 60, 500, 100, 900, 8),
        )
    }
}
