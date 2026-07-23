package io.legado.app.help

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BottomBarSkinArchiveTest {

    @Test
    fun `extract keeps image basenames and contents`() = withTempDir { root ->
        val destination = File(root, "staging")
        val zip = zipBytes {
            entry("nested/bookshelf_selected.PNG", byteArrayOf(1, 2, 3))
            entry("home_normal.jpg", byteArrayOf(4, 5))
            entry("readme.txt", byteArrayOf(9))
        }

        val files = BottomBarSkinArchive.extract(ByteArrayInputStream(zip), destination)

        assertEquals(listOf("bookshelf_selected.png", "home_normal.jpg"), files.map(File::getName))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(destination, "bookshelf_selected.png").readBytes())
    }

    @Test
    fun `extract contains traversal paths inside destination`() = withTempDir { root ->
        val destination = File(root, "staging")
        val zip = zipBytes { entry("../outside.png", byteArrayOf(1)) }

        val files = BottomBarSkinArchive.extract(ByteArrayInputStream(zip), destination)

        assertEquals(listOf("outside.png"), files.map(File::getName))
        assertTrue(File(destination, "outside.png").isFile)
        assertFalse(File(root, "outside.png").exists())
    }

    @Test
    fun `extract renames case insensitive duplicates`() = withTempDir { root ->
        val destination = File(root, "staging")
        val zip = zipBytes {
            entry("nested/icon.PNG", byteArrayOf(1))
            entry("icon.png", byteArrayOf(2))
        }

        val files = BottomBarSkinArchive.extract(ByteArrayInputStream(zip), destination)

        assertEquals(listOf("icon.png", "icon (2).png"), files.map(File::getName))
    }

    @Test
    fun `duplicate unicode names stay within byte limit`() = withTempDir { root ->
        val destination = File(root, "staging")
        val name = "é".repeat(200) + ".png"
        val zip = zipBytes {
            entry("first/$name", byteArrayOf(1))
            entry("second/$name", byteArrayOf(2))
        }

        val files = BottomBarSkinArchive.extract(ByteArrayInputStream(zip), destination)

        assertEquals(2, files.size)
        assertTrue(files[1].name.endsWith(" (2).png"))
        files.forEach {
            assertTrue(
                it.name.codePointCount(0, it.name.length) <=
                    BottomBarSkinFormat.MAX_IMAGE_NAME_LENGTH
            )
            assertTrue(it.name.toByteArray().size <= BottomBarSkinFormat.MAX_IMAGE_NAME_BYTES)
        }
    }

    @Test
    fun `extract rejects archives without images and removes destination`() = withTempDir { root ->
        val destination = File(root, "staging")
        val zip = zipBytes { entry("readme.txt", byteArrayOf(1)) }

        assertThrows(IllegalArgumentException::class.java) {
            BottomBarSkinArchive.extract(ByteArrayInputStream(zip), destination)
        }
        assertFalse(destination.exists())
    }

    @Test
    fun `extract rejects too many entries and removes destination`() = withTempDir { root ->
        val destination = File(root, "staging")
        val zip = zipBytes {
            entry("icon.png", byteArrayOf(1))
            repeat(BottomBarSkinArchive.MAX_ENTRIES) { index ->
                entry("ignored-$index.txt", byteArrayOf())
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            BottomBarSkinArchive.extract(ByteArrayInputStream(zip), destination)
        }
        assertFalse(destination.exists())
    }

    @Test
    fun `directory payload is subject to entry limit`() = withTempDir { root ->
        val destination = File(root, "staging")
        val zip = zipBytes {
            streamEntry("payload/") { output ->
                val block = ByteArray(8192)
                var remaining = BottomBarSkinArchive.MAX_ENTRY_BYTES + 1
                while (remaining > 0) {
                    val count = minOf(block.size, remaining)
                    output.write(block, 0, count)
                    remaining -= count
                }
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            BottomBarSkinArchive.extract(ByteArrayInputStream(zip), destination)
        }
        assertFalse(destination.exists())
    }

    private fun zipBytes(block: ZipBuilder.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> ZipBuilder(zip).block() }
        return output.toByteArray()
    }

    private class ZipBuilder(private val zip: ZipOutputStream) {

        fun entry(name: String, bytes: ByteArray) {
            streamEntry(name) { it.write(bytes) }
        }

        fun streamEntry(name: String, write: (ZipOutputStream) -> Unit) {
            zip.putNextEntry(ZipEntry(name))
            write(zip)
            zip.closeEntry()
        }
    }

    private fun withTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("bottom-bar-skin-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
