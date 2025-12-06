package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.text.StaticLayout
import androidx.annotation.Keep
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import androidx.core.graphics.withTranslation
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine

@Keep
data class HtmlColumn(
    override var start: Float,
    override var end: Float,
    val staticLayout: StaticLayout
) : BaseColumn {
    override var textLine: TextLine = emptyTextLine

    override fun draw(view: ContentTextView, canvas: Canvas) {
        canvas.withTranslation(start, 0f) {
            staticLayout.draw(this)
        }
    }

    override fun isTouch(x: Float): Boolean {
        return x > start && x < end
    }
}
