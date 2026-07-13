package io.legado.app.ui.widget.image

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
