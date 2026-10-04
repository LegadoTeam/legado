package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SelectCursorTouchTest {

    // 与布局一致 (按 1px = 1dp 计算): 24dp 图标, 向选区外侧扩大 20dp, 向下扩大 16dp,
    // 触摸范围 44x40dp, 同系统文本选择手柄 (Material 手柄图 44dp 宽, text_handle_min_size 40dp)
    private val icon = 24
    private val padOuter = 20
    private val padBottom = 16
    private val viewWidth = padOuter + icon
    private val viewHeight = icon + padBottom
    private val anchorY = 300f

    private class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
        fun intersects(other: Area) = left < other.right && other.left < right &&
                top < other.bottom && other.top < bottom
    }

    // 开始光标只有左侧内边距, 结束光标只有右侧内边距
    private fun startArea(anchorX: Float): Area {
        val x = SelectCursorGeometry.startCursorX(anchorX, viewWidth, 0)
        val y = SelectCursorGeometry.cursorY(anchorY, 0)
        return Area(x, y, x + viewWidth, y + viewHeight)
    }

    private fun endArea(anchorX: Float): Area {
        val x = SelectCursorGeometry.endCursorX(anchorX, 0)
        val y = SelectCursorGeometry.cursorY(anchorY, 0)
        return Area(x, y, x + viewWidth, y + viewHeight)
    }

    @Test
    fun `touch area matches the system selection handle size`() {
        for (area in listOf(startArea(100f), endArea(200f))) {
            assertEquals(44f, area.right - area.left, 0f)
            assertEquals(40f, area.bottom - area.top, 0f)
        }
    }

    @Test
    fun `padded cursor icons stay aligned to the selection boundary`() {
        val start = startArea(100f)
        val end = endArea(200f)

        // 开始光标图标 [76, 100] 在触摸范围右侧, 右边缘即选区起点
        assertEquals(76f, start.left + padOuter, 0f)
        assertEquals(100f, start.right, 0f)
        // 结束光标图标 [200, 224] 在触摸范围左侧, 左边缘即选区终点
        assertEquals(200f, end.left, 0f)
        assertEquals(224f, end.left + icon, 0f)
        // 图标顶部贴着行底
        assertEquals(anchorY, start.top, 0f)
        assertEquals(anchorY, end.top, 0f)
    }

    @Test
    fun `touch area extends outward and below but never inward or above`() {
        val start = startArea(100f)
        val end = endArea(200f)

        // 原先只有 24dp 图标本身可以按住, 这些点都会落到页面上
        assertTrue(start.contains(100f - icon - 18, anchorY + 12))
        assertTrue(start.contains(100f - 12, anchorY + icon + 14))
        assertTrue(end.contains(200f + icon + 18, anchorY + 12))
        assertTrue(end.contains(200f + 12, anchorY + icon + 14))
        // 不向选区内侧扩大
        assertFalse(start.contains(101f, anchorY + 12))
        assertFalse(end.contains(199f, anchorY + 12))
        // 不向上扩大, 不遮挡选区所在的行
        assertFalse(start.contains(100f - 12, anchorY - 1))
        assertFalse(end.contains(200f + 12, anchorY - 1))
    }

    @Test
    fun `handles of a one character selection do not overlap`() {
        for (charWidth in listOf(0f, 1f, 12f, 18f, 24f)) {
            assertFalse(startArea(100f).intersects(endArea(100f + charWidth)))
        }
    }

    @Test
    fun `grabbing anywhere in the touch area drags like grabbing the icon center`() {
        // 旧实现: 无内边距, 按在图标中心时开始光标映射到 起点 + (12, -12),
        // 结束光标映射到 终点 + (-12, -12), 即首尾选中字符内
        val start = startArea(100f)
        val end = endArea(200f)
        for (touchX in 0 until viewWidth step 4) {
            for (touchY in 0 until viewHeight step 4) {
                val offsetY = SelectCursorGeometry.touchOffset(touchY.toFloat(), 0, icon)
                val rawY = anchorY + touchY
                val startOffsetX =
                    SelectCursorGeometry.touchOffset(touchX.toFloat(), padOuter, icon)
                val startRawX = start.left + touchX
                val endOffsetX = SelectCursorGeometry.touchOffset(touchX.toFloat(), 0, icon)
                val endRawX = end.left + touchX

                // 刚开始拖动时选区不跳动
                assertEquals(112f, startRawX + startOffsetX + icon, 0.001f)
                assertEquals(188f, endRawX + endOffsetX - icon, 0.001f)
                assertEquals(288f, rawY + offsetY - icon, 0.001f)
                // 之后跟随手指等距移动
                assertEquals(142f, startRawX + 30 + startOffsetX + icon, 0.001f)
                assertEquals(158f, endRawX - 30 + endOffsetX - icon, 0.001f)
                assertEquals(243f, rawY - 45 + offsetY - icon, 0.001f)
            }
        }
    }

    @Test
    fun `placement honors padding on either side`() {
        // 无内边距时与原先一致
        assertEquals(76f, SelectCursorGeometry.startCursorX(100f, icon, 0), 0f)
        assertEquals(200f, SelectCursorGeometry.endCursorX(200f, 0), 0f)
        assertEquals(0f, SelectCursorGeometry.touchOffset(12f, 0, icon), 0f)
        // 内侧有内边距时图标尖端仍对齐选区边界
        assertEquals(64f, SelectCursorGeometry.startCursorX(100f, icon + 24, 12), 0f)
        assertEquals(188f, SelectCursorGeometry.endCursorX(200f, 12), 0f)
        assertEquals(290f, SelectCursorGeometry.cursorY(300f, 10), 0f)
    }

    @Test
    fun `cursor views use padding to enlarge the touch area`() {
        val layout = source("app/src/main/res/layout/activity_book_read.xml")
        fun cursor(id: String) =
            layout.substringAfter("android:id=\"@+id/$id\"").substringBefore("/>")
        val left = cursor("cursor_left")
        val right = cursor("cursor_right")
        assertTrue(left.contains("android:paddingLeft=\"${padOuter}dp\""))
        assertFalse(left.contains("android:paddingRight"))
        assertTrue(right.contains("android:paddingRight=\"${padOuter}dp\""))
        assertFalse(right.contains("android:paddingLeft"))
        for (view in listOf(left, right)) {
            assertTrue(view.contains("android:paddingBottom=\"${padBottom}dp\""))
            assertFalse(view.contains("android:paddingTop"))
            assertFalse(view.contains("android:padding="))
            assertFalse(view.contains("android:paddingStart"))
            assertFalse(view.contains("android:paddingEnd"))
        }
        val cursorIcon = source("app/src/main/res/drawable/ic_cursor_left.xml")
        assertTrue(cursorIcon.contains("android:width=\"${icon}dp\""))
        assertTrue(cursorIcon.contains("android:height=\"${icon}dp\""))

        val activity = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val handleTouch = activity.substringAfter("override fun onTouch(v: View, event: MotionEvent)")
            .substringBefore("override fun upSelectedStart")
        assertTrue(handleTouch.contains("SelectCursorGeometry.touchOffset(event.x"))
        assertTrue(handleTouch.contains("SelectCursorGeometry.touchOffset(event.y"))
        assertTrue(handleTouch.contains("event.rawX + cursorTouchOffsetX"))
        assertFalse(handleTouch.contains("cursorLeft.width"))
        assertFalse(handleTouch.contains("cursorRight.width"))
        val placement = activity.substringAfter("override fun upSelectedStart")
            .substringBefore("override fun onCancelSelect")
        assertTrue(placement.contains("SelectCursorGeometry.startCursorX("))
        assertTrue(placement.contains("SelectCursorGeometry.endCursorX("))
    }

    @Test
    fun `drags that start on an existing selection never reach the page`() {
        val readView = source("app/src/main/java/io/legado/app/ui/book/read/page/ReadView.kt")
        val touch = readView.substringAfter("override fun onTouchEvent")
            .substringBefore("private fun startReplacePreviewGesture")
        val down = touch.substringAfter("MotionEvent.ACTION_DOWN ->")
            .substringBefore("MotionEvent.ACTION_MOVE ->")
        val move = touch.substringAfter("MotionEvent.ACTION_MOVE ->")
            .substringBefore("MotionEvent.ACTION_UP ->")
        val up = touch.substringAfter("MotionEvent.ACTION_UP ->")
            .substringBefore("MotionEvent.ACTION_CANCEL ->")

        // 按下时不再立即取消选区
        assertFalse(down.contains("cancelSelect()"))
        assertTrue(down.contains("selectedOnDown = isTextSelected"))
        // 拖动时先排除按下时已有选区的情况, 再交给页面
        val dispatch = move.substringAfter("when {")
        assertTrue(dispatch.indexOf("selectedOnDown -> Unit") >= 0)
        assertTrue(dispatch.indexOf("selectedOnDown -> Unit") <
                dispatch.indexOf("pageDelegate?.onTouch(event)"))
        // 点击才取消选区, 且不触发点击翻页等动作
        val tap = up.substringAfter("if (!pageDelegate!!.isMoved && !isMove) {")
            .substringBefore("if (isTextSelected)")
        assertTrue(tap.indexOf("if (selectedOnDown)") <
                tap.indexOf("cancelSelect()"))
        assertTrue(tap.indexOf("cancelSelect()") < tap.indexOf("onSingleTapUp()"))

        // 长按重新选择前先取消旧选区, 之后的拖动扩展新选区
        val longPress = readView.substringAfter("private val longPressRunnable = Runnable {")
            .substringBefore("private var replacePreviewGestureState")
        assertTrue(longPress.contains("selectedOnDown = false"))
        assertTrue(longPress.indexOf("cancelSelect()") < longPress.indexOf("onLongPress()"))
    }

    private fun source(relativePath: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, relativePath).readText()
    }
}
