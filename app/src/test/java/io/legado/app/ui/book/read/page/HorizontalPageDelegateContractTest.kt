package io.legado.app.ui.book.read.page

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HorizontalPageDelegateContractTest {

    @Test
    fun `horizontal gestures use focal point and can reverse direction`() {
        val source = File(
            "src/main/java/io/legado/app/ui/book/read/page/delegate/HorizontalPageDelegate.kt"
        ).readText()

        assertTrue(source.contains("val focusX = sumX / div"))
        assertTrue(source.contains("val focusY = sumY / div"))
        assertTrue(source.contains("if (focusX - startX > 0)"))
        assertTrue(source.contains("readView.setStartPoint(focusX, focusY, false)"))
        assertTrue(source.contains("mDirection == PageDirection.NEXT && delta > 0"))
        assertTrue(source.contains("mDirection == PageDirection.PREV && delta < 0"))
        assertTrue(source.contains("readView.setTouchPoint(focusX, focusY)"))
        assertFalse(source.contains("if (sumX - startX > 0)"))
        assertFalse(source.contains("readView.setTouchPoint(sumX, sumY)"))
    }
}
