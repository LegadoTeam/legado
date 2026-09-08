package io.legado.app.help.storage

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupRestoreConcurrencyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val source = BookSource("https://restore-$id.example", "Restored source $id")
    private val book = Book(bookUrl = "${source.bookSourceUrl}/book", origin = source.bookSourceUrl,
        name = "Restored book $id", author = "Backup regression", durChapterIndex = 12)
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val savedLastBackup = LocalConfig.lastBackup
    private lateinit var input: File
    private var output: File? = null

    @Before fun setUp() {
        BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = true }
        BackupConfig.ignoreConfig[BackupConfig.bookshelfContentKey] = false
        BackupConfig.ignoreConfig[BackupConfig.sourceContentKey] = false
        LocalConfig.lastBackup = 0
        input = File.createTempFile("restore-race-", ".zip", context.cacheDir)
        ZipOutputStream(input.outputStream()).use { zip ->
            for ((name, content) in mapOf("bookshelf.json" to listOf(book), "bookSource.json" to listOf(source))) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(GSON.toJson(content).toByteArray())
                zip.closeEntry()
            }
        }
    }

    @After fun cleanUp() {
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        input.delete()
        output?.parentFile?.deleteRecursively()
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
        LocalConfig.lastBackup = savedLastBackup
    }

    @Test fun firstLocalRestoreSurvivesConcurrentBackupAndStartupCleanup(): Unit = runBlocking {
        withTimeout(30_000) {
            // Hold an active operation while real restore/backup calls queue behind it.
            backupRestoreMutex.lock()
            val sentinel = File(Backup.backupPath, "active-$id").apply {
                parentFile!!.mkdirs()
                writeText("active operation")
            }
            val restore = async(start = CoroutineStart.UNDISPATCHED) { Restore.restore(context, input.toUri()) }
            val backup = async(start = CoroutineStart.UNDISPATCHED) {
                Backup.backupForLanTransferLocked(context).also { output = it }
            }
            try {
                assertFalse(restore.isCompleted)
                assertFalse(backup.isCompleted)
                assertFalse(appDb.bookDao.has(book.bookUrl))
                assertFalse(appDb.bookSourceDao.has(source.bookSourceUrl))
                Backup.clearCache()
                assertEquals("active operation", sentinel.readText())
            } finally {
                backupRestoreMutex.unlock()
            }
            restore.await()
            assertRestored()
            assertTrue(LocalConfig.lastBackup > 0)
            ZipFile(backup.await()).use { zip ->
                val books = zip.getInputStream(checkNotNull(zip.getEntry("bookshelf.json")))
                    .bufferedReader().use { GSON.fromJsonArray<Book>(it.readText()).getOrThrow() }
                val sources = zip.getInputStream(checkNotNull(zip.getEntry("bookSource.json")))
                    .bufferedReader().use { GSON.fromJsonArray<BookSource>(it.readText()).getOrThrow() }
                assertEquals(12, books.single { it.bookUrl == book.bookUrl }.durChapterIndex)
                assertEquals(source.bookSourceName, sources.single { it.bookSourceUrl == source.bookSourceUrl }.bookSourceName)
            }
        }
    }

    @Test fun cancellingQueuedRestoreDoesNotExtractOrBlockTheNextRestore(): Unit = runBlocking {
        withTimeout(30_000) {
            backupRestoreMutex.lock()
            val sentinel = File(Backup.backupPath, "cancel-$id").apply {
                parentFile!!.mkdirs()
                writeText("keep staging")
            }
            try {
                val restore = async(start = CoroutineStart.UNDISPATCHED) {
                    Restore.restoreOrThrow(context, input.toUri())
                }
                restore.cancelAndJoin()
                assertEquals("keep staging", sentinel.readText())
                assertFalse(appDb.bookDao.has(book.bookUrl))
                assertEquals(0L, LocalConfig.lastBackup)
            } finally {
                sentinel.delete()
                backupRestoreMutex.unlock()
            }
            Restore.restoreOrThrow(context, input.toUri(), lanTransfer = true)
            assertRestored()
            assertTrue(LocalConfig.lastBackup > 0)
        }
    }

    private fun assertRestored() {
        assertEquals(12, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals(source.bookSourceName, appDb.bookSourceDao.getBookSource(source.bookSourceUrl)!!.bookSourceName)
    }
}
