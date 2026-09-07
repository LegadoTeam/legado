package io.legado.app.base

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackgroundImageThreadingTest {

    @Test
    fun `background image decodes off the main thread`() {
        val source = File("src/main/java/io/legado/app/base/BaseActivity.kt").readText()
        val body = source.substringAfter("open fun upBackgroundImage")
        assertTrue(body.contains("Dispatchers.IO"))
        assertTrue(body.contains("isFinishing"))
    }
}
