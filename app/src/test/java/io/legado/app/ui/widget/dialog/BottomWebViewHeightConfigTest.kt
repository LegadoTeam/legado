package io.legado.app.ui.widget.dialog

import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomWebViewHeightConfigTest {

    @Test
    fun `positive pixel height enables fixed mode`() {
        val spec = resolveBottomSheetHeightSpec(1_000, 480, null, first = true)

        assertEquals(480, spec.layoutHeight)
        assertEquals(480, spec.fixedHeight)
    }

    @Test
    fun `valid percentage takes precedence and enables fixed mode`() {
        val spec = resolveBottomSheetHeightSpec(1_000, 480, 0.4f, first = true)

        assertEquals(400, spec.layoutHeight)
        assertEquals(400, spec.fixedHeight)
    }

    @Test
    fun `invalid percentage falls back without creating zero height fixed mode`() {
        assertEquals(
            BottomSheetHeightSpec(480, 480),
            resolveBottomSheetHeightSpec(1_000, 480, 0f, first = true),
        )
        assertEquals(
            BottomSheetHeightSpec(ViewGroup.LayoutParams.MATCH_PARENT, null),
            resolveBottomSheetHeightSpec(1_000, null, 1.1f, first = true),
        )
    }

    @Test
    fun `zero and unknown negative pixel heights are ignored`() {
        assertEquals(
            BottomSheetHeightSpec(ViewGroup.LayoutParams.MATCH_PARENT, null),
            resolveBottomSheetHeightSpec(1_000, 0, null, first = true),
        )
        assertEquals(
            BottomSheetHeightSpec(null, null),
            resolveBottomSheetHeightSpec(1_000, -3, null, first = false),
        )
    }

    @Test
    fun `layout constants retain flexible behavior`() {
        val matchParent = resolveBottomSheetHeightSpec(
            1_000,
            ViewGroup.LayoutParams.MATCH_PARENT,
            null,
            first = true,
        )
        val wrapContent = resolveBottomSheetHeightSpec(
            1_000,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            null,
            first = false,
        )

        assertNull(matchParent.fixedHeight)
        assertNull(wrapContent.fixedHeight)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, matchParent.layoutHeight)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, wrapContent.layoutHeight)
    }

    @Test
    fun `fixed mode supplies stable defaults`() {
        val spec = resolveBottomSheetBehaviorSpec(
            fixedHeight = 480,
            resetFixedDefaults = false,
            state = null,
            peekHeight = null,
            skipCollapsed = null,
            fitToContents = null,
            draggableOnNestedScroll = null,
            maxHeight = null,
        )

        assertEquals(BottomSheetBehavior.STATE_EXPANDED, spec.state)
        assertEquals(480, spec.peekHeight)
        assertEquals(480, spec.maxHeight)
        assertTrue(spec.skipCollapsed == true)
        assertTrue(spec.fitToContents == true)
        assertFalse(spec.draggableOnNestedScroll == true)
    }

    @Test
    fun `explicit behavior fields override fixed mode defaults`() {
        val spec = resolveBottomSheetBehaviorSpec(
            fixedHeight = 480,
            resetFixedDefaults = false,
            state = BottomSheetBehavior.STATE_COLLAPSED,
            peekHeight = 320,
            skipCollapsed = false,
            fitToContents = false,
            draggableOnNestedScroll = true,
            maxHeight = 640,
        )

        assertEquals(BottomSheetBehavior.STATE_COLLAPSED, spec.state)
        assertEquals(320, spec.peekHeight)
        assertEquals(640, spec.maxHeight)
        assertFalse(spec.skipCollapsed == true)
        assertFalse(spec.fitToContents == true)
        assertTrue(spec.draggableOnNestedScroll == true)
    }

    @Test
    fun `leaving fixed mode restores flexible constraints without changing state`() {
        val spec = resolveBottomSheetBehaviorSpec(
            fixedHeight = null,
            resetFixedDefaults = true,
            state = null,
            peekHeight = null,
            skipCollapsed = null,
            fitToContents = null,
            draggableOnNestedScroll = null,
            maxHeight = null,
        )

        assertNull(spec.state)
        assertEquals(BottomSheetBehavior.PEEK_HEIGHT_AUTO, spec.peekHeight)
        assertEquals(-1, spec.maxHeight)
        assertFalse(spec.skipCollapsed == true)
        assertTrue(spec.fitToContents == true)
        assertTrue(spec.draggableOnNestedScroll == true)
    }
}
