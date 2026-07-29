package io.legado.app.ui.book.read

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import io.legado.app.help.HighlightGeometry
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.book.read.page.HighlightDraw
import io.legado.app.utils.dpToPx

class HighlightFillPreviewDrawable(
    private val style: HighlightStyle,
    private val textSize: Float
) : Drawable() {

    override fun draw(canvas: Canvas) {
        if (style.fill == 0 || bounds.isEmpty) return
        val height = bounds.height().toFloat()
        val baseline = height / 2f + HighlightGeometry.GLYPH_BOX_CENTER_RATIO * textSize
        val shape = style.resolvedFillShape
        val band = HighlightGeometry.fillBand(
            baseline,
            textSize,
            height,
            shape,
            1f.dpToPx()
        )
        canvas.save()
        canvas.translate(0f, bounds.top.toFloat())
        HighlightDraw.drawFillRun(
            canvas,
            bounds.left.toFloat(),
            bounds.right.toFloat(),
            band.top,
            band.bottom,
            style.fill,
            shape
        )
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
