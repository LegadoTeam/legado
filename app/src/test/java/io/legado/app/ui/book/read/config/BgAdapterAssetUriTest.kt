package io.legado.app.ui.book.read.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BgAdapterAssetUriTest {

    @Test
    fun `background previews use glide asset uri without reading bytes on main thread`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/config/BgAdapter.kt"
        )

        assertTrue(source.contains("\"file:///android_asset/bg/\$item\".toUri()"))
        assertFalse(source.contains("assets.open("))
        assertFalse(source.contains(".readBytes()"))
        assertFalse(source.contains("java.io.File"))
    }

    @Test
    fun `background names and selection behavior remain unchanged`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/config/BgAdapter.kt"
        )

        assertTrue(source.contains("tvName.text = item.substringBeforeLast(\".\")"))
        assertTrue(source.contains("ReadBookConfig.durConfig.setCurBg(1, it)"))
        assertTrue(source.contains("postEvent(EventBus.UP_CONFIG, arrayListOf(1))"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
