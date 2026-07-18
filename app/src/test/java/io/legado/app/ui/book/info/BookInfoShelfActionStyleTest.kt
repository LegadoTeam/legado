package io.legado.app.ui.book.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookInfoShelfActionStyleTest {

    @Test
    fun `shelf action uses tonal accent style in both orientations`() {
        val layouts = listOf(
            readProjectFile("src/main/res/layout/activity_book_info.xml"),
            readProjectFile("src/main/res/layout-land/activity_book_info.xml")
        )

        layouts.forEach { layout ->
            assertTrue(layout.contains("AccentTonalBgTextView"))
            assertTrue(layout.contains("android:id=\"@+id/tv_shelf\""))
            assertTrue(layout.contains("android:layout_height=\"48dp\""))
            assertTrue(layout.contains("app:radius=\"8dp\""))
        }
    }

    @Test
    fun `tonal accent style keeps text readable`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/widget/text/AccentTonalBgTextView.kt"
        )

        assertTrue(source.contains("ColorUtils.adjustAlpha(accentColor, 0.12f)"))
        assertTrue(source.contains("ColorUtils.adjustAlpha(accentColor, 0.22f)"))
        assertEquals(1, source.lines().count { it.trim() == "setTextColor(accentColor)" })
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
