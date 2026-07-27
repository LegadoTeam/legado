package io.legado.app.ui.book.read.page.provider

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
     * 向下取整,保证首行放宽后不超出版心
     */
    fun layoutWidth(visibleWidth: Int, firstLineExtra: Float): Int {
        return visibleWidth + firstLineExtra.toInt()
    }

    /**
     * StaticLayout 的逐行右缩进
     * 数组不足行数时最后一项重复,故首行为0,其余行缩回版心
     */
    fun rightIndents(visibleWidth: Int, layoutWidth: Int): IntArray {
        return intArrayOf(0, layoutWidth - visibleWidth)
    }

}
