package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BackupMediaTest {

    @Test
    fun onlyNonEmptySupportedDirectoriesAreIncluded() = withTempDirectory { root ->
        root.resolve("covers").mkdirs()
        root.resolve("bg").mkdirs()
        root.resolve("other").apply {
            mkdirs()
            resolve("ignored.png").writeText("ignored")
        }
        root.resolve("covers/cover.png").writeText("cover")

        assertEquals(
            listOf("covers"),
            findBackupMediaDirectories(root).map { it.name },
        )

        root.resolve("bg/background.png").writeText("background")
        assertEquals(
            listOf("covers", "bg"),
            findBackupMediaDirectories(root).map { it.name },
        )
    }

    @Test
    fun onlyReferencedInternalBackgroundsArePreparedForBackup() = withTempDirectory { root ->
        val externalRoot = root.resolve("external")
        val backupRoot = root.resolve("backup").apply { mkdirs() }
        backupRoot.resolve("bg/stale.png").apply {
            parentFile?.mkdirs()
            writeText("stale")
        }
        externalRoot.resolve("covers").mkdirs()
        externalRoot.resolve("covers/cover.png").writeText("cover")
        externalRoot.resolve("bg").mkdirs()
        val kept = externalRoot.resolve("bg/kept.png").apply { writeText("kept") }
        val shared = externalRoot.resolve("bg/shared.png").apply { writeText("shared") }
        val orphan = externalRoot.resolve("bg/orphan.png").apply { writeText("orphan") }
        val outside = root.resolve("outside.png").apply { writeText("outside") }

        val directories = prepareBackupMediaDirectories(
            externalRoot,
            backupRoot,
            listOf(
                kept.name,
                shared.absolutePath,
                shared.absolutePath,
                outside.absolutePath,
                externalRoot.resolve("bg/../../outside.png").path,
            ),
        )

        assertEquals(listOf("covers", "bg"), directories.map { it.name })
        assertEquals(
            listOf("kept.png", "shared.png"),
            backupRoot.resolve("bg").list()?.sorted(),
        )
        assertEquals("kept", backupRoot.resolve("bg/kept.png").readText())
        assertEquals("shared", backupRoot.resolve("bg/shared.png").readText())
        assertFalse(backupRoot.resolve("bg/orphan.png").exists())
        assertFalse(backupRoot.resolve("bg/outside.png").exists())
        assertTrue(orphan.exists())
    }

    @Test
    fun restoreReplacesDirectoryAfterStagingCompletes() = withTempDirectory { root ->
        val backupRoot = root.resolve("backup")
        val externalRoot = root.resolve("external")
        backupRoot.resolve("covers").mkdirs()
        backupRoot.resolve("covers/new.png").writeText("new")
        externalRoot.resolve("covers").mkdirs()
        externalRoot.resolve("covers/old.png").writeText("old")

        assertTrue(
            restoreBackupMediaDirectory(backupRoot, externalRoot, "covers").getOrThrow()
        )
        assertEquals("new", externalRoot.resolve("covers/new.png").readText())
        assertFalse(externalRoot.resolve("covers/old.png").exists())
        assertFalse(externalRoot.resolve(".covers.restore").exists())
        assertFalse(externalRoot.resolve(".covers.previous").exists())
    }

    @Test
    fun missingBackupDirectoryLeavesCurrentFilesUntouched() = withTempDirectory { root ->
        val backupRoot = root.resolve("backup").apply { mkdirs() }
        val externalRoot = root.resolve("external")
        externalRoot.resolve("bg").mkdirs()
        externalRoot.resolve("bg/current.png").writeText("current")

        assertFalse(
            restoreBackupMediaDirectory(backupRoot, externalRoot, "bg").getOrThrow()
        )
        assertEquals("current", externalRoot.resolve("bg/current.png").readText())
    }

    @Test
    fun interruptedRestoreRecoversPreviousDirectory() = withTempDirectory { root ->
        val backupRoot = root.resolve("backup").apply { mkdirs() }
        val externalRoot = root.resolve("external")
        externalRoot.resolve(".covers.previous").mkdirs()
        externalRoot.resolve(".covers.previous/current.png").writeText("current")

        assertFalse(
            restoreBackupMediaDirectory(backupRoot, externalRoot, "covers").getOrThrow()
        )
        assertEquals("current", externalRoot.resolve("covers/current.png").readText())
        assertFalse(externalRoot.resolve(".covers.previous").exists())
    }

    private fun withTempDirectory(block: (java.io.File) -> Unit) {
        val root = Files.createTempDirectory("backup-media-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
