package io.legado.app.help

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
import android.view.View
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import org.xml.sax.XMLReader
import splitties.init.appCtx

class TextViewTagHandler(private val onButtonClickListener: OnButtonClickListener? = null) : Html.TagHandler {
    companion object {
        private const val BUTTON_TAG = "button"
        private const val BUTTON_SPLIT = "@onclick:"
        private const val HR_TAG = "hr"
        const val HR_PLACE_CHAR = "—"
        const val HR_PLACE_STR = "———"
    }
    interface OnButtonClickListener {
        fun onButtonClick(name: String, click: String)
    }

    private val accentColor by lazy { ThemeStore.accentColor(appCtx) }
    private val textColor by lazy {
        if (ColorUtils.isColorLight(accentColor)) {
            Color.BLACK
        } else {
            Color.WHITE
        }
    }
    private val buttonTagStack = mutableListOf<Int>()

    override fun handleTag(
        opening: Boolean,
        tag: String?,
        output: Editable?,
        xmlReader: XMLReader?
    ) {
        if (output == null || tag == null) return
        when {
            tag.equals(BUTTON_TAG, ignoreCase = true) ->{
                if (opening) {
                    buttonTagStack.add(output.length)
                } else {
                    if (buttonTagStack.isNotEmpty()) {
                        val start = buttonTagStack.removeAt(buttonTagStack.size - 1)
                        val buttonText = output.substring(start, output.length)
                        val parts = buttonText.split(BUTTON_SPLIT, limit = 2)
                        if (parts.size != 2) {
                            return
                        }
                        val (name, click) = parts
                        output.replace(start, output.length, name)
                        val buttonSpan = RoundedButtonSpan(
                            accentColor = accentColor,
                            textColor = textColor,
                            name = name,
                            click = click,
                            onClickListener = object : RoundedButtonSpan.OnClickListener {
                                override fun onClick(name: String, click: String) {
                                    onButtonClickListener?.onButtonClick(name, click)
                                }
                            }
                        )
                        output.setSpan(
                            buttonSpan,
                            start,
                            output.length,
                            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                        )

                        output.setSpan(
                            buttonSpan.clickableSpan,
                            start,
                            output.length,
                            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                        )

                    }
                }
            }
            tag.equals(HR_TAG, ignoreCase = true) && opening -> {
                if (output.isNotEmpty() && output.last() != '\n') {
                    output.append('\n')
                }
                val start = output.length
                output.append(HR_PLACE_CHAR)
                output.append('\n')
                val hrSpan = HorizontalRuleSpan()
                output.setSpan(
                    hrSpan,
                    start,
                    output.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private class HorizontalRuleSpan(
        private val lineThickness: Int = 1.dpToPx(),
        private val verticalMargin: Int = 8.dpToPx()
    ) : ReplacementSpan() {
        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val lineHeight = lineThickness.toFloat()
            val lineY = (top + bottom) / 2f + lineHeight / 2
            val lineTop = lineY - lineHeight / 2
            paint.style = Paint.Style.FILL
            canvas.drawRect(0f, lineTop, canvas.width.toFloat(), lineY, paint)
        }

        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            if (fm != null) {
                fm.ascent = -verticalMargin
                fm.descent = verticalMargin
            }
            return if (text != null) {
                paint.measureText(text, start, end).toInt()
            } else {
                0
            }
        }
    }

    private class RoundedButtonSpan(
        private val accentColor: Int,
        private val textColor: Int,
        private val horizontalPadding: Int = 8.dpToPx(),
        private val horizontalMargin: Int = 8.dpToPx(),
        private val verticalPadding: Int = 4.dpToPx(),
        private val verticalMargin: Int = 8.dpToPx(),
        private val cornerRadius: Float = 8f.dpToPx(),
        private val name: String,
        private val click: String,
        private val onClickListener: OnClickListener? = null
    ) : ReplacementSpan() {
        interface OnClickListener {
            fun onClick(name: String, click: String)
        }
        private var width = 0
        val clickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                click.takeIf { it.isNotBlank() }?.let { onClickListener?.onClick(name, it) }
            }

            override fun updateDrawState(ds: TextPaint) {
                ds.isUnderlineText = false
            }
        }
        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val text = text ?: return
            val bgTop = (top + verticalMargin - verticalPadding).toFloat()
            val bgLeft = x + horizontalMargin
            val bgRight = bgLeft + width
            val bgBottom = (bottom - verticalMargin + verticalPadding).toFloat()
            val drawable = GradientDrawable().apply {
                setColor(accentColor)
                cornerRadius = this@RoundedButtonSpan.cornerRadius
            }
            drawable.setBounds(bgLeft.toInt(), bgTop.toInt(), bgRight.toInt(), bgBottom.toInt())
            drawable.draw(canvas)
            paint.color = textColor
            paint.style = Paint.Style.FILL
            paint.isFakeBoldText = true
            paint.isUnderlineText = false
            val originalTextSize = paint.textSize
            paint.textSize = originalTextSize * 0.9f
            val textWidth = paint.measureText(text, start, end)
            val textX = bgLeft + (width - textWidth) / 2
            val textY = y.toFloat()
            canvas.drawText(text, start, end, textX, textY, paint)
        }

        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            width = (paint.measureText(text, start, end) + 2 * horizontalPadding).toInt()
            if (fm != null) {
                val fontMetrics = paint.fontMetricsInt
                fm.ascent = fontMetrics.ascent - verticalMargin
                fm.descent = fontMetrics.descent + verticalMargin
            }
            return width + 2 * horizontalMargin
        }
    }
}
