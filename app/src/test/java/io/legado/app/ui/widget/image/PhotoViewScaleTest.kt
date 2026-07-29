package io.legado.app.ui.widget.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhotoViewScaleTest {

    @Test
    fun smallLandscapeImageFitsContainerWidth() {
        assertEquals(4f, fitCenterUpscale(200f, 100f, 800f, 600f), 0f)
    }

    @Test
    fun smallPortraitImageDoesNotOverflowContainerHeight() {
        assertEquals(3f, fitCenterUpscale(100f, 200f, 800f, 600f), 0f)
    }

    @Test
    fun imageAlreadyFittedByHeightIsNotUpscaledAgain() {
        assertEquals(1f, fitCenterUpscale(300f, 600f, 800f, 600f), 0f)
    }

    @Test
    fun invalidImageSizeKeepsCurrentScale() {
        assertEquals(1f, fitCenterUpscale(0f, 100f, 800f, 600f), 0f)
    }

    @Test
    fun singleTapWaitsForDoubleTapDecision() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/widget/image/PhotoView.kt"
        ).readText()
        val dialogSource = projectFile(
            "src/main/java/io/legado/app/ui/widget/dialog/PhotoDialog.kt"
        ).readText()

        assertTrue(
            source.contains(
                "override fun onSingleTapConfirmed(e: MotionEvent): Boolean = performClick()"
            )
        )
        assertFalse(source.contains("mClickRunnable"))
        assertTrue(dialogSource.contains("binding.photoView.setOnClickListener { dismiss() }"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
