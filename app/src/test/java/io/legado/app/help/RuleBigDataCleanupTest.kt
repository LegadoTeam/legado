package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RuleBigDataCleanupTest {

    @Test
    fun existingKeyLookupUsesBoundedDistinctBatches() {
        val queriedBatches = arrayListOf<List<String>>()
        val keys = (0..900).map { "key-$it" } + listOf("key-0", " key-0 ")

        val existing = findExistingRuleDataKeys(keys) { batch ->
            queriedBatches.add(batch)
            batch.filter { it.endsWith("0") }
        }

        assertEquals(listOf(900, 2), queriedBatches.map { it.size })
        assertEquals(902, queriedBatches.flatten().distinct().size)
        assertTrue("key-0" in existing)
        assertTrue("key-900" in existing)
        assertFalse("key-1" in existing)
    }

    @Test
    fun emptyKeyLookupDoesNotQueryDatabase() {
        var queryCount = 0

        val existing = findExistingRuleDataKeys(emptyList()) {
            queryCount++
            it
        }

        assertTrue(existing.isEmpty())
        assertEquals(0, queryCount)
    }

    @Test
    fun cleanupDeletesInvalidRootsAndKeepsEntriesRevivedDuringScan() {
        val root = Files.createTempDirectory("rule-data-cleanup").toFile()
        try {
            val accessLock = Any()
            val unexpectedFile = File(root, "unexpected.tmp").apply { writeText("invalid") }
            val validDirectory = createRuleDataDirectory(root, "valid", "valid")
            val invalidDirectory = createRuleDataDirectory(root, "invalid", "missing")
            File(invalidDirectory, "nested/value.txt").apply {
                parentFile?.mkdirs()
                writeText("stale")
            }
            val unmarkedDirectory = File(root, "unmarked").apply { mkdirs() }
            File(unmarkedDirectory, "value.txt").writeText("stale")
            val appearedDirectory = File(root, "appeared").apply { mkdirs() }
            val changedDirectory = createRuleDataDirectory(root, "changed", "old")
            val unreadableDirectory = File(root, "unreadable").apply {
                mkdirs()
                File(this, "origin.txt").mkdirs()
            }
            val queriedKeys = arrayListOf<String>()
            val recheckedKeys = arrayListOf<String>()

            clearInvalidRuleData(
                dataDir = root,
                markerFileName = "origin.txt",
                findExistingKeys = { batch ->
                    queriedKeys.addAll(batch)
                    File(appearedDirectory, "origin.txt").writeText("appeared-valid")
                    File(changedDirectory, "origin.txt").writeText("changed-valid")
                    batch.filter { it == "valid" }
                },
                existsNow = { key ->
                    recheckedKeys.add(key)
                    key == "appeared-valid" || key == "changed-valid"
                },
                accessLock = accessLock,
            )

            assertFalse(unexpectedFile.exists())
            assertTrue(validDirectory.exists())
            assertFalse(invalidDirectory.exists())
            assertFalse(unmarkedDirectory.exists())
            assertTrue(appearedDirectory.exists())
            assertTrue(changedDirectory.exists())
            assertTrue(unreadableDirectory.exists())
            assertEquals(setOf("valid", "missing", "old"), queriedKeys.toSet())
            assertEquals(
                setOf("missing", "appeared-valid", "changed-valid"),
                recheckedKeys.toSet(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupSerializesFinalDeleteWithRuleDataWrites() {
        val root = Files.createTempDirectory("rule-data-cleanup-lock").toFile()
        try {
            val accessLock = Any()
            val directory = createRuleDataDirectory(root, "candidate", "missing")
            val recheckStarted = CountDownLatch(1)
            val finishRecheck = CountDownLatch(1)
            val writerStarted = CountDownLatch(1)
            val writerFinished = CountDownLatch(1)
            val cleanupThread = thread(start = true) {
                clearInvalidRuleData(
                    dataDir = root,
                    markerFileName = "origin.txt",
                    findExistingKeys = { emptyList() },
                    existsNow = {
                        recheckStarted.countDown()
                        assertTrue(finishRecheck.await(5, TimeUnit.SECONDS))
                        false
                    },
                    accessLock = accessLock,
                )
            }

            assertTrue(recheckStarted.await(5, TimeUnit.SECONDS))
            val writerThread = thread(start = true) {
                writerStarted.countDown()
                synchronized(accessLock) {
                    createRuleDataDirectory(root, "candidate", "restored")
                    File(directory, "value.txt").writeText("current")
                }
                writerFinished.countDown()
            }

            assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
            assertFalse(writerFinished.await(100, TimeUnit.MILLISECONDS))
            finishRecheck.countDown()
            cleanupThread.join(5_000)
            writerThread.join(5_000)

            assertFalse(cleanupThread.isAlive)
            assertFalse(writerThread.isAlive)
            assertTrue(writerFinished.await(0, TimeUnit.MILLISECONDS))
            assertEquals("restored", File(directory, "origin.txt").readText())
            assertEquals("current", File(directory, "value.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createRuleDataDirectory(root: File, name: String, key: String): File {
        return File(root, name).apply {
            mkdirs()
            File(this, "origin.txt").writeText(key)
        }
    }
}
