package io.legado.app.api.controller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookControllerImageIsolationTest {

    private val source by lazy {
        projectFile(
            "src/main/java/io/legado/app/api/controller/BookController.kt"
        ).readText().replace("\r\n", "\n")
    }

    @Test
    fun `image requests reject blank book urls`() {
        val getImg = getImgSource()

        assertTrue(getImg.contains("if (bookUrl.isNullOrBlank())"))
        assertTrue(getImg.indexOf("isNullOrBlank") < getImg.indexOf("cachedContext"))
    }

    @Test
    fun `image requests use one local book context snapshot`() {
        val getImg = getImgSource()
        val runBlocking = getImg.substring(getImg.indexOf("val bitmap = runBlocking"))

        assertTrue(source.contains("private data class ImageContext("))
        assertTrue(source.contains("@Volatile\n    private var cachedImageContext"))
        assertTrue(getImg.contains("val cachedContext = cachedImageContext"))
        assertTrue(getImg.contains("cachedContext?.takeIf { it.bookUrl == bookUrl }"))
        assertTrue(getImg.indexOf("cachedContext") < getImg.indexOf("runBlocking"))
        assertTrue(
            runBlocking.contains(
                "ImageProvider.cacheImage(imageContext.book, src, imageContext.bookSource)"
            )
        )
        assertTrue(
            runBlocking.contains("ImageProvider.getImage(imageContext.book, src, width)")
        )
        assertFalse(source.contains("imageContextLock"))
        assertFalse(source.contains("private lateinit var book: Book"))
        assertFalse(source.contains("private var bookUrl: String"))
    }

    private fun getImgSource(): String {
        val start = source.indexOf("fun getImg(")
        val end = source.indexOf("\n    /**", start)
        require(start >= 0 && end > start)
        return source.substring(start, end)
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
