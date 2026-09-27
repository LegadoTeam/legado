package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewColumnGeometryTest {

    @Test
    fun `custom icons stay centered when they overflow a text line`() {
        assertEquals(10f, ReviewColumnGeometry.centeredTop(40f, 20f), 0f)
        assertEquals(-10f, ReviewColumnGeometry.centeredTop(40f, 60f), 0f)
    }

    @Test
    fun `right aligned titles reserve only icon overflow`() {
        assertEquals(31f, ReviewColumnGeometry.trailingInset(60f, 30f, 1f), 0f)
        assertEquals(0f, ReviewColumnGeometry.trailingInset(20f, 30f, 1f), 0f)
    }

    @Test
    fun `title shift follows review visibility and width changes`() {
        assertEquals(-30f, ReviewColumnGeometry.trailingShift(30f, false, 30f, true), 0f)
        assertEquals(-20f, ReviewColumnGeometry.trailingShift(30f, true, 50f, true), 0f)
        assertEquals(50f, ReviewColumnGeometry.trailingShift(50f, true, 50f, false), 0f)
    }

    @Test
    fun `short lines keep the review icon after text`() {
        assertEquals(
            700f,
            ReviewColumnGeometry.start(700f, 80f, 1000, false, false, 1f),
            0f,
        )
    }

    @Test
    fun `full lines use the single page margin`() {
        assertEquals(
            919f,
            ReviewColumnGeometry.start(950f, 80f, 1000, false, false, 1f),
            0f,
        )
    }

    @Test
    fun `double page ranges stop at their physical page edge`() {
        assertEquals(
            419f,
            ReviewColumnGeometry.start(490f, 80f, 1001, true, true, 1f),
            0f,
        )
        assertEquals(
            920f,
            ReviewColumnGeometry.start(990f, 80f, 1001, true, false, 1f),
            0f,
        )
    }
}
