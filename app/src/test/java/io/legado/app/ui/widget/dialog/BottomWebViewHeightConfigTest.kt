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
            resetHeightModeDefaults = false,
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
            resetHeightModeDefaults = false,
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
            resetHeightModeDefaults = true,
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

    @Test
    fun `expanded mode uses the larger height as layout limit`() {
        val spec = resolveBottomSheetHeightSpec(
            screenHeight = 1_000,
            dialogHeight = 400,
            heightPercentage = null,
            first = true,
            expandedHeight = 800,
        )

        assertEquals(800, spec.layoutHeight)
        assertNull(spec.fixedHeight)
        assertEquals(400, spec.collapsedHeight)
        assertEquals(800, spec.expandedHeight)
        assertEquals(800, spec.constrainedHeight)
    }

    @Test
    fun `expanded percentages follow the same precedence as collapsed percentages`() {
        val spec = resolveBottomSheetHeightSpec(
            screenHeight = 1_000,
            dialogHeight = 300,
            heightPercentage = 0.4f,
            first = true,
            expandedHeight = 700,
            expandedHeightPercentage = 0.9f,
        )

        assertEquals(400, spec.collapsedHeight)
        assertEquals(900, spec.expandedHeight)
    }

    @Test
    fun `invalid expanded height falls back to fixed mode`() {
        val spec = resolveBottomSheetHeightSpec(
            screenHeight = 1_000,
            dialogHeight = 480,
            heightPercentage = null,
            first = true,
            expandedHeight = 320,
        )

        assertEquals(BottomSheetHeightSpec(480, 480), spec)
    }

    @Test
    fun `expanded height without a collapsed height is ignored`() {
        val spec = resolveBottomSheetHeightSpec(
            screenHeight = 1_000,
            dialogHeight = null,
            heightPercentage = null,
            first = true,
            expandedHeight = 800,
        )

        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, spec.layoutHeight)
        assertNull(spec.constrainedHeight)
    }

    @Test
    fun `expanded mode supplies collapsed and draggable defaults`() {
        val spec = resolveBottomSheetBehaviorSpec(
            fixedHeight = null,
            resetHeightModeDefaults = false,
            state = null,
            peekHeight = null,
            skipCollapsed = null,
            fitToContents = null,
            draggableOnNestedScroll = null,
            maxHeight = null,
            collapsedHeight = 400,
            expandedHeight = 800,
        )

        assertEquals(BottomSheetBehavior.STATE_COLLAPSED, spec.state)
        assertEquals(400, spec.peekHeight)
        assertEquals(800, spec.maxHeight)
        assertFalse(spec.skipCollapsed == true)
        assertTrue(spec.fitToContents == true)
        assertTrue(spec.draggableOnNestedScroll == true)
    }

    @Test
    fun `explicit behavior fields override expanded mode defaults`() {
        val spec = resolveBottomSheetBehaviorSpec(
            fixedHeight = null,
            resetHeightModeDefaults = false,
            state = BottomSheetBehavior.STATE_EXPANDED,
            peekHeight = 360,
            skipCollapsed = true,
            fitToContents = false,
            draggableOnNestedScroll = false,
            maxHeight = 760,
            collapsedHeight = 400,
            expandedHeight = 800,
        )

        assertEquals(BottomSheetBehavior.STATE_EXPANDED, spec.state)
        assertEquals(360, spec.peekHeight)
        assertEquals(760, spec.maxHeight)
        assertTrue(spec.skipCollapsed == true)
        assertFalse(spec.fitToContents == true)
        assertFalse(spec.draggableOnNestedScroll == true)
    }

    @Test
    fun `sparse expanded update keeps the configured collapsed height`() {
        val previous = BottomSheetHeightConfig(
            dialogHeight = null,
            heightPercentage = 0.4f,
            expandedHeight = null,
            expandedHeightPercentage = null,
        )

        val merged = mergeBottomSheetHeightConfig(
            previous = previous,
            dialogHeight = null,
            heightPercentage = null,
            expandedHeight = 800,
            expandedHeightPercentage = null,
        )

        assertEquals(0.4f, merged.heightPercentage)
        assertEquals(800, merged.expandedHeight)
    }

    @Test
    fun `explicit zero expanded height clears an older percentage mode`() {
        val previous = BottomSheetHeightConfig(
            dialogHeight = 400,
            heightPercentage = null,
            expandedHeight = null,
            expandedHeightPercentage = 0.9f,
        )

        val merged = mergeBottomSheetHeightConfig(
            previous = previous,
            dialogHeight = null,
            heightPercentage = null,
            expandedHeight = 0,
            expandedHeightPercentage = null,
        )

        assertEquals(0, merged.expandedHeight)
        assertNull(merged.expandedHeightPercentage)
        assertEquals(
            BottomSheetHeightSpec(400, 400),
            resolveBottomSheetHeightSpec(
                screenHeight = 1_000,
                dialogHeight = merged.dialogHeight,
                heightPercentage = merged.heightPercentage,
                first = false,
                expandedHeight = merged.expandedHeight,
                expandedHeightPercentage = merged.expandedHeightPercentage,
            ),
        )
    }
}
