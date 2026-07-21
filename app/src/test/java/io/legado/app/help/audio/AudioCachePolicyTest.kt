package io.legado.app.help.audio

import io.legado.app.data.entities.BookChapter
import io.legado.app.model.AudioCacheKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AudioCachePolicyTest {

    @Test
    fun `chapter key survives index and title changes`() {
        val original = BookChapter(url = "chapter/42", title = "Old title", index = 3)
        val reordered = BookChapter(url = "chapter/42", title = "New title", index = 99)

        assertEquals(AudioCacheKey.from(original), AudioCacheKey.from(reordered))
        assertNotEquals(
            AudioCacheKey.from(original),
            AudioCacheKey.from(BookChapter(url = "chapter/43", title = "Old title", index = 3))
        )
        assertEquals(
            AudioCacheKey.from("", "Title only"),
            AudioCacheKey.from(BookChapter(title = "Title only"))
        )
    }

    @Test
    fun `file name is safe and round trips stable key`() {
        val key = AudioCacheKey.from("chapter/42", "ignored")
        val fileName = AudioCachePolicy.buildFileName(
            chapterIndex = 12,
            key = key,
            chapterTitle = " bad:/title?*\u0000 ",
            playUrlHash = "0123456789abcdef",
            revision = "deadbeef",
            extension = "mp3"
        )

        assertFalse(fileName.any { it in "\\/:*?\"<>|" || it.isISOControl() })
        assertEquals(
            CachedAudioFile(12, key, "mp3"),
            AudioCachePolicy.parseFileName(fileName)
        )
        assertNull(AudioCachePolicy.parseFileName("$fileName.part"))
        assertNull(AudioCachePolicy.parseFileName("$fileName.complete"))
        assertNull(AudioCachePolicy.parseFileName(fileName.substringBeforeLast('.') + ".php"))
    }

    @Test
    fun `extension detection accepts audio and rejects playlists`() {
        assertEquals(
            "mp3",
            AudioCachePolicy.detectExtension(
                "audio/mpeg; charset=binary",
                "https://example.com/audio",
                "https://example.com/play"
            )
        )
        assertEquals(
            "m4b",
            AudioCachePolicy.detectExtension(
                "application/octet-stream",
                "https://example.com/book.M4B?token=1",
                "https://example.com/play"
            )
        )
        assertEquals(
            "audio",
            AudioCachePolicy.detectExtension(
                null,
                "https://example.com/audio.php",
                "https://example.com/play"
            )
        )
        assertThrows(UnsupportedOperationException::class.java) {
            AudioCachePolicy.detectExtension(
                "application/vnd.apple.mpegurl",
                "https://example.com/master",
                "https://example.com/play"
            )
        }
        assertThrows(UnsupportedOperationException::class.java) {
            AudioCachePolicy.detectExtension(
                null,
                "https://example.com/master.m3u8?token=1",
                "https://example.com/play"
            )
        }
        assertThrows(UnsupportedOperationException::class.java) {
            AudioCachePolicy.requireCacheablePlayUrl(
                "[\"https://example.com/one.mp3\",\"https://example.com/two.mp3\"]"
            )
        }
    }

    @Test
    fun `range normalization rejects invalid start and clamps end`() {
        assertEquals(2..4, AudioCachePolicy.normalizeRange(2, 9, 5))
        assertEquals(0..0, AudioCachePolicy.normalizeRange(0, 0, 1))
        assertNull(AudioCachePolicy.normalizeRange(-1, 2, 5))
        assertNull(AudioCachePolicy.normalizeRange(3, 2, 5))
        assertNull(AudioCachePolicy.normalizeRange(5, 5, 5))
        assertNull(AudioCachePolicy.normalizeRange(0, 0, 0))
    }

    @Test
    fun `file validation rejects empty and incomplete writes`() {
        assertTrue(AudioCachePolicy.isCompleteFile(10, null))
        assertTrue(AudioCachePolicy.isCompleteFile(10, 10))
        assertFalse(AudioCachePolicy.isCompleteFile(0, null))
        assertFalse(AudioCachePolicy.isCompleteFile(9, 10))
    }

    @Test
    fun `cache metadata round trips remote url and rejects partial content`() {
        val playUrl = "https://example.com/audio.mp3?token=abc\nheader=value"
        val metadata = AudioCacheMetadata.encode(playUrl)

        assertEquals(playUrl, AudioCacheMetadata.decode(metadata))
        assertNull(AudioCacheMetadata.decode(metadata.substringBeforeLast('\n')))
        assertNull(AudioCacheMetadata.decode(metadata.replace("token=abc", "token=changed")))
    }

    @Test
    fun `copy checks cancellation between chunks`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                val job = Job()
                val input = object : ByteArrayInputStream(ByteArray(DEFAULT_BUFFER_SIZE * 2)) {
                    private var firstRead = true

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        return super.read(buffer, offset, length).also {
                            if (firstRead) {
                                firstRead = false
                                job.cancel(CancellationException("stop"))
                            }
                        }
                    }
                }
                withContext(job) {
                    AudioCacheManager.copyCancellable(input, ByteArrayOutputStream())
                }
            }
        }
    }
}
