package io.legado.app.utils

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageDecoderContractTest {

    @Test
    fun `glide enables platform decoder for network images`() {
        val source = projectFile(
            "src/main/java/io/legado/app/help/glide/LegadoGlideModule.kt"
        ).readText()
        assertTrue(source.contains("builder.setImageDecoderEnabledForBitmaps(true)"))
    }

    @Test
    fun `bitmap utility keeps an api guarded image decoder fallback`() {
        val source = projectFile("src/main/java/io/legado/app/utils/BitmapUtils.kt").readText()
        assertTrue(source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.P"))
        assertTrue(source.contains("ImageDecoder.createSource(file)"))
        assertTrue(source.contains("ImageDecoder.createSource(bytes)"))
        assertTrue(source.contains("setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE)"))
    }

    @Test
    fun `image consumers use decoder aware validation`() {
        val provider = projectFile("src/main/java/io/legado/app/model/ImageProvider.kt").readText()
        val bookHelp = projectFile("src/main/java/io/legado/app/help/book/BookHelp.kt").readText()
        assertTrue(provider.contains("BitmapUtils.getImageSize(file.absolutePath)"))
        assertTrue(bookHelp.contains("BitmapUtils.getImageSize(image.absolutePath)"))
        assertTrue(bookHelp.contains("BitmapUtils.isImage(bytes)"))
    }

    @Test
    fun `legacy browser converts heif requests through native decoder`() {
        val dialog = projectFile(
            "src/main/java/io/legado/app/ui/widget/dialog/BottomWebViewDialog.kt"
        ).readText()
        assertTrue(dialog.contains("ImageLoader.loadBitmap(appCtx, url, sourceOrigin)"))
        assertTrue(dialog.contains(".disallowHardwareConfig()"))
        assertTrue(dialog.contains("path.endsWith(\".heic\", ignoreCase = true)"))
        assertTrue(dialog.contains("path.endsWith(\".heif\", ignoreCase = true)"))
        assertTrue(dialog.contains("Bitmap.CompressFormat.PNG"))
        assertTrue(dialog.contains("\"image/png\""))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
