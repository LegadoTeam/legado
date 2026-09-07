package io.legado.app.lib.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarContrastTest {

    @Test
    fun transparentToolbarUsesVisibleContentBackground() {
        assertEquals(
            LIGHT_BACKGROUND,
            toolbarBackgroundColor(true, DARK_PRIMARY, LIGHT_BACKGROUND)
        )
        assertEquals(
            DARK_PRIMARY,
            toolbarBackgroundColor(false, DARK_PRIMARY, LIGHT_BACKGROUND)
        )
    }

    private companion object {
        val DARK_PRIMARY = 0xFF121212.toInt()
        val LIGHT_BACKGROUND = 0xFFFAFAFA.toInt()
    }
}
