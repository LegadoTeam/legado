@file:Suppress("unused")

package io.legado.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.annotation.RequiresApi
import com.google.android.renderscript.Toolkit
import java.io.*
import kotlin.math.*


@Suppress("WeakerAccess", "MemberVisibilityCanBePrivate")
object BitmapUtils {

    private data class ImageSizeCacheEntry(
        val exists: Boolean,
        val length: Long,
        val lastModified: Long,
        val size: Size?,
    ) {
        fun matches(file: File): Boolean =
            exists == file.exists() &&
                length == file.length() &&
                lastModified == file.lastModified()
    }

    private val imageSizeCache = LruCache<String, ImageSizeCacheEntry>(128)
    private const val HEIF_HEADER_SCAN_BYTES = 1024 * 1024
    private val heifBrands = setOf(
        "heic", "heif", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1"
    )

    /**
     * 从path中获取图片信息,在通过BitmapFactory.decodeFile(String path)方法将突破转成Bitmap时，
     * 遇到大一些的图片，我们经常会遇到OOM(Out Of Memory)的问题。所以用到了我们上面提到的BitmapFactory.Options这个类。
     *
     * @param path   文件路径
     * @param width  想要显示的图片的宽度
     * @param height 想要显示的图片的高度
     * @return
     */
    @Throws(IOException::class)
    fun decodeBitmap(path: String, width: Int, height: Int? = null): Bitmap? {
        val isHeif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isHeifPath(path)
        if (isHeif) {
            decodeBitmapWithImageDecoder(File(path), width, height)?.let { return it }
        }
        val fis = FileInputStream(path)
        val bitmap = fis.use {
            val op = BitmapFactory.Options()
            // inJustDecodeBounds如果设置为true,仅仅返回图片实际的宽和高,宽和高是赋值给opts.outWidth,opts.outHeight;
            op.inJustDecodeBounds = true
            BitmapFactory.decodeFileDescriptor(fis.fd, null, op)
            op.inSampleSize = calculateInSampleSize(op, width, height)
            op.inJustDecodeBounds = false
            BitmapFactory.decodeFileDescriptor(fis.fd, null, op)
        }
        return bitmap ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !isHeif) {
            decodeBitmapWithImageDecoder(File(path), width, height)
        } else {
            null
        }
    }

    /**
     * 获取图片尺寸。BitmapFactory 不认识的格式（例如 HEIF/HEIC）交给系统解码器探测。
     */
    fun getImageSize(path: String): Size? {
        val file = File(path)
        imageSizeCache.get(path)?.takeIf { it.matches(file) }?.let { return it.size }
        val size = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isHeifPath(path)) {
            readHeifImageSize(file)
                ?: decodeImageSizeWithImageDecoder(file)
                ?: readImageSizeWithBitmapFactory(file)
        } else {
            readImageSizeWithBitmapFactory(file)
                ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    decodeImageSizeWithImageDecoder(file)
                } else {
                    null
                }
        }
        imageSizeCache.put(
            path,
            ImageSizeCacheEntry(file.exists(), file.length(), file.lastModified(), size)
        )
        return size
    }

    fun clearImageSizeCache() {
        imageSizeCache.evictAll()
    }

    /** 检测网络返回的图片字节，保留 SVG 的调用方回退。 */
    fun isImage(bytes: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasHeifFileSignature(bytes)) {
            return true
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth > 0 && options.outHeight > 0) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            isImageWithImageDecoder(bytes)
        } else {
            false
        }
    }

    /**
     * 解析点九图片
     */
    @Throws(IOException::class)
    fun decodeNinePatchDrawable(path: String): Drawable? {
        val fis = FileInputStream(path)
        return fis.use {
            NinePatchDrawable.createFromStream(fis, null)
        }
    }

    /**
     *计算 InSampleSize。缺省返回1
     * @param options BitmapFactory.Options,
     * @param width  想要显示的图片的宽度
     * @param height 想要显示的图片的高度
     * @return
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        width: Int? = null,
        height: Int? = null
    ): Int = calculateInSampleSize(options.outWidth, options.outHeight, width, height)

    private fun calculateInSampleSize(
        imageWidth: Int,
        imageHeight: Int,
        width: Int? = null,
        height: Int? = null
    ): Int {
        //获取比例大小
        val wRatio = width?.takeIf { it > 0 }?.let { imageWidth / it } ?: -1
        val hRatio = height?.takeIf { it > 0 }?.let { imageHeight / it } ?: -1
        //如果超出指定大小，则缩小相应的比例
        return when {
            wRatio > 1 && hRatio > 1 -> max(wRatio, hRatio)
            wRatio > 1 -> wRatio
            hRatio > 1 -> hRatio
            else -> 1
        }
    }

    private fun readImageSizeWithBitmapFactory(file: File): Size? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            Size(options.outWidth, options.outHeight)
        } else {
            null
        }
    }

    internal fun isHeifPath(path: String): Boolean {
        val cleanPath = path.substringBefore('?').substringBefore('#')
        return cleanPath.endsWith(".heic", ignoreCase = true) ||
            cleanPath.endsWith(".heif", ignoreCase = true)
    }

    /**
     * HEIF uses an ISO-BMFF `ftyp` header. Recognizing its brand is enough for
     * download validation and avoids decoding the same image just to inspect it.
     */
    internal fun hasHeifFileSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val lastTypeOffset = (bytes.size - 4).coerceAtMost(64)
        for (typeOffset in 0..lastTypeOffset) {
            if (!asciiEquals(bytes, typeOffset, "ftyp")) continue
            val majorBrandOffset = typeOffset + 4
            if (asciiBrand(bytes, majorBrandOffset)) return true
            var compatibleBrandOffset = typeOffset + 12
            while (compatibleBrandOffset + 4 <= bytes.size &&
                compatibleBrandOffset < typeOffset + 4 + 4 * 32
            ) {
                if (asciiBrand(bytes, compatibleBrandOffset)) return true
                compatibleBrandOffset += 4
            }
        }
        return false
    }

    private fun readHeifImageSize(file: File): Size? {
        if (!file.isFile || file.length() < 24) return null
        val length = min(file.length(), HEIF_HEADER_SCAN_BYTES.toLong()).toInt()
        val bytes = ByteArray(length)
        return try {
            FileInputStream(file).use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    if (count <= 0) break
                    offset += count
                }
                findHeifImageDimensions(bytes, offset)?.let { (width, height) ->
                    Size(width, height)
                }
            }
        } catch (_: IOException) {
            null
        }
    }

    internal fun findHeifImageDimensions(
        bytes: ByteArray,
        length: Int = bytes.size,
    ): Pair<Int, Int>? {
        // An ispe FullBox stores size/type, version+flags, width and height.
        val limit = length.coerceIn(0, bytes.size)
        if (limit < 20) return null
        var best: Pair<Int, Int>? = null
        for (typeOffset in 4..(limit - 20)) {
            if (!asciiEquals(bytes, typeOffset, "ispe")) continue
            val boxStart = typeOffset - 4
            val boxSize = readUInt32(bytes, boxStart, limit)
            if (boxSize < 20L || boxSize == 1L || boxStart.toLong() + boxSize > limit) continue
            val width = readUInt32(bytes, typeOffset + 8, limit)
            val height = readUInt32(bytes, typeOffset + 12, limit)
            if (width <= 0L || height <= 0L || width > Int.MAX_VALUE || height > Int.MAX_VALUE) {
                continue
            }
            val candidate = width.toInt() to height.toInt()
            val candidateArea = candidate.first.toLong() * candidate.second
            val bestArea = best?.let { it.first.toLong() * it.second } ?: -1L
            if (candidateArea > bestArea) {
                best = candidate
            }
        }
        return best
    }

    private fun readUInt32(bytes: ByteArray, offset: Int, limit: Int): Long {
        if (offset < 0 || offset + 4 > limit) return -1L
        return ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
    }

    private fun asciiBrand(bytes: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset + 4 > bytes.size) return false
        return heifBrands.any { asciiEquals(bytes, offset, it) }
    }

    private fun asciiEquals(bytes: ByteArray, offset: Int, value: String): Boolean {
        if (offset < 0 || offset + value.length > bytes.size) return false
        value.indices.forEach { index ->
            if (bytes[offset + index].toInt() != value[index].code) return false
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeBitmapWithImageDecoder(file: File, width: Int, height: Int?): Bitmap? {
        return try {
            android.graphics.ImageDecoder.decodeBitmap(
                android.graphics.ImageDecoder.createSource(file)
            ) { decoder, info, _ ->
                decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE)
                val sampleSize = calculateInSampleSize(
                    info.size.width,
                    info.size.height,
                    width,
                    height,
                )
                if (sampleSize > 1) decoder.setTargetSampleSize(sampleSize)
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeImageSizeWithImageDecoder(file: File): Size? {
        var imageSize: Size? = null
        return try {
            val bitmap = android.graphics.ImageDecoder.decodeBitmap(
                android.graphics.ImageDecoder.createSource(file)
            ) { decoder, info, _ ->
                imageSize = info.size
                decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE)
                decoder.setTargetSize(1, 1)
            }
            bitmap.recycle()
            imageSize
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun isImageWithImageDecoder(bytes: ByteArray): Boolean {
        return try {
            val bitmap = android.graphics.ImageDecoder.decodeBitmap(
                android.graphics.ImageDecoder.createSource(bytes)
            ) { decoder, _, _ ->
                decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE)
                decoder.setTargetSize(1, 1)
            }
            bitmap.recycle()
            true
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    /** 从path中获取Bitmap图片
     * @param path 图片路径
     * @return
     */
    @Throws(IOException::class)
    fun decodeBitmap(path: String): Bitmap? {
        val fis = FileInputStream(path)
        return fis.use {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true

            BitmapFactory.decodeFileDescriptor(fis.fd, null, opts)
            opts.inSampleSize = computeSampleSize(opts, -1, 128 * 128)
            opts.inJustDecodeBounds = false
            BitmapFactory.decodeFileDescriptor(fis.fd, null, opts)
        }
    }

    /**
     * 以最省内存的方式读取本地资源的图片
     * @param context 设备上下文
     * @param resId 资源ID
     * @return
     */
    fun decodeBitmap(context: Context, resId: Int): Bitmap? {
        val opt = BitmapFactory.Options()
        opt.inPreferredConfig = Config.RGB_565
        return BitmapFactory.decodeResource(context.resources, resId, opt)
    }

    /**
     * @param context 设备上下文
     * @param resId 资源ID
     * @param width
     * @param height
     * @return
     */
    fun decodeBitmap(context: Context, resId: Int, width: Int, height: Int): Bitmap? {
        val op = BitmapFactory.Options()
        // inJustDecodeBounds如果设置为true,仅仅返回图片实际的宽和高,宽和高是赋值给opts.outWidth,opts.outHeight;
        op.inJustDecodeBounds = true
        BitmapFactory.decodeResource(context.resources, resId, op) //获取尺寸信息
        op.inSampleSize = calculateInSampleSize(op, width, height)
        op.inJustDecodeBounds = false
        return BitmapFactory.decodeResource(context.resources, resId, op)
    }

    /**
     * @param context 设备上下文
     * @param fileNameInAssets Assets里面文件的名称
     * @param width 图片的宽度
     * @param height 图片的高度
     * @return Bitmap
     * @throws IOException
     */
    @Throws(IOException::class)
    fun decodeAssetsBitmap(
        context: Context,
        fileNameInAssets: String,
        width: Int,
        height: Int
    ): Bitmap? {
        var inputStream = context.assets.open(fileNameInAssets)
        return inputStream.use {
            val op = BitmapFactory.Options()
            // inJustDecodeBounds如果设置为true,仅仅返回图片实际的宽和高,宽和高是赋值给opts.outWidth,opts.outHeight;
            op.inJustDecodeBounds = true
            BitmapFactory.decodeStream(inputStream, null, op) //获取尺寸信息
            op.inSampleSize = calculateInSampleSize(op, width, height)
            inputStream = context.assets.open(fileNameInAssets)
            op.inJustDecodeBounds = false
            BitmapFactory.decodeStream(inputStream, null, op)
        }
    }

    /**
     * @param options
     * @param minSideLength
     * @param maxNumOfPixels
     * @return
     * 设置恰当的inSampleSize是解决该问题的关键之一。BitmapFactory.Options提供了另一个成员inJustDecodeBounds。
     * 设置inJustDecodeBounds为true后，decodeFile并不分配空间，但可计算出原始图片的长度和宽度，即opts.width和opts.height。
     * 有了这两个参数，再通过一定的算法，即可得到一个恰当的inSampleSize。
     * 查看Android源码，Android提供了下面这种动态计算的方法。
     */
    fun computeSampleSize(
        options: BitmapFactory.Options,
        minSideLength: Int,
        maxNumOfPixels: Int
    ): Int {
        val initialSize = computeInitialSampleSize(options, minSideLength, maxNumOfPixels)
        var roundedSize: Int
        if (initialSize <= 8) {
            roundedSize = 1
            while (roundedSize < initialSize) {
                roundedSize = roundedSize shl 1
            }
        } else {
            roundedSize = (initialSize + 7) / 8 * 8
        }
        return roundedSize
    }


    private fun computeInitialSampleSize(
        options: BitmapFactory.Options,
        minSideLength: Int,
        maxNumOfPixels: Int
    ): Int {

        val w = options.outWidth.toDouble()
        val h = options.outHeight.toDouble()

        val lowerBound = when (maxNumOfPixels) {
            -1 -> 1
            else -> ceil(sqrt(w * h / maxNumOfPixels)).toInt()
        }

        val upperBound = when (minSideLength) {
            -1 -> 128
            else -> min(
                floor(w / minSideLength),
                floor(h / minSideLength)
            ).toInt()
        }

        if (upperBound < lowerBound) {
            // return the larger one when there is no overlapping zone.
            return lowerBound
        }

        return when {
            maxNumOfPixels == -1 && minSideLength == -1 -> {
                1
            }
            minSideLength == -1 -> {
                lowerBound
            }
            else -> {
                upperBound
            }
        }
    }

    /**
     * 将Bitmap转换成InputStream
     *
     * @param bitmap
     * @return
     */
    fun toInputStream(bitmap: Bitmap): InputStream {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90 /*ignored for PNG*/, bos)
        return ByteArrayInputStream(bos.toByteArray()).also { bos.close() }
    }

}

/**
 * 获取指定宽高的图片
 */
fun Bitmap.resizeAndRecycle(newWidth: Int, newHeight: Int): Bitmap {
    //获取新的bitmap
    val bitmap = Toolkit.resize(this, newWidth, newHeight)
    recycle()
    return bitmap
}

/**
 * 高斯模糊
 */
fun Bitmap.stackBlur(radius: Int = 8): Bitmap {
    return Toolkit.blur(this, radius)
}

/**
 * 取平均色
 */
fun Bitmap.getMeanColor(): Int {
    val width: Int = this.width
    val height: Int = this.height
    var pixel: Int
    var pixelSumRed = 0
    var pixelSumBlue = 0
    var pixelSumGreen = 0
    for (i in 0..99) {
        for (j in 70..99) {
            pixel = this.getPixel(
                (i * width / 100.toFloat()).roundToInt(),
                (j * height / 100.toFloat()).roundToInt()
            )
            pixelSumRed += Color.red(pixel)
            pixelSumGreen += Color.green(pixel)
            pixelSumBlue += Color.blue(pixel)
        }
    }
    val averagePixelRed = pixelSumRed / 3000
    val averagePixelBlue = pixelSumBlue / 3000
    val averagePixelGreen = pixelSumGreen / 3000
    return Color.rgb(
        averagePixelRed + 3,
        averagePixelGreen + 3,
        averagePixelBlue + 3
    )

}
