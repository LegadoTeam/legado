package io.legado.app.utils

import android.os.Build
import android.text.TextPaint

val TextPaint.textHeight: Float
    get() = fontMetrics.run { descent - ascent + leading }

/**
 * 获取文本中每个字符的宽度
 *
 * 调用 getTextWidths 获取每个字符宽度
 * Android 15 适配：考虑 letterSpacing 属性对首尾字符宽度的影响，
 * 对第一个和最后一个非零宽度字符增加半个字符间距的补偿
 *
 * @param text 要测量的文本
 * @param widths 存储每个字符宽度的数组，长度与文本长度一致
 */
fun TextPaint.getTextWidthsCompat(text: String, widths: FloatArray) {
    getTextWidths(text, widths)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        val letterSpacing = letterSpacing * textSize
        val letterSpacingHalf = letterSpacing * 0.5f
        for (i in widths.indices) {
            if (widths[i] > 0) {
                widths[i] += letterSpacingHalf
                break
            }
        }
        for (i in text.lastIndex downTo 0) {
            if (widths[i] > 0) {
                widths[i] += letterSpacingHalf
                break
            }
        }
    }
}
