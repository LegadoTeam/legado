package io.legado.app.ui.widget.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardToolImeInsetsTest {

    @Test
    fun `visible ime uses its inset height`() {
        assertEquals(
            720,
            resolveKeyboardToolHeight(
                imeVisible = true,
                imeInsetBottom = 720,
                screenHeight = 2400,
                visibleFrameBottom = 1000,
            )
        )
    }

    @Test
    fun `hidden ime ignores legacy frame loss`() {
        assertEquals(
            0,
            resolveKeyboardToolHeight(
                imeVisible = false,
                imeInsetBottom = 0,
                screenHeight = 2400,
                visibleFrameBottom = 1200,
            )
        )
    }

    @Test
    fun `visible ime with no bottom inset does not show bottom toolbar`() {
        assertEquals(
            0,
            resolveKeyboardToolHeight(
                imeVisible = true,
                imeInsetBottom = 0,
                screenHeight = 2400,
                visibleFrameBottom = 1200,
            )
        )
    }

    @Test
    fun `missing insets falls back to visible frame loss`() {
        assertEquals(
            600,
            resolveKeyboardToolHeight(
                imeVisible = null,
                imeInsetBottom = 0,
                screenHeight = 2400,
                visibleFrameBottom = 1800,
            )
        )
    }

    @Test
    fun `legacy fallback requires more than one fifth of screen`() {
        assertEquals(
            0,
            resolveKeyboardToolHeight(
                imeVisible = null,
                imeInsetBottom = 0,
                screenHeight = 2400,
                visibleFrameBottom = 1920,
            )
        )
    }

    @Test
    fun `legacy fallback rejects invalid visible frame`() {
        assertEquals(
            0,
            resolveKeyboardToolHeight(
                imeVisible = null,
                imeInsetBottom = 0,
                screenHeight = 2400,
                visibleFrameBottom = 2500,
            )
        )
        assertEquals(
            0,
            resolveKeyboardToolHeight(
                imeVisible = null,
                imeInsetBottom = 0,
                screenHeight = 2400,
                visibleFrameBottom = 0,
            )
        )
    }
}
