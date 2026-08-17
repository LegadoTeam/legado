package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.BookCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.getSharedPreferences
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.outputStream
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.core.content.edit
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME

internal fun selectedBackupFileNames(isEnabled: (String) -> Boolean): List<String> =
    buildList {
        if (isEnabled(BackupConfig.bookshelfContentKey)) {
            addAll(listOf("bookshelf.json", "bookGroup.json"))
        }
        if (isEnabled(BackupConfig.annotationContentKey)) {
            addAll(listOf("bookmark.json", "highlight.json", "highlightRule.json"))
        }
        if (isEnabled(BackupConfig.sourceContentKey)) {
            addAll(listOf("bookSource.json", "rssSources.json", "rssStar.json", "sourceSub.json"))
        }
        if (isEnabled(BackupConfig.cookieContentKey)) {
            add(BackupConfig.cookieFileName)
        }
        if (isEnabled(BackupConfig.ruleContentKey)) {
            addAll(
                listOf(
                    "replaceRule.json",
                    "txtTocRule.json",
                    "httpTTS.json",
                    "keyboardAssists.json",
                    "dictRule.json",
                    "autoTask.json",
                    "servers.json",
                    DirectLinkUpload.ruleFileName,
                    BookCover.configFileName,
                )
            )
        }
        if (isEnabled(BackupConfig.historyContentKey)) {
            addAll(listOf("readRecord.json", "searchHistory.json"))
        }
        if (isEnabled(BackupConfig.settingContentKey)) {
            addAll(
                listOf(
                    ReadBookConfig.configFileName,
                    ReadBookConfig.shareConfigFileName,
                    ThemeConfig.configFileName,
                    "config.xml",
                    "videoConfig.xml",
                )
            )
        }
    }

/**
 * 备份
 */
object Backup {

    val backupPath: String by lazy {
        appCtx.filesDir.getFile("backup").createFolderIfNotExist().absolutePath
    }
    val zipFilePath = "${appCtx.externalFiles.absolutePath}${File.separator}tmp_backup.zip"

    private const val TAG = "Backup"

    private val mutex = Mutex()

    private fun getNowZipFileName(): String {
        val backupDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        val deviceName = AppConfig.webDavDeviceName
        return if (deviceName?.isNotBlank() == true) {
            "backup${backupDate}-${deviceName}.zip"
        } else {
            "backup${backupDate}.zip"
        }.normalizeFileName()
    }

    private fun shouldBackup(): Boolean {
        val lastBackup = LocalConfig.lastBackup
        return lastBackup + TimeUnit.DAYS.toMillis(1) < System.currentTimeMillis()
    }

    fun autoBack(context: Context) {
        if (!AppConfig.autoBackup) return
        if (shouldBackup()) {
            Coroutine.async {
                mutex.withLock {
                    if (shouldBackup()) {
                        val backupZipFileName = getNowZipFileName()
                        if (!AppWebDav.hasBackUp(backupZipFileName)) {
                            backup(context, AppConfig.backupPath)
                        } else {
                            LocalConfig.lastBackup = System.currentTimeMillis()
                        }
                    }
                }
            }.onError {
                AppLog.put("自动备份失败\n${it.localizedMessage}")
            }
        }
    }

    suspend fun backupLocked(context: Context, path: String?) {
        mutex.withLock {
            withContext(IO) {
                backup(context, path)
            }
        }
    }

    private suspend fun backup(context: Context, path: String?) {
        LogUtils.d(TAG, "开始备份 path:$path")
        val enabledContentKeys = BackupConfig.contentKeys.filterTo(hashSetOf()) {
            BackupConfig.contentIsEnabled(it)
        }
        val password = LocalConfig.password
        if (BackupConfig.cookieContentKey in enabledContentKeys &&
            password.isNullOrBlank()
        ) {
            throw NoStackTraceException(appCtx.getString(R.string.cookie_backup_password_required))
        }
        LocalConfig.lastBackup = System.currentTimeMillis()
        val aes = BackupAES(password)
        FileUtils.delete(backupPath)
        val backupPersistedCovers = BackupConfig.persistedCoverContentKey in enabledContentKeys
        val backupOtherCovers = BackupConfig.otherCoverContentKey in enabledContentKeys
        val backupBackgrounds = BackupConfig.backgroundContentKey in enabledContentKeys
        val readConfigSnapshot = ReadBookConfig.configList.map { it.copy() }
        val shareReadConfigSnapshot = ReadBookConfig.shareConfig.copy()
        val backgroundPaths = if (backupBackgrounds) {
            arrayListOf<String>().apply {
                (readConfigSnapshot + shareReadConfigSnapshot).forEach { config ->
                    if (config.bgType == 2) add(config.bgStr)
                    if (config.bgTypeNight == 2) add(config.bgStrNight)
                    if (config.bgTypeEInk == 2) add(config.bgStrEInk)
                }
            }
        } else {
            emptyList()
        }
        writeListToJson(
            appDb.bookDao.all.map { book ->
                book.copy(
                    persistedCoverUrl = book.persistedCoverUrl
                        .takeIf { backupPersistedCovers },
                )
            },
            "bookshelf.json",
            backupPath,
        )
        writeListToJson(appDb.bookmarkDao.all, "bookmark.json", backupPath)
        writeListToJson(appDb.bookHighlightDao.all, "highlight.json", backupPath)
        writeListToJson(
            appDb.highlightRuleDao.all,
            "highlightRule.json",
            backupPath,
            writeEmpty = true
        )
        writeListToJson(appDb.bookGroupDao.all, "bookGroup.json", backupPath)
        writeListToJson(appDb.bookSourceDao.all, "bookSource.json", backupPath)
        writeListToJson(appDb.rssSourceDao.all, "rssSources.json", backupPath)
        writeListToJson(appDb.rssStarDao.all, "rssStar.json", backupPath)
        writeListToJson(appDb.replaceRuleDao.all, "replaceRule.json", backupPath)
        writeListToJson(appDb.readRecordDao.all, "readRecord.json", backupPath)
        writeListToJson(appDb.searchKeywordDao.all, "searchHistory.json", backupPath)
        writeListToJson(appDb.ruleSubDao.all, "sourceSub.json", backupPath)
        writeListToJson(appDb.txtTocRuleDao.all, "txtTocRule.json", backupPath)
        writeListToJson(appDb.httpTTSDao.all, "httpTTS.json", backupPath)
        writeListToJson(appDb.keyboardAssistsDao.all, "keyboardAssists.json", backupPath)
        writeListToJson(appDb.dictRuleDao.all, "dictRule.json", backupPath)
        writeListToJson(appDb.autoTaskRuleDao.all(), "autoTask.json", backupPath)
        GSON.toJson(appDb.serverDao.all).let { json ->
            aes.runCatching {
                encryptBase64(json)
            }.getOrDefault(json).let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + "servers.json")
                    .writeText(it)
            }
        }
        if (BackupConfig.cookieContentKey in enabledContentKeys) {
            val encryptedCookies = aes.encryptBase64(GSON.toJson(appDb.cookieDao.all))
            FileUtils.createFileIfNotExist(
                backupPath + File.separator + BackupConfig.cookieFileName
            ).writeText(encryptedCookies)
        }
        currentCoroutineContext().ensureActive()
        GSON.toJson(readConfigSnapshot).let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.configFileName)
                .writeText(it)
        }
        GSON.toJson(shareReadConfigSnapshot).let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.shareConfigFileName)
                .writeText(it)
        }
        GSON.toJson(ThemeConfig.configList).let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + ThemeConfig.configFileName)
                .writeText(it)
        }
        DirectLinkUpload.getConfig()?.let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + DirectLinkUpload.ruleFileName)
                .writeText(GSON.toJson(it))
        }
        BookCover.getConfig()?.let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + BookCover.configFileName)
                .writeText(GSON.toJson(it))
        }
        currentCoroutineContext().ensureActive()
        appCtx.getSharedPreferences(backupPath, "config")?.let { sp ->
            val edit = sp.edit()
            appCtx.defaultSharedPreferences.all.forEach { (key, value) ->
                if (BackupConfig.keyIsNotIgnore(key)) {
                    when (key) {
                        PreferKey.webDavPassword -> {
                            edit.putString(key, aes.runCatching {
                                encryptBase64(value.toString())
                            }.getOrDefault(value.toString()))
                        }

                        else -> when (value) {
                            is Int -> edit.putInt(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Long -> edit.putLong(key, value)
                            is Float -> edit.putFloat(key, value)
                            is String -> edit.putString(key, value)
                        }
                    }
                }
            }
            edit.commit()
        }
        currentCoroutineContext().ensureActive()
        appCtx.getSharedPreferences(backupPath, "videoConfig")?.let { sp ->
            sp.edit(commit = true) {
                appCtx.getSharedPreferences(VIDEO_PREF_NAME, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                    when (value) {
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val zipFileName = getNowZipFileName()
        val paths = ArrayList(selectedBackupFileNames(enabledContentKeys::contains))
        for (i in 0 until paths.size) {
            paths[i] = backupPath + File.separator + paths[i]
        }
        paths.addAll(
            prepareBackupMediaDirectories(
                appCtx.externalFiles,
                File(backupPath),
                backgroundPaths,
                backupPersistedCovers,
                backupOtherCovers,
                backupBackgrounds,
            ).map { it.absolutePath }
        )
        FileUtils.delete(zipFilePath)
        FileUtils.delete(zipFilePath.replace("tmp_", ""))
        val backupFileName = if (AppConfig.onlyLatestBackup) {
            "backup.zip"
        } else {
            zipFileName
        }
        if (ZipUtils.zipFiles(paths, zipFilePath)) {
            when {
                path.isNullOrBlank() -> {
                    copyBackup(context.getExternalFilesDir(null)!!, backupFileName)
                }

                path.isContentScheme() -> {
                    copyBackup(context, path.toUri(), backupFileName)
                }

                else -> {
                    copyBackup(File(path), backupFileName)
                }
            }
            try {
                AppWebDav.backUpWebDav(zipFileName)
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    AppLog.put("上传备份至webdav失败\n$e", e)
                }
            }
        }
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
        currentCoroutineContext().ensureActive()
        if (backupBackgrounds) {
            backgroundPaths.map {
                if (it.contains(File.separator)) {
                    File(it)
                } else {
                    appCtx.externalFiles.getFile("bg", it)
                }
            }.let {
                AppWebDav.upBgs(it.toTypedArray())
            }
        }
    }

    private suspend fun writeListToJson(
        list: List<Any>,
        fileName: String,
        path: String,
        writeEmpty: Boolean = false
    ) {
        currentCoroutineContext().ensureActive()
        withContext(IO) {
            if (list.isNotEmpty() || writeEmpty) {
                LogUtils.d(TAG, "阅读备份 $fileName 列表大小 ${list.size}")
                val file = FileUtils.createFileIfNotExist(path + File.separator + fileName)
                file.outputStream().buffered().use {
                    GSON.writeToOutputStream(it, list)
                }
                LogUtils.d(TAG, "阅读备份 $fileName 写入大小 ${file.length()}")
            } else {
                LogUtils.d(TAG, "阅读备份 $fileName 列表为空")
            }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(context: Context, uri: Uri, fileName: String) {
        val treeDoc = DocumentFile.fromTreeUri(context, uri)!!
        treeDoc.findFile(fileName)?.delete()
        val fileDoc = treeDoc.createFile("", fileName)
            ?: throw NoStackTraceException("创建文件失败")
        val outputS = fileDoc.openOutputStream()
            ?: throw NoStackTraceException("打开OutputStream失败")
        outputS.use {
            FileInputStream(zipFilePath).use { inputS ->
                inputS.copyTo(outputS)
            }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(rootFile: File, fileName: String) {
        FileInputStream(File(zipFilePath)).use { inputS ->
            val file = FileUtils.createFileIfNotExist(rootFile, fileName)
            FileOutputStream(file).use { outputS ->
                inputS.copyTo(outputS)
            }
        }
    }

    fun clearCache() {
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
    }
}
