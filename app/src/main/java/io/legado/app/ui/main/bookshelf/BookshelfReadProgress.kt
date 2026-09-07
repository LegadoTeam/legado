package io.legado.app.ui.main.bookshelf

import android.graphics.Color
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.legado.app.data.entities.Book
import io.legado.app.help.book.readProgress
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.BookshelfReadProgressMode
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.visible
import kotlin.math.roundToInt

fun LinearProgressIndicator.updateBookshelfReadProgress(
    book: Book,
    percentView: TextView? = null,
) {
    val progressFraction = book.readProgress()
        ?.takeIf { AppConfig.showBookshelfReadProgress }
    if (progressFraction == null) {
        gone()
        percentView?.gone()
        return
    }

    val progressPercent = (progressFraction * 100).roundToInt()
    val accent = context.accentColor
    setIndicatorColor(accent)
    trackColor = ColorUtils.withAlpha(accent, Color.alpha(trackColor) / 255f)
    trackThickness = BookshelfReadProgressMode
        .thicknessDp(AppConfig.bookshelfReadProgressMode)
        .dpToPx()
    progress = progressPercent
    visible()
    percentView?.apply {
        text = "$progressPercent%"
        visible()
    }
}
