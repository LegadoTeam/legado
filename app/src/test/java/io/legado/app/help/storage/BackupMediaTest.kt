package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BackupMediaTest {

    @Test
    fun restoreMediaSpaceCountsCurrentAndIncomingSelectedDirectories() =
        withTempDirectory { root ->
            val backupRoot = root.resolve("backup").apply { mkdirs() }
            val externalRoot = root.resolve("external").apply { mkdirs() }
            backupRoot.resolve("covers/incoming.cover").apply {
                parentFile?.mkdirs()
                writeText("1234")
            }
            externalRoot.resolve("covers/current.cover").apply {
                parentFile?.mkdirs()
                writeText("123456")
            }
            externalRoot.resolve("bg/current.png").apply {
                parentFile?.mkdirs()
                writeText("ignored")
            }

            assertEquals(
                10L,
                requiredRestoreMediaBytes(backupRoot, externalRoot, listOf("covers", "bg")),
            )
        }

    @Test
    fun selectedBackupFilesFollowContentGroups() {
        assertEquals(
            listOf(
                "bookshelf.json",
                "bookGroup.json",
                "readRecord.json",
                "searchHistory.json",
            ),
            selectedBackupFileNames {
                it == "backupBookshelf" || it == "backupHistory"
            },
        )

        val allFiles = selectedBackupFileNames { true }
        assertEquals(
            listOf(
                "bookshelf.json",
                "bookGroup.json",
                "bookmark.json",
                "highlight.json",
                "highlightRule.json",
                "bookSource.json",
                "rssSources.json",
                "rssStar.json",
                "sourceSub.json",
                "cookies.json",
                "runtimeSourceCache.json",
                "replaceRule.json",
                "txtTocRule.json",
                "httpTTS.json",
                "keyboardAssists.json",
                "dictRule.json",
                "autoTask.json",
                "servers.json",
                "directLinkUploadRule.json",
                "coverRule.json",
                "readRecord.json",
                "searchHistory.json",
                "readConfig.json",
                "shareReadConfig.json",
                "themeConfig.json",
                "config.xml",
                "videoConfig.xml",
            ),
            allFiles,
        )
    }

    @Test
    fun cookiesAreOptInBackupContent() {
        assertEquals(
            listOf("cookies.json"),
            selectedBackupFileNames { it == "backupCookies" },
        )
    }

    @Test
    fun sourceVariablesAreOptInBackupContent() {
        assertEquals(
            listOf("runtimeSourceCache.json"),
            selectedBackupFileNames { it == "backupSourceVariables" },
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
    fun coverCategoriesArePreparedIndependently() = withTempDirectory { root ->
        val externalRoot = root.resolve("external")
        val backupRoot = root.resolve("backup").apply { mkdirs() }
        backupRoot.resolve("bg/stale.png").apply {
            parentFile?.mkdirs()
            writeText("stale")
        }
        val covers = externalRoot.resolve("covers").apply { mkdirs() }
        val persisted = covers.resolve("0123456789abcdef0123456789abcdef.cover")
            .apply { writeText("persisted") }
        val custom = covers.resolve("custom.png").apply { writeText("custom") }

        prepareBackupMediaDirectories(
            externalRoot,
            backupRoot,
            emptyList(),
            includePersistedCovers = false,
            includeOtherCovers = true,
            includeBackgrounds = false,
        )
        assertFalse(backupRoot.resolve("covers/${persisted.name}").exists())
        assertEquals("custom", backupRoot.resolve("covers/${custom.name}").readText())

        prepareBackupMediaDirectories(
            externalRoot,
            backupRoot,
            emptyList(),
            includePersistedCovers = true,
            includeOtherCovers = false,
            includeBackgrounds = false,
        )
        assertEquals("persisted", backupRoot.resolve("covers/${persisted.name}").readText())
        assertFalse(backupRoot.resolve("covers/${custom.name}").exists())
        assertFalse(backupRoot.resolve("bg").exists())
    }

    @Test
    fun `restored cover paths follow the current external files directory`() =
        withTempDirectory { root ->
            val backupRoot = root.resolve("backup").apply { mkdirs() }
            backupRoot.resolve("covers/hash.cover").apply {
                parentFile?.mkdirs()
                writeText("cover")
            }
            val externalRoot = root.resolve("external")
            val oldPath = root.resolve("old/external/covers/hash.cover").absolutePath

            assertEquals(
                externalRoot.resolve("covers/hash.cover").absolutePath,
                remapRestoredCoverPath(oldPath, backupRoot, externalRoot),
            )
            assertEquals(
                "https://images.example/covers/hash.cover",
                remapRestoredCoverPath(
                    "https://images.example/covers/hash.cover",
                    backupRoot,
                    externalRoot,
                ),
            )

            backupRoot.resolve("covers/hash.cover").delete()
            externalRoot.resolve("covers/hash.cover").apply {
                parentFile?.mkdirs()
                writeText("current")
            }
            assertEquals(
                externalRoot.resolve("covers/hash.cover").absolutePath,
                remapRestoredCoverPath(oldPath, backupRoot, externalRoot),
            )
            externalRoot.resolve("covers/hash.cover").delete()
            assertNull(remapRestoredCoverPath(oldPath, backupRoot, externalRoot))
        }

    @Test
    fun restoreMergesDirectoryAfterStagingCompletes() = withTempDirectory { root ->
        val backupRoot = root.resolve("backup")
        val externalRoot = root.resolve("external")
        backupRoot.resolve("covers").mkdirs()
        backupRoot.resolve("covers/new.png").writeText("new")
        backupRoot.resolve("covers/shared.png").writeText("backup")
        externalRoot.resolve("covers").mkdirs()
        externalRoot.resolve("covers/old.png").writeText("old")
        externalRoot.resolve("covers/shared.png").writeText("current")

        assertTrue(
            restoreBackupMediaDirectory(backupRoot, externalRoot, "covers").getOrThrow()
        )
        assertEquals("new", externalRoot.resolve("covers/new.png").readText())
        assertEquals("old", externalRoot.resolve("covers/old.png").readText())
        assertEquals("backup", externalRoot.resolve("covers/shared.png").readText())
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

    @Test
    fun retryMergesPreviousCurrentAndBackupDirectories() = withTempDirectory { root ->
        val backupRoot = root.resolve("backup")
        val externalRoot = root.resolve("external")
        backupRoot.resolve("covers").mkdirs()
        backupRoot.resolve("covers/backup.png").writeText("backup")
        backupRoot.resolve("covers/shared.png").writeText("backup")
        externalRoot.resolve(".covers.previous").mkdirs()
        externalRoot.resolve(".covers.previous/previous.png").writeText("previous")
        externalRoot.resolve(".covers.previous/shared.png").writeText("previous")
        externalRoot.resolve("covers").mkdirs()
        externalRoot.resolve("covers/current.png").writeText("current")
        externalRoot.resolve("covers/shared.png").writeText("current")

        assertTrue(
            restoreBackupMediaDirectory(backupRoot, externalRoot, "covers").getOrThrow()
        )
        assertEquals("previous", externalRoot.resolve("covers/previous.png").readText())
        assertEquals("current", externalRoot.resolve("covers/current.png").readText())
        assertEquals("backup", externalRoot.resolve("covers/backup.png").readText())
        assertEquals("backup", externalRoot.resolve("covers/shared.png").readText())
        assertFalse(externalRoot.resolve(".covers.previous").exists())
    }

    @Test
    fun retryMergesPreviousAndCurrentWithoutBackupMedia() = withTempDirectory { root ->
        val backupRoot = root.resolve("backup").apply { mkdirs() }
        val externalRoot = root.resolve("external")
        externalRoot.resolve(".covers.previous").mkdirs()
        externalRoot.resolve(".covers.previous/previous.png").writeText("previous")
        externalRoot.resolve("covers").mkdirs()
        externalRoot.resolve("covers/current.png").writeText("current")

        assertTrue(
            restoreBackupMediaDirectory(backupRoot, externalRoot, "covers").getOrThrow()
        )
        assertEquals("previous", externalRoot.resolve("covers/previous.png").readText())
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
