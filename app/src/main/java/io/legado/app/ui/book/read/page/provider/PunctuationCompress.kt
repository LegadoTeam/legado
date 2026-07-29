package io.legado.app.ui.book.read.page.provider

import android.graphics.Rect
import android.text.TextPaint
import io.legado.app.constant.PunctuationCompressMode
import kotlin.math.max
import kotlin.math.min

/**
 * 标点挤压规则
 * 只做字符判定与浮点运算,字形空白由调用方测量后传入,挤压结果可被单元测试直接校验
 */
internal object PunctuationCompressRule {

    /**非标点*/
    const val classNone = 0

    /**前置标点,禁止出现在行尾*/
    const val classOpen = 1

    /**后置标点,禁止出现在行首*/
    const val classClose = 2

    /**从右侧裁剪,字形偏左*/
    const val trimRight = 0

    /**从左侧裁剪,字形偏右*/
    const val trimLeft = 1

    /**两侧同时裁剪,字形居中*/
    const val trimBoth = 2

    private const val openChars = "“‘（〔［｛〈《「『【〖〝﹁﹃"
    private const val closeChars = "”’）〕］｝〉》」』】〗〞﹂﹄。，、；：！？．"

    /**挤压表,前一段是前置标点,后一段是后置标点*/
    private const val chars = openChars + closeChars

    val size = chars.length

    fun charAt(index: Int): Char = chars[index]

    /**标点在挤压表中的下标,非标点返回-1*/
    fun indexOf(char: Char): Int = chars.indexOf(char)

    fun classOf(index: Int): Int = when {
        index < 0 -> classNone
        index < openChars.length -> classOpen
        else -> classClose
    }

    fun charClass(char: Char): Int = classOf(indexOf(char))

    /**
     * 相邻标点中当前这个是否挤压
     * 后置接前置时两个都挤压,同类相接时挤压前一个,合计让出一个字宽
     */
    fun compressAdjacent(charClass: Int, prevClass: Int, nextClass: Int): Boolean {
        return when (charClass) {
            classClose -> nextClass != classNone
            classOpen -> prevClass == classClose || nextClass == classOpen
            else -> false
        }
    }

    /**
     * 裁剪方向
     * 一侧空白明显多于另一侧时只裁空的那侧,两侧相当时均分,字形在列内的相对位置由此保持
     */
    fun trimSide(leftSpace: Float, rightSpace: Float): Int = when {
        rightSpace >= leftSpace * 2f -> trimRight
        leftSpace >= rightSpace * 2f -> trimLeft
        else -> trimBoth
    }

    /**
     * 可裁掉的宽度
     * 目标是压到半角,但不超过字形该侧的空白,字形本身不会被压到相邻的字上
     * @param width 字符测量出的排布宽度
     * @param em 一个字宽
     */
    fun trimWidth(width: Float, em: Float, leftSpace: Float, rightSpace: Float): Float {
        //已是半角或本就窄的标点,再压就会挨上相邻的字
        if (width < em * 0.9f) return 0f
        val space = when (trimSide(leftSpace, rightSpace)) {
            trimRight -> rightSpace
            trimLeft -> leftSpace
            else -> 2f * min(leftSpace, rightSpace)
        }
        return min(width / 2f, max(0f, space))
    }

    /**
     * 裁剪后字形的绘制偏移
     * 列的起点不变,字形按裁剪方向内移,裁掉的始终是空白
     */
    fun drawOffset(side: Int, trim: Float): Float = when {
        trim <= 0f -> 0f
        side == trimLeft -> -trim
        side == trimBoth -> -trim / 2f
        else -> 0f
    }
}

/**
 * 按当前字体测量标点字形的空白并执行挤压
 * 测量结果按标点缓存,一个画笔一个实例
 */
internal class PunctuationCompressor(private val paint: TextPaint) {

    private val widthBuffer = FloatArray(1)
    private val inkBounds = Rect()

    /**一个字宽*/
    private val em = measureWidth("我")

    private val measured = BooleanArray(PunctuationCompressRule.size)

    /**标点自身的排布宽度*/
    private val naturals = FloatArray(PunctuationCompressRule.size)

    /**可裁掉的宽度*/
    private val trims = FloatArray(PunctuationCompressRule.size)

    /**裁剪方向*/
    private val sides = IntArray(PunctuationCompressRule.size)

    /**
     * 段落内与行位置无关的挤压,直接改写字宽
     * 挤压后的字宽同时供断行与列排布使用,断行能多排下被挤压让出的宽度
     * @return 是否有标点被挤压
     */
    fun compressParagraph(
        text: String,
        widths: FloatArray,
        mode: PunctuationCompressMode
    ): Boolean {
        if (!mode.compressAdjacent && !mode.compressAll) return false
        var compressed = false
        var prevClass = PunctuationCompressRule.classNone
        var current = nextBase(text, widths, 0)
        while (current >= 0) {
            val next = nextBase(text, widths, current + 1)
            val index = PunctuationCompressRule.indexOf(text[current])
            val charClass = PunctuationCompressRule.classOf(index)
            if (charClass != PunctuationCompressRule.classNone) {
                val nextClass = if (next < 0) {
                    PunctuationCompressRule.classNone
                } else {
                    PunctuationCompressRule.charClass(text[next])
                }
                val hit = mode.compressAll || PunctuationCompressRule.compressAdjacent(
                    charClass, prevClass, nextClass
                )
                if (hit && compressAt(index, widths, current)) {
                    compressed = true
                }
            }
            prevClass = charClass
            current = next
        }
        return compressed
    }

    /**
     * 行尾标点挤压,断行后只压该行最后一个后置标点
     * 行尾之外的标点不动,段落末行不压,避免自然排版的右边界无故缩进
     * @return 是否有标点被挤压
     */
    fun compressLineEnd(words: List<String>, widths: MutableList<Float>): Boolean {
        for (i in words.indices.reversed()) {
            val word = words[i]
            //行尾的空格不参与,继续往前找最后一个可见字
            if (word.isBlank()) continue
            if (word.length != 1) return false
            val index = PunctuationCompressRule.indexOf(word[0])
            if (PunctuationCompressRule.classOf(index) != PunctuationCompressRule.classClose) {
                return false
            }
            measure(index)
            val trim = trims[index]
            if (trim <= minTrim) return false
            //段落内已挤压过的不再压
            if (widths[i] < naturals[index] - minTrim) return false
            widths[i] = widths[i] - trim
            return true
        }
        return false
    }

    /**
     * 该列被挤压时返回标点下标,否则返回-1
     * @param columnWidth 该列的排布宽度,不含两端对齐补出的行内间隙
     */
    fun compressedIndex(char: String, columnWidth: Float): Int {
        if (char.length != 1) return -1
        val index = PunctuationCompressRule.indexOf(char[0])
        if (index < 0) return -1
        measure(index)
        return if (columnWidth < naturals[index] - minTrim) index else -1
    }

    /**
     * 挤压后字形在列内的绘制偏移
     * 按实际压掉的宽度算,与段落挤压还是行尾挤压无关
     */
    fun drawOffsetAt(index: Int, columnWidth: Float): Float {
        return PunctuationCompressRule.drawOffset(sides[index], naturals[index] - columnWidth)
    }

    private fun compressAt(index: Int, widths: FloatArray, position: Int): Boolean {
        measure(index)
        val trim = trims[index]
        if (trim <= minTrim) return false
        //同一个字只压一次
        if (widths[position] < naturals[index] - minTrim) return false
        widths[position] = widths[position] - trim
        return true
    }

    /**下一个有宽度的字,零宽字符并入前一个字,与 measureTextSplit 的分列一致*/
    private fun nextBase(text: String, widths: FloatArray, from: Int): Int {
        for (i in from until text.length) {
            if (widths[i] > 0f) return i
        }
        return -1
    }

    private fun measure(index: Int) {
        if (measured[index]) return
        measured[index] = true
        val text = PunctuationCompressRule.charAt(index).toString()
        val width = measureWidth(text)
        paint.getTextBounds(text, 0, 1, inkBounds)
        val leftSpace = max(0f, inkBounds.left.toFloat())
        val rightSpace = max(0f, width - inkBounds.right)
        naturals[index] = width
        sides[index] = PunctuationCompressRule.trimSide(leftSpace, rightSpace)
        trims[index] = PunctuationCompressRule.trimWidth(width, em, leftSpace, rightSpace)
    }

    /**与排版取字宽的方式一致,不能用 measureText,两者在部分系统上不等*/
    private fun measureWidth(text: String): Float {
        paint.getTextWidths(text, widthBuffer)
        return widthBuffer[0]
    }

    private companion object {
        /**压不到这个宽度就不值得压,也用来判断一个列是否已被挤压*/
        const val minTrim = 0.5f
    }
}
