package io.legado.app.help

import io.legado.app.help.HighlightStyle.Deco
import io.legado.app.help.HighlightStyle.Kind
import io.legado.app.help.HighlightStyle.Underline

object HighlightStyles {
    val presets: List<HighlightStyle> = listOf(
        HighlightStyle(fill = 0x80FFF176.toInt()),
        HighlightStyle(
            fill = 0x804FC3F7.toInt(),
            fillShape = HighlightStyle.FillShape.ROUNDED
        ),
        HighlightStyle(
            fill = 0x8069F0AE.toInt(),
            fillShape = HighlightStyle.FillShape.HALF
        ),
        HighlightStyle(underline = Underline(Kind.WAVY, 0xFFE53935.toInt())),
        HighlightStyle(
            underline = Underline(Kind.SOLID, 0xFF1E88E5.toInt()),
            bold = true
        ),
        HighlightStyle(strike = Deco(0xFF9E9E9E.toInt())),
        HighlightStyle(emphasis = Deco(0xFFE53935.toInt()))
    )
}
