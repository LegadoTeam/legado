package io.legado.app.ui.widget.seekbar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VerticalSeekBarMigrationTest {

    @Test
    fun brightnessControlUsesRotatedAppCompatSeekBar() {
        val layout = projectFile("src/main/res/layout/view_read_menu.xml").readText()

        assertTrue(layout.contains("VerticalSeekBarWrapper"))
        assertTrue(layout.contains("androidx.appcompat.widget.AppCompatSeekBar"))
        assertTrue(layout.contains("android:id=\"@+id/seek_brightness\""))
        assertTrue(layout.contains("android:max=\"255\""))
        assertTrue(layout.contains("android:rotation=\"-90\""))
    }

    @Test
    fun obsoleteReflectiveSeekBarImplementationIsRemoved() {
        assertFalse(
            projectFile("src/main/java/io/legado/app/ui/widget/seekbar/VerticalSeekBar.kt")
                .exists()
        )
        val attrs = projectFile("src/main/res/values/attrs.xml").readText()
        assertFalse(attrs.contains("name=\"VerticalSeekBar\""))
        assertFalse(attrs.contains("name=\"seekBarRotation\""))
    }

    @Test
    fun wrapperKeepsDirectionAndCompactMeasurementRules() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/widget/seekbar/VerticalSeekBarWrapper.kt"
        ).readText()
        val readMenu = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadMenu.kt"
        ).readText()

        assertTrue(source.contains("ViewCompat.LAYOUT_DIRECTION_LTR"))
        assertTrue(source.contains("MeasureSpec.makeMeasureSpec(contentHeight"))
        assertTrue(source.contains("child.measuredHeight + paddingLeft + paddingRight"))
        assertTrue(readMenu.contains("seekBrightness.applyTint(context.accentColor)"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.exists() || it.parentFile?.exists() == true }
            ?: File(pathInApp)
    }
}
