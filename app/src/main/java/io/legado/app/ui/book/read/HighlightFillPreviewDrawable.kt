package io.legado.app.ui.book.read

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import io.legado.app.help.HighlightGeometry
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.book.read.page.HighlightDraw
import io.legado.app.utils.dpToPx

class HighlightFillPreviewDrawable(
    private val style: HighlightStyle,
    private val textSize: Float,
    private val inkBounds: Rect? = null,
    private val textStart: Float = 0f
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
        val radius = (band.bottom - band.top) / 2f * style.resolvedPillPaddingScale
        var left = bounds.left.toFloat()
        var right = bounds.right.toFloat()
        style.resolvedHorizontalPadding?.let { requested -> inkBounds?.let { ink ->
            val minimum = if (shape == HighlightStyle.FillShape.PILL) HighlightGeometry.pillClearance(radius,
                baseline + ink.top, baseline + ink.bottom, band.top, band.bottom, 1f.dpToPx()) else 0f
            val clearance = maxOf(requested.dpToPx(), minimum,
                if (shape == HighlightStyle.FillShape.PILL) radius - ink.width() / 2f else 0f)
            left = bounds.left + textStart + ink.left - clearance
            right = bounds.left + textStart + ink.right + clearance
        } }
        canvas.save()
        canvas.translate(0f, bounds.top.toFloat())
        HighlightDraw.drawFillRun(
            canvas,
            left,
            right,
            band.top,
            band.bottom,
            style.fill,
            shape,
            radius
        )
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
