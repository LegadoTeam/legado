package io.legado.app.help.book

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ResourceCacheFilesTest {
    @Test fun failedReplacementAndMetadataRestoreOldTextImagesAndMarkers() {
        val root = Files.createTempDirectory("resource-cache-files").toFile()
        try {
            val body = File(root, "body.nb").apply { writeText("old body") }
            val image = File(root, "image.png").apply { writeText("old image") }
            val marker = File(root, "body.nb.reversed").apply { writeText("reversed") }
            val added = File(root, "new.png")
            fun staged(value: String) = File.createTempFile("staged-", ".tmp", root).apply { writeText(value) }
            val replacements = linkedMapOf(body to staged("new body"), image to staged("new image"),
                marker to null, added to staged("added image"))
            assertThrows(IOException::class.java) {
                replaceResourceFiles(replacements) { throw IOException("metadata write failed") }
            }
            assertEquals("old body", body.readText())
            assertEquals("old image", image.readText())
            assertEquals("reversed", marker.readText())
            assertFalse(added.exists())
            assertThrows(IOException::class.java) {
                replaceResourceFiles(linkedMapOf(body to staged("replacement"),
                    image to File(root, "missing-stage"))) { fail("Metadata must not run") }
            }
            assertEquals("old body", body.readText())
            assertEquals("old image", image.readText())
            replaceResourceFiles(linkedMapOf(body to staged("new body"),
                image to staged("new image"), marker to null, added to staged("added image"))) {}
            assertEquals("new body", body.readText())
            assertEquals("new image", image.readText())
            assertFalse(marker.exists())
            assertEquals("added image", added.readText())
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".resource-backup-") })
        } finally { root.deleteRecursively() }
    }
}
