package io.legado.app.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream

class InputStreamExtensionsTest {

    @Test
    fun `recognizes json objects at former partial buffer lengths`() {
        for (length in listOf(129, 180, 255)) {
            assertTrue(jsonObject(length).inputStream().looksLikeJson())
        }
    }

    @Test
    fun `recognizes json arrays at former partial buffer lengths`() {
        for (length in listOf(129, 180, 255)) {
            assertTrue(jsonArray(length).inputStream().looksLikeJson())
        }
    }

    @Test
    fun `does not depend on available for content provider streams`() {
        val input = TrackingInputStream(
            bytes = jsonObject(16 * 1024),
            reportedAvailable = 0,
        )

        assertTrue(input.looksLikeJson())
        assertTrue(input.bytesRead < 128)
        assertTrue(input.closed)
    }

    @Test
    fun `supports short reads after leading whitespace`() {
        val input = TrackingInputStream(
            bytes = (" ".repeat(257) + jsonObject(8193).toString(Charsets.UTF_8)).toByteArray(),
            maxReadSize = 7,
        )

        assertTrue(input.looksLikeJson())
        assertTrue(input.bytesRead < 512)
        assertTrue(input.closed)
    }

    @Test
    fun `limits whitespace sniffing and closes the stream`() {
        val bytes = ByteArray(128 * 1024) { ' '.code.toByte() }
        val input = TrackingInputStream(bytes, maxReadSize = 11)

        assertFalse(input.looksLikeJson())
        assertTrue(input.bytesRead <= 64 * 1024)
        assertTrue(input.bytesRead < bytes.size)
        assertTrue(input.closed)
    }

    @Test
    fun `accepts a json marker at the sniff boundary only`() {
        val accepted = ByteArray(64 * 1024 + 2) { ' '.code.toByte() }.apply {
            this[64 * 1024 - 1] = '{'.code.toByte()
            this[lastIndex] = '}'.code.toByte()
        }
        val rejected = ByteArray(64 * 1024 + 2) { ' '.code.toByte() }.apply {
            this[64 * 1024] = '{'.code.toByte()
            this[lastIndex] = '}'.code.toByte()
        }

        assertTrue(accepted.inputStream().looksLikeJson())
        assertFalse(rejected.inputStream().looksLikeJson())
    }

    @Test
    fun `falls back to a single byte when bulk read returns zero`() {
        val input = TrackingInputStream(
            bytes = "    {\"value\":1}".toByteArray(),
            zeroBulkReads = 1,
        )

        assertTrue(input.looksLikeJson())
        assertTrue(input.closed)
    }

    @Test
    fun `rejects non json after a bounded prefix read`() {
        val bytes = "plain text".repeat(8192).toByteArray()
        val input = TrackingInputStream(bytes)

        assertFalse(input.looksLikeJson())
        assertTrue(input.bytesRead < bytes.size)
        assertTrue(input.closed)
    }

    @Test
    fun `closes the stream when reading fails`() {
        val input = TrackingInputStream(
            bytes = "    {\"value\":1}".toByteArray(),
            failAfter = 3,
        )

        assertThrows(IOException::class.java) {
            input.looksLikeJson()
        }
        assertTrue(input.closed)
    }

    @Test
    fun `treats an unfinished object as a json import candidate`() {
        assertTrue("{\"value\":1".byteInputStream().looksLikeJson())
    }

    @Test
    fun `rejects null empty and whitespace only streams`() {
        assertFalse((null as InputStream?).looksLikeJson())
        assertFalse(byteArrayOf().inputStream().looksLikeJson())
        assertFalse(" \t\r\n".byteInputStream().looksLikeJson())
    }

    private fun jsonObject(length: Int): ByteArray {
        val padding = length - OBJECT_OVERHEAD
        require(padding >= 0)
        return "{\"value\":\"${"x".repeat(padding)}\"}".toByteArray().also {
            check(it.size == length)
        }
    }

    private fun jsonArray(length: Int): ByteArray {
        val padding = length - ARRAY_OVERHEAD
        require(padding >= 0)
        return "[\"${"x".repeat(padding)}\"]".toByteArray().also {
            check(it.size == length)
        }
    }

    private class TrackingInputStream(
        private val bytes: ByteArray,
        private val reportedAvailable: Int? = null,
        private val maxReadSize: Int = Int.MAX_VALUE,
        private val failAfter: Int? = null,
        private var zeroBulkReads: Int = 0,
    ) : InputStream() {

        var bytesRead = 0
            private set
        var closed = false
            private set

        private var position = 0

        override fun read(): Int {
            failIfNeeded()
            if (position >= bytes.size) return -1
            bytesRead++
            return bytes[position++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            failIfNeeded()
            if (position >= bytes.size) return -1
            if (zeroBulkReads > 0) {
                zeroBulkReads--
                return 0
            }
            val count = minOf(length, maxReadSize, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            bytesRead += count
            return count
        }

        override fun available(): Int = reportedAvailable ?: bytes.size - position

        override fun close() {
            closed = true
        }

        private fun failIfNeeded() {
            if (failAfter != null && bytesRead >= failAfter) {
                throw IOException("read failed")
            }
        }
    }

    private companion object {
        const val OBJECT_OVERHEAD = 12
        const val ARRAY_OVERHEAD = 4
    }
}
