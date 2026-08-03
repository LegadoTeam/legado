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

    /**簇的标点下标,空簇、多码点簇或非标点返回-1*/
    fun indexOfCluster(cluster: String): Int {
        return indexOfCluster(cluster, 0, cluster.length)
    }

    /**多码点簇可能改变字宽或墨迹位置,不能复用裸基字符的测量缓存*/
    fun indexOfCluster(text: String, start: Int, end: Int): Int {
        return if (end - start == 1) indexOf(text[start]) else -1
    }

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

    /**可裁掉的宽度*/
    private val trims = FloatArray(PunctuationCompressRule.size)

    /**裁剪方向*/
    private val sides = IntArray(PunctuationCompressRule.size)

    /**
     * 段落内被挤压的字位置,与字宽数组同下标
     * 挤压与否由这里记录,不能靠比较列宽反推:Android 15 会给段落首尾字加上半个字距的补偿,
     * 字距可为负,补偿量最大可达裁剪量的一半,反推会把没压的判成压了
     */
    private var compressedAt = BooleanArray(0)

    /**
     * 段落排版开始,重置挤压记录并执行与行位置无关的挤压
     * 挤压后的字宽同时供断行与列排布使用,断行能多排下被挤压让出的宽度
     */
    fun beginParagraph(text: String, widths: FloatArray, mode: PunctuationCompressMode) {
        if (compressedAt.size < text.length) {
            compressedAt = BooleanArray(text.length)
        } else {
            compressedAt.fill(false, 0, text.length)
        }
        if (mode.compressAdjacent || mode.compressAll) {
            compressParagraph(text, widths, mode)
        }
    }

    private fun compressParagraph(
        text: String,
        widths: FloatArray,
        mode: PunctuationCompressMode
    ) {
        var prevClass = PunctuationCompressRule.classNone
        var current = nextBase(text, widths, 0)
        while (current >= 0) {
            val next = nextBase(text, widths, current + 1)
            val currentEnd = if (next < 0) text.length else next
            val index = PunctuationCompressRule.indexOfCluster(text, current, currentEnd)
            val charClass = PunctuationCompressRule.classOf(index)
            if (charClass != PunctuationCompressRule.classNone) {
                val nextClass = if (next < 0) {
                    PunctuationCompressRule.classNone
                } else {
                    val afterNext = nextBase(text, widths, next + 1)
                    val nextEnd = if (afterNext < 0) text.length else afterNext
                    PunctuationCompressRule.classOf(
                        PunctuationCompressRule.indexOfCluster(text, next, nextEnd)
                    )
                }
                val hit = mode.compressAll || PunctuationCompressRule.compressAdjacent(
                    charClass, prevClass, nextClass
                )
                if (hit) {
                    compressAt(index, widths, current)
                }
            }
            prevClass = charClass
            current = next
        }
    }

    /**
     * 行尾标点挤压,断行后只压该行最后一个后置标点
     * 行尾之外的标点不动,段落末行不压,避免自然排版的右边界无故缩进
     * @return 是否有标点被挤压
     */
    fun compressLineEnd(
        words: List<String>,
        widths: MutableList<Float>,
        lineStart: Int
    ): Boolean {
        var position = lineStart
        for (i in words.indices) {
            position += words[i].length
        }
        for (i in words.indices.reversed()) {
            val word = words[i]
            position -= word.length
            //行尾的空格不参与,继续往前找最后一个可见字
            if (word.isBlank()) continue
            val index = PunctuationCompressRule.indexOfCluster(word)
            if (PunctuationCompressRule.classOf(index) != PunctuationCompressRule.classClose) {
                return false
            }
            measure(index)
            if (trims[index] <= minTrim) return false
            //段落内已挤压过的不再压
            if (compressedAt[position]) return false
            widths[i] = widths[i] - trims[index]
            compressedAt[position] = true
            return true
        }
        return false
    }

    /**
     * 该行各列的字形绘制偏移,整行都没有挤压时返回 null
     * 偏移按记录的裁剪量算,不受段落首尾字距补偿影响
     */
    fun lineDrawOffsets(words: List<String>, lineStart: Int): FloatArray? {
        var offsets: FloatArray? = null
        var position = lineStart
        for (i in words.indices) {
            val word = words[i]
            if (compressedAt[position]) {
                val index = PunctuationCompressRule.indexOfCluster(word)
                if (index >= 0) {
                    val result = offsets ?: FloatArray(words.size).also { offsets = it }
                    result[i] = PunctuationCompressRule.drawOffset(sides[index], trims[index])
                }
            }
            position += word.length
        }
        return offsets
    }

    private fun compressAt(index: Int, widths: FloatArray, position: Int) {
        measure(index)
        if (trims[index] <= minTrim) return
        //同一个字只压一次
        if (compressedAt[position]) return
        widths[position] = widths[position] - trims[index]
        compressedAt[position] = true
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
