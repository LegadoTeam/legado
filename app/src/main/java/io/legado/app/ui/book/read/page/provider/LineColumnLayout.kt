package io.legado.app.ui.book.read.page.provider

/**
 * 行内列坐标计算
 * 只做浮点运算,不依赖列对象和绘制,排版结果可被单元测试直接校验
 * 回调全部内联,不产生额外对象
 */
internal object LineColumnLayout {

    /**缩进区域的列,自然排列时仍是正文字符*/
    const val kindIndent = 0

    /**悬挂到缩进内的段首标点列*/
    const val kindHanging = 1

    /**正文列*/
    const val kindText = 2

    /**
     * 段首标点悬挂宽度,不悬挂时返回0
     * @param widths 段落各字符宽度
     * @param indentLength 缩进字符数
     * @param indentCharWidth 两端对齐时单个缩进字符的排布宽度
     */
    fun hangingWidth(widths: FloatArray, indentLength: Int, indentCharWidth: Float): Float {
        var indentWidth = 0f
        for (i in 0 until indentLength) {
            indentWidth += widths[i]
        }
        //两端对齐时缩进按 indentCharWidth 排布,取两者较小值保证悬挂不超出版心
        indentWidth = minOf(indentWidth, indentCharWidth * indentLength)
        val charWidth = widths[indentLength]
        return if (charWidth > 0f && charWidth <= indentWidth + 0.5f) charWidth else 0f
    }

    /**
     * 自然排列,依次累加字宽
     * hangingWidth 大于0时缩进后的首个标点悬挂到缩进内,正文首字与其他段落对齐,
     * 被悬挂标点覆盖的缩进列同步收缩,避免点击区域重叠
     * @param onIndentWidth 正文起点,下划线由此画起,悬挂时为悬挂标点的起点
     * @param emit 列区域,悬挂列的 xStart 小于缩进结束位置
     */
    inline fun natural(
        widths: List<Float>,
        startX: Float,
        hasIndent: Boolean,
        indentLength: Int,
        hangingWidth: Float,
        onIndentWidth: (indentWidth: Float) -> Unit,
        emit: (index: Int, xStart: Float, xEnd: Float, kind: Int) -> Unit
    ) {
        val hanging = hasIndent && hangingWidth > 0f && widths.size > indentLength
        var hangingStart = Float.MAX_VALUE
        if (hanging) {
            var indentEnd = startX
            for (i in 0 until indentLength) {
                indentEnd += widths[i]
            }
            //自然排列按逐字测量的宽度排布,悬挂宽度取本行的列宽而非段落测量值
            hangingStart = indentEnd - widths[indentLength]
        }
        var x = startX
        for (index in widths.indices) {
            if (hanging && index == indentLength) {
                emit(index, hangingStart, x, kindHanging)
                continue
            }
            val x1 = x + widths[index]
            if (hasIndent && index < indentLength) {
                emit(index, minOf(x, hangingStart), minOf(x1, hangingStart), kindIndent)
            } else {
                emit(index, x, x1, kindText)
            }
            x = x1
            if (hasIndent && index == indentLength - 1) {
                //悬挂标点是正文的一部分,下划线要盖住它
                onIndentWidth(if (hanging) hangingStart else x)
            }
        }
    }

    /**
     * 无缩进,两端对齐
     * 剩余宽度分配到空格或字间距
     * @param onJustify 行起始x与单个间隙宽度, isWordSpacing 为true时分配到空格
     */
    inline fun justified(
        words: List<String>,
        widths: List<Float>,
        visibleWidth: Float,
        /**自然排版长度**/
        desiredWidth: Float,
        /**起始x坐标**/
        startX: Float,
        onJustify: (startX: Float, gap: Float, isWordSpacing: Boolean) -> Unit,
        emit: (index: Int, xStart: Float, xEnd: Float, kind: Int) -> Unit
    ) {
        val residualWidth = visibleWidth - desiredWidth
        val spaceSize = words.count { it == " " }
        if (spaceSize > 1) {
            val d = residualWidth / spaceSize
            onJustify(startX, d, true)
            var x = startX
            for (index in words.indices) {
                val cw = widths[index]
                val x1 = if (words[index] == " " && index != words.lastIndex) {
                    x + cw + d
                } else {
                    x + cw
                }
                emit(index, x, x1, kindText)
                x = x1
            }
        } else {
            val gapCount: Int = words.lastIndex
            val d = if (gapCount > 0) residualWidth / gapCount else 0f
            onJustify(startX, d, false)
            var x = startX
            for (index in words.indices) {
                val cw = widths[index]
                val x1 = if (index != words.lastIndex) (x + cw + d) else (x + cw)
                emit(index, x, x1, kindText)
                x = x1
            }
        }
    }

    /**
     * 有缩进,两端对齐
     * 缩进按 indentCharWidth 排布,其后为可选的悬挂标点,余下正文两端对齐
     * @param onIndentWidth 正文起点,下划线由此画起,悬挂时为悬挂标点的起点
     * @return 正文首字在 words 中的下标,小于 words.size 时说明排布了正文
     */
    inline fun justifiedFirst(
        words: List<String>,
        widths: List<Float>,
        visibleWidth: Float,
        /**自然排版长度**/
        desiredWidth: Float,
        indentLength: Int,
        indentCharWidth: Float,
        hangingWidth: Float,
        onIndentWidth: (indentWidth: Float) -> Unit,
        onJustify: (startX: Float, gap: Float, isWordSpacing: Boolean) -> Unit,
        emit: (index: Int, xStart: Float, xEnd: Float, kind: Int) -> Unit
    ): Int {
        var indentEnd = 0f
        for (i in 0 until indentLength) {
            indentEnd += indentCharWidth
        }
        val hanging = hangingWidth > 0f && words.size > indentLength
        val hangingStart = if (hanging) indentEnd - hangingWidth else Float.MAX_VALUE
        var x = 0f
        for (index in 0 until indentLength) {
            val x1 = x + indentCharWidth
            emit(index, minOf(x, hangingStart), minOf(x1, hangingStart), kindIndent)
            x = x1
        }
        //悬挂标点是正文的一部分,下划线要盖住它
        onIndentWidth(if (hanging) hangingStart else x)
        var wordStart = indentLength
        if (hanging) {
            //段首标点悬挂到缩进内,正文首字与其他段落对齐
            emit(wordStart, hangingStart, x, kindHanging)
            wordStart++
        }
        if (words.size > wordStart) {
            justified(
                words.subList(wordStart, words.size),
                widths.subList(wordStart, widths.size),
                visibleWidth,
                //悬挂标点不再占用行内宽度
                desiredWidth - hangingWidth,
                x,
                onJustify
            ) { index, xStart, xEnd, _ ->
                emit(wordStart + index, xStart, xEnd, kindText)
            }
        }
        return wordStart
    }

}
