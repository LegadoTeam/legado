package io.legado.app.ui.widget.text

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScrollSelectionColorTest {

    @Test
    fun `scrolling text widgets use translucent accent selection`() {
        val expected = "highlightColor = ColorUtils.adjustAlpha(context.accentColor, 0.35f)"

        assertTrue(readWidget("ScrollTextView.kt").contains(expected))
        assertTrue(readWidget("ScrollMultiAutoCompleteTextView.kt").contains(expected))
    }

    private fun readWidget(name: String): String {
        val pathInApp = "src/main/java/io/legado/app/ui/widget/text/$name"
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
