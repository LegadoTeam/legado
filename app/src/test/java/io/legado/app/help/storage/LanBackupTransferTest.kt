package io.legado.app.help.storage

import com.google.gson.JsonParser
import io.legado.app.utils.GSON
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LanBackupTransferTest {

    @Test
    fun `descriptor uses stable protocol field names`() {
        val descriptor = LanBackupDescriptor(
            hosts = listOf("192.168.1.2"),
            port = 12345,
            token = "01".repeat(16),
            key = "02".repeat(32),
            iv = "03".repeat(12),
            size = 17,
            sha256 = "04".repeat(32),
            deviceName = "test",
            expiresAt = 10_000,
        )

        val json = GSON.toJson(descriptor)

        val jsonObject = JsonParser.parseString(json).asJsonObject
        assertEquals(LAN_BACKUP_PROTOCOL, jsonObject.get("protocol").asString)
        assertEquals("test", jsonObject.get("deviceName").asString)
        assertEquals(
            descriptor,
            LanBackupTransfer.decodeDescriptor(json, now = 1_000).getOrThrow(),
        )
        assertTrue(
            LanBackupTransfer.validateDescriptor(
                descriptor.copy(size = LAN_BACKUP_MAX_ENCRYPTED_BYTES + 1),
                now = 1_000,
            ).isFailure
        )
    }

    @Test
    fun `encrypted backup requires the paired token`() = withTempDirectory { directory ->
        val input = File(directory, "backup.zip").apply { writeText("backup payload") }
        val encrypted = File(directory, "backup.enc")
        val decrypted = File(directory, "restored.zip")
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(12) { (it + 32).toByte() }

        LanBackupTransfer.encryptBackup(input, encrypted, key, iv, "paired-token")
        LanBackupTransfer.decryptBackup(encrypted, decrypted, key, iv, "paired-token")

        assertArrayEquals(input.readBytes(), decrypted.readBytes())
        assertThrows(Exception::class.java) {
            LanBackupTransfer.decryptBackup(encrypted, decrypted, key, iv, "other-token")
        }
    }

    @Test
    fun `archive validation counts expanded bytes and rejects traversal`() =
        withTempDirectory { directory ->
            val valid = File(directory, "valid.zip")
            writeZip(valid, "config.xml" to "1234", "bookshelf.json" to "123456")

            assertEquals(10L, LanBackupTransfer.validateBackupArchive(valid))
            assertThrows(IllegalArgumentException::class.java) {
                LanBackupTransfer.validateBackupArchive(valid, maxBytes = 9)
            }
            assertThrows(IllegalArgumentException::class.java) {
                LanBackupTransfer.validateBackupArchive(valid, maxEntries = 1)
            }

            val coverArchive = File(directory, "covers.zip")
            writeZip(
                coverArchive,
                *Array(513) { index -> "covers/$index.cover" to "" },
            )
            assertEquals(0L, LanBackupTransfer.validateBackupArchive(coverArchive))

            val traversal = File(directory, "traversal.zip")
            writeZip(traversal, "../outside" to "payload")
            assertThrows(SecurityException::class.java) {
                LanBackupTransfer.validateBackupArchive(traversal)
            }
        }

    @Test
    fun `temporary server permits retry before expiry`() = withTempDirectory { directory ->
        val payload = "encrypted backup".toByteArray()
        val file = File(directory, "backup.enc").apply { writeBytes(payload) }
        val token = "0123456789abcdef0123456789abcdef"
        val server = LanBackupServer(file, token, System.currentTimeMillis() + 60_000)
        server.start(5_000, false)
        try {
            repeat(2) {
                val connection = URL(
                    "http://127.0.0.1:${server.listeningPort}/backup/$token"
                ).openConnection() as HttpURLConnection
                try {
                    assertEquals(HttpURLConnection.HTTP_OK, connection.responseCode)
                    assertArrayEquals(payload, connection.inputStream.use { it.readBytes() })
                } finally {
                    connection.disconnect()
                }
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `settings flow validates then backs up before restore`() {
        val fragment = projectFile(
            "src/main/java/io/legado/app/ui/config/BackupConfigFragment.kt"
        ).readText()
        val flow = fragment.substringAfter("private fun receiveLanBackup")
            .substringBefore("private fun backupIgnore")
        val receiveIndex = flow.indexOf("LanBackupTransfer.receive")
        val backupIndex = flow.indexOf("Backup.backupBeforeLanRestoreLocked")
        val spaceIndex = flow.indexOf("LanBackupTransfer.requireRestoreSpace")
        val restoreIndex = flow.indexOf("Restore.restoreOrThrow")
        assertTrue(receiveIndex >= 0)
        assertTrue(backupIndex >= 0)
        assertTrue(spaceIndex >= 0)
        assertTrue(restoreIndex >= 0)
        assertTrue(receiveIndex < backupIndex)
        assertTrue(backupIndex < spaceIndex)
        assertTrue(spaceIndex < restoreIndex)
        assertTrue(flow.contains("receivedFile?.parentFile?.deleteRecursively()"))
        assertTrue(flow.contains("withContext(IO)"))
        assertTrue(fragment.contains("lanBackupSession?.close()"))
        assertTrue(fragment.contains("override fun onStop()"))

        val backup = projectFile(
            "src/main/java/io/legado/app/help/storage/Backup.kt"
        ).readText()
        assertTrue(backup.contains("variable = book.variable.takeUnless { lanTransfer }"))
        assertTrue(backup.contains("paths.removeAll(listOf(\"servers.json\""))
        assertTrue(backup.contains("DirectLinkUpload.getConfig()?.takeUnless { lanTransfer }"))
        assertTrue(backup.contains("enabledContentKeys.remove(BackupConfig.cookieContentKey)"))
        assertTrue(backup.contains("it == BackupConfig.cookieContentKey"))
        assertTrue(backup.contains("check(\n                    backup("))
        assertTrue(backup.contains("return backupCreated"))
        assertTrue(backup.contains("lan_backup/send/${'$'}{UUID.randomUUID()}"))
        assertTrue(backup.contains("val workingZipFile = File(directory, \"tmp_backup.zip\")"))
        assertTrue(backup.contains("workingZipFile = workingZipFile"))
        assertTrue(backup.contains("ZipUtils.zipFiles(paths, workingZipFile.absolutePath)"))
        assertTrue(backup.contains("File(appCtx.cacheDir, \"lan_backup\").deleteRecursively()"))
        assertTrue(backup.contains("writePreferenceSnapshot(appCtx, backupPath, \"config\")"))
        assertTrue(backup.contains("writePreferenceSnapshot(appCtx, backupPath, \"videoConfig\")"))

        val transfer = projectFile(
            "src/main/java/io/legado/app/help/storage/LanBackupTransfer.kt"
        ).readText()
        assertTrue(transfer.contains("lan_backup/receive/${'$'}{UUID.randomUUID()}"))
        assertTrue(transfer.contains("connection.instanceFollowRedirects = false"))
        assertTrue(transfer.contains("cipher.doFinal()"))
        assertTrue(!transfer.contains("CipherInputStream"))

        val restore = projectFile(
            "src/main/java/io/legado/app/help/storage/Restore.kt"
        ).readText()
        assertTrue(restore.contains("suspend fun restoreOrThrow"))
        assertTrue(restore.contains("if (lanTransfer) book.variable = null"))
        assertTrue(restore.contains("appDb.bookDao.upsertPreservingVariable(book)"))
        assertTrue(restore.contains("lan_backup/restore/${'$'}{UUID.randomUUID()}"))
        assertTrue(restore.contains("extractBackup(context, uri, restorePath)"))
        assertTrue(restore.contains("LanBackupTransfer.requireRestoreMediaSpace"))
        assertTrue(restore.contains("if (lanTransfer) FileUtils.delete(restorePath)"))
        assertTrue(restore.contains("currentCoroutineContext().ensureActive()"))
        assertTrue(restore.contains("withContext(NonCancellable)"))
        assertTrue(restore.contains("readPreferenceSnapshot(appCtx, path, \"config\")"))
        assertTrue(restore.contains("readPreferenceSnapshot(appCtx, path, \"videoConfig\")"))
        assertTrue(restore.contains("!lanTransfer && !BackupConfig.ignoreCookies"))
        assertTrue(restore.contains("key !in lanTransferIgnoredPrefKeys"))
        assertTrue(flow.contains("lanTransfer = true"))

        val bookDao = projectFile(
            "src/main/java/io/legado/app/data/dao/BookDao.kt"
        ).readText()
        assertTrue(bookDao.contains("fun upsertPreservingVariable(book: Book)"))
        assertTrue(bookDao.contains("book.variable = existing.variable"))

        val snapshot = projectFile(
            "src/main/java/io/legado/app/help/storage/PreferenceSnapshot.kt"
        ).readText()
        assertTrue(snapshot.contains("UUID.randomUUID()"))
        assertTrue(snapshot.contains("preferences.edit().clear()"))
        assertTrue(snapshot.contains("check(editor.commit())"))
        assertTrue(snapshot.contains("temporaryFile.copyTo("))
        assertTrue(snapshot.contains("return HashMap(preferences.all)"))
        assertTrue(snapshot.contains("File(\"\${file.absolutePath}.bak\").delete()"))

        val preferences = projectFile(
            "src/main/java/io/legado/app/utils/PreferencesExtensions.kt"
        ).readText()
        assertTrue(preferences.contains("synchronized(objMBase.javaClass)"))
        assertTrue(preferences.contains("val originalPreferencesDir"))
        assertTrue(preferences.contains("fieldMPreferencesDir.set(objMBase, originalPreferencesDir)"))
    }

    private fun writeZip(file: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("lan-backup-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first(File::isFile)
}
