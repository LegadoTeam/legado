package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewColumnGeometryTest {

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
