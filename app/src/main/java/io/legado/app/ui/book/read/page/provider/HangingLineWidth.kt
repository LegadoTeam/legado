package io.legado.app.ui.book.read.page.provider

import kotlin.math.ceil

/**
 * 段首标点悬挂后的断行宽度
 * 悬挂标点排在缩进内不占行宽,断行时首行可以多排 hangingWidth
 */
internal object HangingLineWidth {

    /**
     * ZhLayout 逐字断行时本行的可用宽度
     */
    fun lineCapacity(width: Int, firstLineExtra: Float, line: Int): Float {
        return if (line == 0) width + firstLineExtra else width.toFloat()
    }

    /**
     * StaticLayout 的排版宽度,首行用满,其余行由 rightIndents 缩回版心
     * StaticLayout 只接受整数宽度,向上取整,否则首行会因差不足1px而少排一个字。
     * 多出的不足1px在两端对齐时被间隙吸收,自然排版时至多让该行退回逐列绘制
     */
    fun layoutWidth(visibleWidth: Int, firstLineExtra: Float): Int {
        return visibleWidth + ceil(firstLineExtra).toInt()
    }

    /**
     * StaticLayout 的逐行右缩进
     * 数组不足行数时最后一项重复,故首行为0,其余行缩回版心
     */
    fun rightIndents(visibleWidth: Int, layoutWidth: Int): IntArray {
        return intArrayOf(0, layoutWidth - visibleWidth)
    }

}
