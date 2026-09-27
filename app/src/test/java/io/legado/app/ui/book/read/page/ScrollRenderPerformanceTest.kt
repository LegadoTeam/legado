package io.legado.app.ui.book.read.page

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScrollRenderPerformanceTest {

    @Test
    fun `scroll rendering skips work that cannot affect the frame`() {
        val content = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt"
        )
        val page = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/TextPage.kt"
        )
        val line = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/TextLine.kt"
        )
        val columns = listOf(
            readProjectFile(
                "src/main/java/io/legado/app/ui/book/read/page/entities/column/TextColumn.kt"
            ),
            readProjectFile(
                "src/main/java/io/legado/app/ui/book/read/page/entities/column/TextHtmlColumn.kt"
            )
        )
        val scroll = content.substringAfter("fun scroll(")
            .substringBefore("fun submitRenderTask(")
        val drawPage = page.substringAfter("private fun drawPage(")
            .substringBefore("fun render(")
        val drawLine = line.substringAfter("private fun drawTextLine(")
            .substringBefore("private fun fastDrawTextLine(")

        assertTrue(scroll.contains("postInvalidateOnAnimation()"))
        assertTrue(drawPage.contains("canvas.quickReject("))
        assertTrue(
            drawPage.indexOf("canvas.quickReject(") <
                    drawPage.indexOf("line.draw(view, this)")
        )
        assertTrue(drawPage.contains("line.onlyTextColumn"))
        assertTrue(drawPage.contains("!line.hasOverflowTextStyle"))
        assertTrue(drawLine.contains("if (fillColumnCount > 0)"))
        assertTrue(drawLine.contains("if (styledColumnCount > 0)"))
        columns.forEach {
            assertTrue(it.contains("textLine.fillColumnCount++"))
            assertTrue(it.contains("textLine.fillColumnCount--"))
        }
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first(File::isFile)
            .readText()
    }
}
