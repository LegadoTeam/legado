package io.legado.app.help.storage

import io.legado.app.data.entities.Cookie
import io.legado.app.help.http.CookieStore
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class CookieBackupCompatibilityTest {

    @Test
    fun persistentCookiesRoundTripAsJson() {
        val cookies = listOf(
            Cookie("example.com", "sid=one"),
            Cookie("another.example", "token=two"),
        )

        assertEquals(
            cookies,
            GSON.fromJsonArray<Cookie>(GSON.toJson(cookies)).getOrThrow(),
        )
    }

    @Test
    fun restoredCookieValuesOverrideByNameAndPreserveOthers() {
        val merged = CookieStore.mergeCookieValues(
            "sid=old; keep=yes",
            "sid=new; added=one",
        )

        assertEquals(
            mapOf("sid" to "new", "keep" to "yes", "added" to "one"),
            CookieStore.cookieToMap(merged),
        )
    }

    @Test
    fun cookieBackupEncryptionUsesProvidedPassword() {
        val aes = BackupAES("backup-password")
        val json = """[{"url":"example.com","cookie":"sid=one"}]"""

        assertEquals(json, aes.decryptStr(aes.encryptBase64(json)))
    }

    @Test
    fun cookieBackupParserRejectsNullFieldsBeforeRestore() {
        assertEquals(
            listOf(Cookie("example.com", "sid=one")),
            parseCookieBackup("""[{"url":"example.com","cookie":"sid=one"}]"""),
        )
        assertThrows(IllegalArgumentException::class.java) {
            parseCookieBackup("""[{"url":null,"cookie":"sid=one"}]""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseCookieBackup("""[{"url":"example.com","cookie":null}]""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseCookieBackup("""[{"url":1,"cookie":"sid=one"}]""")
        }
    }

    @Test
    fun cookieBackupIsEncryptedAndRestoresThroughCookieStore() {
        val backup = projectFile("src/main/java/io/legado/app/help/storage/Backup.kt").readText()
        val restore = projectFile("src/main/java/io/legado/app/help/storage/Restore.kt").readText()
        val cookieStore = projectFile(
            "src/main/java/io/legado/app/help/http/CookieStore.kt"
        ).readText()
        val mainActivity = projectFile(
            "src/main/java/io/legado/app/ui/main/MainActivity.kt"
        ).readText()
        val mainViewModel = projectFile(
            "src/main/java/io/legado/app/ui/main/MainViewModel.kt"
        ).readText()
        val config = projectFile(
            "src/main/java/io/legado/app/help/storage/BackupConfig.kt"
        ).readText()
        assertTrue(backup.contains("private suspend fun backup("))
        assertTrue(restore.contains("private suspend fun restore("))
        assertTrue(mainActivity.contains("private fun backupSync()"))
        assertTrue(mainViewModel.contains("fun restoreWebDav("))
        val backupFunction = backup.substringAfter("private suspend fun backup(")
            .substringBefore("private suspend fun writeListToJson")
        val restoreFunction = restore.substringAfter("private suspend fun restore(")
            .substringBefore("private inline fun <reified T> fileToListT")
        val backupSync = mainActivity.substringAfter("private fun backupSync()")
            .substringBefore("override fun onSaveInstanceState")
        val autoRestore = mainViewModel.substringAfter("fun restoreWebDav(")
            .substringBefore("private fun deleteNotShelfBook")
        val contentIsEnabled = config.substringAfter("internal fun contentIsEnabled(")
            .substringBefore("fun saveIgnoreConfig")

        assertTrue(backupFunction.contains("aes.encryptBase64(GSON.toJson(appDb.cookieDao.all))"))
        assertTrue(backupFunction.contains("BackupConfig.cookieContentKey in enabledContentKeys"))
        assertTrue(backupFunction.contains("val password = LocalConfig.password"))
        assertTrue(backupFunction.contains("password.isNullOrBlank()"))
        assertTrue(backupFunction.contains("val aes = BackupAES(password)"))
        assertTrue(
            backupFunction.indexOf("password.isNullOrBlank()") <
                backupFunction.indexOf("LocalConfig.lastBackup = System.currentTimeMillis()")
        )
        assertTrue(restoreFunction.contains("!BackupConfig.ignoreCookies"))
        assertTrue(restoreFunction.contains("val password = LocalConfig.password"))
        assertTrue(restoreFunction.contains("password.isNullOrBlank()"))
        assertTrue(restoreFunction.contains("val aes = BackupAES(password)"))
        assertTrue(restoreFunction.contains("aes.decryptStr(file.readText())"))
        assertTrue(
            restoreFunction.indexOf("val restoredCookies") <
                restoreFunction.indexOf("fileToListT<Book>")
        )
        assertTrue(restoreFunction.contains("CookieStore.restoreCookie(cookie.url, cookie.cookie)"))
        assertTrue(cookieStore.contains("internal fun restoreCookie"))
        assertTrue(cookieStore.contains("private fun saveCookie"))
        val saveCookie = cookieStore.substringAfter("private fun saveCookie").substringBefore(
            "fun setWebCookie"
        )
        assertTrue(saveCookie.contains("appDb.cookieDao.insert"))
        assertTrue(saveCookie.contains("CacheManager.putMemory"))
        assertTrue(
            saveCookie.indexOf("appDb.cookieDao.insert") <
                saveCookie.indexOf("CacheManager.putMemory")
        )
        assertTrue(backupSync.contains("cancelButton {"))
        assertTrue(backupSync.contains("LocalConfig.lastBackup = maxOf("))
        assertTrue(
            backupSync.contains(
                "viewModel.restoreWebDav("
            )
        )
        assertTrue(backupSync.contains("lastBackupFile.lastModify,"))
        assertTrue(autoRestore.contains("}.onSuccess {"))
        assertTrue(
            autoRestore.contains(
                "LocalConfig.lastBackup = maxOf(LocalConfig.lastBackup, restoredLastBackup)"
            )
        )
        assertTrue(autoRestore.contains("executeLazy {"))
        assertTrue(autoRestore.contains("}.onError {"))
        assertTrue(autoRestore.contains("}.start()"))
        assertTrue(contentIsEnabled.contains("if (key == cookieContentKey)"))
        assertTrue(contentIsEnabled.contains("ignoreConfig[key] == false"))
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first(File::isFile)
}
