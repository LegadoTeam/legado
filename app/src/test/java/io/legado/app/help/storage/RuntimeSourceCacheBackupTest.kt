package io.legado.app.help.storage

import io.legado.app.data.entities.Cache
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeSourceCacheBackupTest {

    @Test
    fun parserAcceptsLegacyPlaintextAndKeepsLatestValuePerKey() {
        val json = """
            [
              {"key":"sourceVariable_demo","value":"old","deadline":0},
              {"key":"v_demo_token","value":"42","deadline":123},
              {"key":"sourceVariable_demo","value":"new","deadline":0}
            ]
        """.trimIndent()

        assertEquals(
            listOf(
                Cache("sourceVariable_demo", "new", 0),
                Cache("v_demo_token", "42", 123),
            ),
            parseRuntimeSourceCacheBackup(json),
        )
    }

    @Test
    fun backupPayloadIsEncryptedWithTheLocalPassword() {
        val caches = listOf(Cache("userInfo_demo", "secret-token", 0))
        val plaintext = GSON.toJson(caches)
        val aes = BackupAES("runtime-password")
        val encrypted = aes.encryptBase64(plaintext)

        assertFalse(encrypted.contains("secret-token"))
        assertEquals(caches, parseRuntimeSourceCacheBackup(aes.decryptStr(encrypted)))
    }

    @Test
    fun parserRejectsUnknownOrUnsafeEntries() {
        assertThrows(IllegalArgumentException::class.java) {
            parseRuntimeSourceCacheBackup(
                """[{"key":"ordinary-cache","value":"x","deadline":0}]"""
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseRuntimeSourceCacheBackup(
                """[{"key":"v_demo","value":"x","deadline":-1}]"""
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseRuntimeSourceCacheBackup(
                """[{"key":"v_demo","value":{"token":"x"},"deadline":0}]"""
            )
        }
    }

    @Test
    fun runtimeBackupContractIsOptInEncryptedMergeAndLanSafe() {
        val backup = projectFile(
            "src/main/java/io/legado/app/help/storage/Backup.kt"
        ).readText()
        val restore = projectFile(
            "src/main/java/io/legado/app/help/storage/Restore.kt"
        ).readText()
        val config = projectFile(
            "src/main/java/io/legado/app/help/storage/BackupConfig.kt"
        ).readText()
        val dao = projectFile("src/main/java/io/legado/app/data/dao/CacheDao.kt").readText()

        assertTrue(config.contains("runtimeSourceCacheContentKey = \"backupSourceVariables\""))
        assertTrue(config.contains("runtimeSourceCacheIgnoreKey = \"ignoreSourceVariables\""))
        assertTrue(config.contains("runtimeSourceCacheFileName = \"runtimeSourceCache.json\""))
        assertTrue(backup.contains("aes.encryptBase64(GSON.toJson(runtimeCaches))"))
        assertTrue(backup.contains("enabledContentKeys.remove(BackupConfig.runtimeSourceCacheContentKey)"))
        assertTrue(restore.contains("!lanTransfer &&"))
        assertTrue(restore.contains("!BackupConfig.ignoreSourceVariables"))
        assertTrue(restore.contains("raw.isJsonArray()"))
        assertTrue(restore.contains("aes.decryptStr(raw)"))
        assertTrue(restore.contains("appDb.cacheDao.insert(*caches.toTypedArray())"))
        assertFalse(restore.contains("deleteAllRuntimeSourceCaches()"))
        assertTrue(dao.contains("fun getRuntimeSourceCaches(now: Long): List<Cache>"))
        assertTrue(dao.contains("'userInfo_'"))
        assertTrue(dao.contains("'sourceVariable_'"))
        assertTrue(dao.contains("'infoMap_'"))
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first(File::isFile)
}
