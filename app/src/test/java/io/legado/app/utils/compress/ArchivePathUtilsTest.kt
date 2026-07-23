package io.legado.app.utils.compress

import io.legado.app.utils.isSameOrDescendantOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchivePathUtilsTest {

    @Test
    fun `same-prefix sibling is outside destination`() = withTempDirectory { root ->
        val destDir = root.resolve("extract").apply { mkdirs() }
        val siblingName = "${destDir.name}-sibling"

        assertThrows(SecurityException::class.java) {
            resolveArchiveEntryFile(destDir, "../$siblingName/payload.txt")
        }
        assertFalse(
            root.resolve(siblingName).canonicalFile.isSameOrDescendantOf(
                destDir.canonicalFile
            )
        )
    }

    @Test
    fun `zip extraction cannot write to same-prefix sibling`() = withTempDirectory { root ->
        val destDir = root.resolve("extract").apply { mkdirs() }
        val siblingName = "${destDir.name}-sibling"
        val zipBytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("../$siblingName/payload.txt"))
                zip.write("payload".toByteArray())
                zip.closeEntry()
            }
            output.toByteArray()
        }

        assertThrows(SecurityException::class.java) {
            ZipUtils.unZipToPath(ByteArrayInputStream(zipBytes), destDir)
        }
        assertFalse(root.resolve("$siblingName/payload.txt").exists())
    }

    @Test
    fun `absolute archive entries are rejected`() = withTempDirectory { root ->
        val destDir = root.resolve("extract").apply { mkdirs() }

        listOf(
            "/archive-outside/payload.txt",
            "\\archive-outside\\payload.txt",
            "C:\\archive-outside\\payload.txt",
        ).forEach { entryName ->
            assertThrows(entryName, SecurityException::class.java) {
                resolveArchiveEntryFile(destDir, entryName)
            }
        }
    }

    @Test
    fun `normal child path remains inside destination`() = withTempDirectory { root ->
        val destDir = root.resolve("extract").apply { mkdirs() }
        val entryFile = resolveArchiveEntryFile(destDir, "nested/../payload.txt")

        assertEquals(destDir.resolve("payload.txt").canonicalFile, entryFile)
        assertTrue(
            entryFile.canonicalFile.isSameOrDescendantOf(destDir.canonicalFile)
        )
    }

    @Test
    fun `shared preparation creates only entries inside destination`() =
        withTempDirectory { root ->
            val destDir = root.resolve("extract").apply { mkdirs() }
            val directory = prepareArchiveEntryFile(
                destDir,
                "nested/content",
                isDirectory = true,
            )
            val entryFile = prepareArchiveEntryFile(
                destDir,
                "nested/content/payload.txt",
                isDirectory = false,
            )

            entryFile.writeText("payload")

            assertTrue(directory.isDirectory)
            assertEquals("payload", entryFile.readText())
            assertTrue(
                entryFile.canonicalFile.isSameOrDescendantOf(destDir.canonicalFile)
            )
        }

    @Test
    fun `symlink to outside destination is rejected`() = withTempDirectory { root ->
        val destDir = root.resolve("extract").apply { mkdirs() }
        val outsideDir = root.resolve("outside").apply { mkdirs() }
        val link = destDir.resolve("linked")
        try {
            Files.createSymbolicLink(link.toPath(), outsideDir.toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic links are unavailable", error)
        }

        assertThrows(SecurityException::class.java) {
            prepareArchiveEntryFile(destDir, "linked/payload.txt", isDirectory = false)
        }
    }

    @Test
    fun `destination itself and directory entries are allowed`() = withTempDirectory { root ->
        val destDir = root.resolve("extract").apply { mkdirs() }

        assertEquals(destDir.canonicalFile, resolveArchiveEntryFile(destDir, "").canonicalFile)
        assertEquals(destDir.canonicalFile, resolveArchiveEntryFile(destDir, ".").canonicalFile)
        assertEquals(
            destDir.resolve("nested").canonicalFile,
            resolveArchiveEntryFile(destDir, "nested/").canonicalFile,
        )
        assertTrue(
            destDir.canonicalFile.isSameOrDescendantOf(destDir.canonicalFile)
        )
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("archive-path-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
