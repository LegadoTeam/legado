package io.legado.app.config

import me.ag2s.epublib.domain.GuideReference
import me.ag2s.epublib.util.StringUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class LocaleResourceConsistencyTest {

    @Test
    fun `book type arrays keep every source type`() {
        val expected = mapOf(
            "values" to listOf("Text", "Audio", "Image", "File", "Video"),
            "values-es-rES" to listOf("Texto", "Audio", "Image", "File", "Vídeo"),
            "values-pt-rBR" to listOf("Texto", "Áudio", "Image", "File", "Vídeo"),
            "values-vi" to listOf("Văn bản", "Âm thanh", "Hình ảnh", "Tập tin", "Video"),
            "values-zh" to listOf("文本", "音频", "图片", "文件", "视频"),
            "values-zh-rHK" to listOf("文本", "音頻", "图片", "文件", "影片"),
            "values-zh-rTW" to listOf("文字", "音訊", "圖片", "資料", "影片"),
        )
        val resourceRoot = File(repositoryRoot(), "app/src/main/res")
        val arrays = resourceRoot.listFiles().orEmpty()
            .map { File(it, "arrays.xml") }
            .filter(File::isFile)
            .mapNotNull { file ->
                val document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(file)
                val nodes = document.getElementsByTagName("string-array")
                val bookTypes = (0 until nodes.length)
                    .map { nodes.item(it) as Element }
                    .firstOrNull { it.getAttribute("name") == "book_type" }
                    ?: return@mapNotNull null
                requireNotNull(file.parentFile).name to
                    bookTypes.getElementsByTagName("item").let { items ->
                        (0 until items.length).map { items.item(it).textContent.trim() }
                    }
            }
            .toMap()

        assertEquals("book_type arrays must match BookSourceType indices", expected, arrays)
    }

    @Test
    fun `epub comparisons ignore the device locale`() {
        synchronized(Locale::class.java) {
            val original = Locale.getDefault()
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"))

                assertTrue(StringUtil.endsWithIgnoreCase("cover.GIF", ".gif"))
                assertTrue(StringUtil.startsWithIgnoreCase("INFO", "info"))
                assertEquals(
                    "title",
                    GuideReference(null, "TITLE", "Guide", null).type,
                )
            } finally {
                Locale.setDefault(original)
            }
        }
    }

    private fun repositoryRoot(): File {
        val userDirectory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        return generateSequence(userDirectory) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
