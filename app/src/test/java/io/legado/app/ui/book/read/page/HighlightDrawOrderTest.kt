package io.legado.app.ui.book.read.page

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HighlightDrawOrderTest {

    @Test
    fun `highlight underline is drawn before text while strike remains after text`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/TextLine.kt"
        ).readText().replace("\r\n", "\n")
        val drawTextLine = section(source, "private fun drawTextLine", "private fun fastDrawTextLine")
        val underlinePrepass = drawTextLine.indexOf(
            "drawHighlightRuns(canvas, underlineBeforeText = true)"
        )
        val columnDraw = drawTextLine.indexOf("columns[i].draw(view, canvas)")
        assertTrue(underlinePrepass >= 0)
        assertTrue(columnDraw > underlinePrepass)

        val drawRuns = section(source, "private fun drawHighlightRuns", "fun checkFastDraw")
        assertTrue(drawRuns.contains("underline.takeIf { underlineBeforeText }"))
        assertTrue(drawRuns.contains("strike.takeIf { !underlineBeforeText }"))
        assertTrue(drawRuns.contains("box.takeIf { !underlineBeforeText }"))
    }

    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex)
        require(startIndex >= 0 && endIndex > startIndex)
        return source.substring(startIndex, endIndex)
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Missing project file: $pathInApp")
}
