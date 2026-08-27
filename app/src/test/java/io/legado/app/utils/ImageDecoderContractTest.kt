package io.legado.app.utils

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
        assertTrue(source.contains("isHeifPath(path)"))
        assertTrue(source.contains("setTargetSampleSize(sampleSize)"))
        assertTrue(source.contains("imageSizeCache"))
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
        assertTrue(dialog.contains("heifResponseCache"))
    }

    @Test
    fun `heif file signatures are recognized without pixel decoding`() {
        assertTrue(BitmapUtils.hasHeifFileSignature(bytesWithBrand("heic")))
        assertTrue(BitmapUtils.hasHeifFileSignature(bytesWithBrand("mif1")))
        assertTrue(BitmapUtils.hasHeifFileSignature(bytesWithCompatibleBrand("heix")))
        assertFalse(BitmapUtils.hasHeifFileSignature(bytesWithBrand("avif")))
        assertFalse(BitmapUtils.hasHeifFileSignature(ByteArray(32)))
    }

    @Test
    fun `heif path detection ignores query and fragment`() {
        assertTrue(BitmapUtils.isHeifPath("/cache/image.HEIF?token=1"))
        assertTrue(BitmapUtils.isHeifPath("/cache/image.heic#preview"))
        assertFalse(BitmapUtils.isHeifPath("/cache/image.jpg?format=heic"))
    }

    @Test
    fun `heif dimensions can be read from the ispe metadata box`() {
        val bytes = ByteArray(64)
        writeUInt32(bytes, 16, 20)
        "ispe".toByteArray().copyInto(bytes, 20)
        writeUInt32(bytes, 28, 1280)
        writeUInt32(bytes, 32, 720)
        val dimensions = BitmapUtils.findHeifImageDimensions(bytes)
        assertEquals(1280, dimensions?.first)
        assertEquals(720, dimensions?.second)
    }

    private fun bytesWithBrand(brand: String): ByteArray {
        val bytes = ByteArray(24)
        "ftyp".toByteArray().copyInto(bytes, 4)
        brand.toByteArray().copyInto(bytes, 8)
        return bytes
    }

    private fun bytesWithCompatibleBrand(brand: String): ByteArray {
        val bytes = bytesWithBrand("mif1")
        brand.toByteArray().copyInto(bytes, 16)
        return bytes
    }

    private fun writeUInt32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
