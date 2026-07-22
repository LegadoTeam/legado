package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReviewIconColorSourceTest {

    @Test
    fun `review icon color is persisted and exported`() {
        val source = projectFile(
            "src/main/java/io/legado/app/help/config/ReadBookConfig.kt"
        ).readText().normalizeLines()

        assertTrue(source.contains("var reviewIconColor: Int = 0"))
        assertTrue(source.contains("get() = config.reviewIconColor"))
        assertTrue(source.contains("exportConfig.reviewIconColor = shareConfig.reviewIconColor"))
        assertTrue(source.contains("\"reviewIconColor\" to reviewIconColor"))
    }

    @Test
    fun `provider prefers configured color and keeps theme fallback`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/provider/ChapterProvider.kt"
        ).readText().normalizeLines()

        assertTrue(
            Regex(
                """reviewPaint\.color = ReadBookConfig\.reviewIconColor\.takeIf \{ it != 0 \}"""
            ).containsMatchIn(source)
        )
        assertTrue(source.contains("ColorUtils.lightenColor(contentPaint.color)"))
        assertTrue(source.contains("ColorUtils.darkenColor(contentPaint.color)"))
    }

    @Test
    fun `color picker uses a unique id and refreshes review rendering`() {
        val dialog = projectFile(
            "src/main/java/io/legado/app/ui/book/read/config/BgTextConfigDialog.kt"
        ).readText().normalizeLines()
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText().normalizeLines()
        val layout = projectFile(
            "src/main/res/layout/dialog_read_bg_text.xml"
        ).readText().normalizeLines()

        assertTrue(dialog.contains("const val REVIEW_ICON_COLOR = 124"))
        assertTrue(dialog.contains(".setDialogId(REVIEW_ICON_COLOR)"))
        assertTrue(dialog.contains("ReadBookConfig.reviewIconColor = 0"))
        assertTrue(dialog.contains("arrayListOf(8, 9, 11)"))
        assertTrue(activity.contains("REVIEW_ICON_COLOR ->"))
        assertTrue(layout.contains("@+id/tv_review_icon_color"))
        assertTrue(layout.contains("@string/review_icon_color_title"))
    }

    private fun String.normalizeLines(): String = replace("\r\n", "\n")

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
