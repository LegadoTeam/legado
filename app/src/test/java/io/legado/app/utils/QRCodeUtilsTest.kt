package io.legado.app.utils

import com.google.zxing.BarcodeFormat
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.QRCodeWriter
import com.king.zxing.DecodeConfig
import com.king.zxing.DecodeFormatManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QRCodeUtilsTest {

    @Test
    fun decodesGeneratedQrCode() {
        val content = "legado-zxing-3.5"
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 256, 256)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] = if (matrix[x, y]) BLACK else WHITE
            }
        }
        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)

        val result = QRCodeUtils.parseCodeResult(source, DecodeFormatManager.QR_CODE_HINTS)

        assertEquals(content, result?.text)
    }

    @Test
    fun configuresFullAreaQrCodeScanning() {
        val hints = DecodeFormatManager.QR_CODE_HINTS
        val config = DecodeConfig()
            .setHints(hints)
            .setFullAreaScan(true)
            .setAreaRectRatio(0.8f)

        assertSame(hints, config.getHints())
        assertTrue(config.isFullAreaScan())
        assertEquals(0.8f, config.getAreaRectRatio(), 0f)
    }

    private companion object {
        const val BLACK = -16777216
        const val WHITE = -1
    }
}
