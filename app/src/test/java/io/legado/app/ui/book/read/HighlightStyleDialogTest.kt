package io.legado.app.ui.book.read

import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HighlightStyleDialogTest {

    private class Host : HighlightStyleDialog.StyleHost {
        override fun currentHighlightStyle(): HighlightStyle = HighlightStyle()

        override fun onHighlightStyleChanged(style: HighlightStyle) = Unit

        override fun pickHighlightColor(dialogId: Int, initial: Int, withAlpha: Boolean) = Unit
    }

    @Test
    fun resolvesParentHostBeforeActivityHost() {
        val parentHost = Host()

        assertSame(parentHost, HighlightStyleDialog.resolveStyleHost(parentHost, Host()))
    }

    @Test
    fun fallsBackToActivityHost() {
        val activityHost = Host()

        assertSame(activityHost, HighlightStyleDialog.resolveStyleHost(null, activityHost))
    }

    @Test
    fun returnsNullWithoutAHost() {
        assertNull(HighlightStyleDialog.resolveStyleHost(Any(), Any()))
    }

    @Test
    fun emptyOrMissingStyleFallsBackToAVisiblePreset() {
        assertEquals(HighlightStyles.presets.first(), visibleHighlightStyle(null))
        assertEquals(HighlightStyles.presets.first(), visibleHighlightStyle(HighlightStyle()))
    }

    @Test
    fun configuredStyleIsKept() {
        val style = HighlightStyle(textColor = 1)

        assertSame(style, visibleHighlightStyle(style))
    }

    @Test
    fun fillPresetsHaveDistinctSwatches() {
        val colors = HighlightStyles.presets.mapNotNull { style ->
            style.fill.takeIf { it != 0 }
        }

        assertEquals(colors.size, colors.distinct().size)
    }
}
