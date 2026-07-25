package io.legado.app.ui.widget.dialog

import android.content.Context
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonPlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.tables.TableTheme

internal object HelpMarkwonTheme {

    fun plugin(context: Context): MarkwonPlugin {
        val accentColor = context.accentColor
        val textColor = ThemeStore.textColorPrimary(context)
        return object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder
                    .headingTextSizeMultipliers(
                        floatArrayOf(1.45f, 1.3f, 1.15f, 1.05f, 1f, 1f)
                    )
                    .headingBreakColor(surfaceColor(context, 0.18f))
                    .codeBlockBackgroundColor(surfaceColor(context, 0.06f))
                    .codeBlockTextColor(textColor)
                    .codeBlockMargin(8.dpToPx())
                    .codeBackgroundColor(surfaceColor(context, 0.1f))
                    .codeTextColor(textColor)
                    .blockQuoteColor(accentColor)
                    .blockQuoteWidth(3.dpToPx())
                    .listItemColor(accentColor)
                    .linkColor(accentColor)
                    .thematicBreakColor(surfaceColor(context, 0.18f))
            }
        }
    }

    fun tableTheme(context: Context): TableTheme {
        return TableTheme.Builder()
            .tableBorderColor(surfaceColor(context, 0.18f))
            .tableBorderWidth(1.dpToPx())
            .tableCellPadding(8.dpToPx())
            .tableHeaderRowBackgroundColor(surfaceColor(context, 0.04f))
            .build()
    }

    private fun surfaceColor(context: Context, foregroundAmount: Float): Int {
        return ColorUtils.blendColors(
            context.backgroundColor,
            ThemeStore.textColorPrimary(context),
            foregroundAmount,
        )
    }
}
