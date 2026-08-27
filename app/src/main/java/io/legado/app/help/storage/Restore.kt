package io.legado.app.help.storage

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.JsonElement
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppConst.androidId
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.Cache
import io.legado.app.data.entities.Cookie
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppCacheManager
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.HighlightStyle
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.normalizeLegacyPersistedCover
import io.legado.app.help.book.upType
import io.legado.app.help.config.BookshelfReadProgressMode
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.lib.theme.WallpaperTheme
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.model.BookCover
import io.legado.app.model.localBook.LocalBook
import io.legado.app.service.AutoTaskScheduler
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.GSONStrict
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.openInputStream
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.util.UUID

internal fun parseCookieBackup(json: String): List<Cookie> {
    return GSONStrict.fromJsonArray<JsonElement>(json).getOrThrow().map { element ->
        require(element.isJsonObject)
        val url = element.asJsonObject.get("url")
        val cookie = element.asJsonObject.get("cookie")
        require(url != null && url.isJsonPrimitive && url.asJsonPrimitive.isString)
        require(cookie != null && cookie.isJsonPrimitive && cookie.asJsonPrimitive.isString)
        Cookie(url.asString.also { require(it.isNotBlank()) }, cookie.asString)
    }
}

private val runtimeSourceCachePrefixes = arrayOf(
    "v_",
    "userInfo_",
    "loginHeader_",
    "sourceVariable_",
    "infoMap_",
)

internal fun isRuntimeSourceCacheKey(key: String): Boolean {
    return runtimeSourceCachePrefixes.any { prefix ->
        key.startsWith(prefix) && key.length > prefix.length
    }
}

internal fun parseRuntimeSourceCacheBackup(json: String): List<Cache> {
    val latestByKey = linkedMapOf<String, Cache>()
    GSONStrict.fromJsonArray<JsonElement>(json).getOrThrow().forEach { element ->
        require(element.isJsonObject)
        val objectElement = element.asJsonObject
        val keyElement = objectElement.get("key")
        require(keyElement != null && keyElement.isJsonPrimitive && keyElement.asJsonPrimitive.isString)
        val key = keyElement.asString
        require(isRuntimeSourceCacheKey(key))

        val valueElement = objectElement.get("value")
        require(
            valueElement != null && (valueElement.isJsonNull ||
                (valueElement.isJsonPrimitive && valueElement.asJsonPrimitive.isString))
        )
        val deadlineElement = objectElement.get("deadline")
        require(
            deadlineElement != null && deadlineElement.isJsonPrimitive &&
                deadlineElement.asJsonPrimitive.isNumber
        )
        val deadline = deadlineElement.asJsonPrimitive.asString.toLongOrNull()
            ?: throw IllegalArgumentException("deadline must be an integer")
        require(deadline >= 0L)
        latestByKey[key] = Cache(
            key = key,
            value = if (valueElement.isJsonNull) null else valueElement.asString,
            deadline = deadline,
        )
    }
    return latestByKey.values.toList()
}

/**
 * 恢复
 */
object Restore {

    private val mutex = Mutex()

    private const val TAG = "Restore"

    suspend fun restore(context: Context, uri: Uri) {
        LogUtils.d(TAG, "开始恢复备份 uri:$uri")
        kotlin.runCatching {
            extractBackup(context, uri)
        }.onFailure {
            AppLog.put("复制解压文件出错\n${it.localizedMessage}", it)
            return
        }
        kotlin.runCatching {
            restoreLocked(Backup.backupPath)
            LocalConfig.lastBackup = System.currentTimeMillis()
        }.onFailure {
            appCtx.toastOnUi("恢复备份出错\n${it.localizedMessage}")
            AppLog.put("恢复备份出错\n${it.localizedMessage}", it)
        }
    }

    suspend fun restoreOrThrow(
        context: Context,
        uri: Uri,
        lanTransfer: Boolean = false,
    ) {
        LogUtils.d(TAG, "开始恢复备份 uri:$uri")
        val restorePath = if (lanTransfer) {
            File(context.cacheDir, "lan_backup/restore/${UUID.randomUUID()}").absolutePath
        } else {
            Backup.backupPath
        }
        try {
            extractBackup(context, uri, restorePath)
            if (lanTransfer) {
                LanBackupTransfer.requireRestoreMediaSpace(
                    context,
                    File(restorePath),
                    includeBackgrounds = !BackupConfig.ignoreReadConfig,
                )
            }
            restoreLocked(restorePath, lanTransfer)
            LocalConfig.lastBackup = System.currentTimeMillis()
        } finally {
            if (lanTransfer) FileUtils.delete(restorePath)
        }
    }

    private fun extractBackup(
        context: Context,
        uri: Uri,
        targetPath: String = Backup.backupPath,
    ) {
        FileUtils.delete(targetPath)
        if (uri.isContentScheme()) {
            DocumentFile.fromSingleUri(context, uri)!!.openInputStream()!!.use {
                ZipUtils.unZipToPath(it, targetPath)
            }
        } else {
            ZipUtils.unZipToPath(File(uri.path!!), targetPath)
        }
    }

    suspend fun restoreLocked(path: String, lanTransfer: Boolean = false) {
        mutex.withLock {
            if (lanTransfer) {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    restore(path, lanTransfer = true)
                }
            } else {
                restore(path)
            }
        }
    }

    private suspend fun restore(path: String, lanTransfer: Boolean = false) {
        val password = LocalConfig.password
        val aes = BackupAES(password)
        val backupRoot = File(path)
        val restoredPreferences = readPreferenceSnapshot(appCtx, path, "config")
        val restoredVideoPreferences = readPreferenceSnapshot(appCtx, path, "videoConfig")
        val restoredCookies = if (!lanTransfer && !BackupConfig.ignoreCookies) {
            File(path, BackupConfig.cookieFileName).takeIf { it.exists() }?.let { file ->
                if (password.isNullOrBlank()) {
                    throw NoStackTraceException(
                        appCtx.getString(R.string.cookie_backup_password_required)
                    )
                }
                parseCookieBackup(aes.decryptStr(file.readText()))
            }
        } else {
            null
        }
        val restoredRuntimeSourceCaches = if (!lanTransfer &&
            !BackupConfig.ignoreSourceVariables
        ) {
            File(path, BackupConfig.runtimeSourceCacheFileName).takeIf { it.exists() }?.let { file ->
                if (password.isNullOrBlank()) {
                    throw NoStackTraceException(
                        appCtx.getString(R.string.source_variables_backup_password_required)
                    )
                }
                val raw = file.readText()
                val json = if (raw.isJsonArray()) {
                    raw
                } else {
                    aes.decryptStr(raw)
                }
                parseRuntimeSourceCacheBackup(json)
            }
        } else {
            null
        }
        val restoredAutoTasks = fileToListT<AutoTaskRule>(path, "autoTask.json")
        fileToListT<Book>(path, "bookshelf.json")?.let {
            it.forEach { book ->
                if (lanTransfer) book.variable = null
                book.upType()
                book.normalizeLegacyPersistedCover()
                book.customCoverUrl = book.customCoverUrl?.let { coverPath ->
                    remapRestoredCoverPath(coverPath, backupRoot, appCtx.externalFiles)
                }
                book.persistedCoverUrl = book.persistedCoverUrl?.let { coverPath ->
                    remapRestoredCoverPath(coverPath, backupRoot, appCtx.externalFiles)
                }
            }
            it.filter { book -> book.isLocal }
                .forEach { book ->
                    book.coverUrl = LocalBook.getCoverPath(book)
                }
            val newBooks = arrayListOf<Book>()
            val ignoreLocalBook = BackupConfig.ignoreLocalBook
            it.forEach { book ->
                if (ignoreLocalBook && book.isLocal) {
                    return@forEach
                }
                if (lanTransfer) {
                    appDb.bookDao.upsertPreservingVariable(book)
                    return@forEach
                }
                if (appDb.bookDao.has(book.bookUrl)) {
                    try {
                        appDb.bookDao.update(book)
                    } catch (_: SQLiteConstraintException) {
                        appDb.bookDao.insert(book)
                    }
                } else {
                    newBooks.add(book)
                }
            }
            appDb.bookDao.insert(*newBooks.toTypedArray())
        }
        fileToListT<Bookmark>(path, "bookmark.json")?.let {
            appDb.bookmarkDao.insert(*it.toTypedArray())
        }
        fileToListT<BookHighlight>(path, "highlight.json")?.let { highlights ->
            kotlin.runCatching {
                applyLegacyHighlightStyles(File(path, "highlight.json").readText(), highlights)
                applyLegacyHighlightOwners(highlights)
                appDb.bookHighlightDao.insert(*highlights.toTypedArray())
            }.onFailure {
                AppLog.put("恢复高亮出错\n${it.localizedMessage}", it)
            }
        }
        fileToListT<HighlightRule>(path, "highlightRule.json")?.let { rules ->
            kotlin.runCatching {
                appDb.highlightRuleDao.replaceAll(rules.map(HighlightRule::normalizeForRestore))
            }.onFailure {
                AppLog.put("恢复高亮规则出错\n${it.localizedMessage}", it)
            }
        }
        fileToListT<BookGroup>(path, "bookGroup.json")?.let { groups ->
            groups.forEach { group ->
                group.cover = group.cover?.let { coverPath ->
                    remapRestoredCoverPath(coverPath, backupRoot, appCtx.externalFiles)
                }
            }
            appDb.bookGroupDao.insert(*groups.toTypedArray())
        }
        fileToListT<BookSource>(path, "bookSource.json")?.let {
            appDb.bookSourceDao.insert(*it.toTypedArray())
        } ?: run {
            val bookSourceFile = File(path, "bookSource.json")
            if (bookSourceFile.exists()) {
                val json = bookSourceFile.readText()
                ImportOldData.importOldSource(json)
            }
        }
        fileToListT<RssSource>(path, "rssSources.json")?.let {
            appDb.rssSourceDao.insert(*it.toTypedArray())
        }
        fileToListT<RssStar>(path, "rssStar.json")?.let {
            appDb.rssStarDao.insert(*it.toTypedArray())
        }
        fileToListT<ReplaceRule>(path, "replaceRule.json")?.let {
            val insertedIds = appDb.replaceRuleDao.insert(*it.toTypedArray())
            ReplacePreviewConfig.saveImportedSamples(it, insertedIds, clearMissing = true)
        }
        fileToListT<SearchKeyword>(path, "searchHistory.json")?.let {
            appDb.searchKeywordDao.insert(*it.toTypedArray())
        }
        fileToListT<RuleSub>(path, "sourceSub.json")?.let {
            appDb.ruleSubDao.insert(*it.toTypedArray())
        }
        fileToListT<TxtTocRule>(path, "txtTocRule.json")?.let {
            appDb.txtTocRuleDao.insert(*it.toTypedArray())
        }
        fileToListT<HttpTTS>(path, "httpTTS.json")?.let {
            appDb.httpTTSDao.insert(*it.toTypedArray())
        }
        fileToListT<DictRule>(path, "dictRule.json")?.let {
            appDb.dictRuleDao.insert(*it.toTypedArray())
        }
        fileToListT<KeyboardAssist>(path, "keyboardAssists.json")?.let {
            appDb.keyboardAssistsDao.deleteAll() //先删除所有,保证和备份数据一样
            appDb.keyboardAssistsDao.insert(*it.toTypedArray())
        }
        fileToListT<ReadRecord>(path, "readRecord.json")?.let {
            it.forEach { readRecord ->
                //判断是不是本机记录
                if (readRecord.deviceId != androidId) {
                    appDb.readRecordDao.insert(readRecord)
                } else {
                    val current = appDb.readRecordDao
                        .getRecord(readRecord.deviceId, readRecord.bookName)
                    if (current == null || current.readTime < readRecord.readTime) {
                        appDb.readRecordDao.insert(readRecord)
                    } else if (readRecord.author.isNotBlank()) {
                        appDb.readRecordDao.insert(current.copy(author = readRecord.author))
                    }
                }
            }
        }
        File(path, "servers.json").takeIf {
            !lanTransfer && it.exists()
        }?.runCatching {
            var json = readText()
            if (!json.isJsonArray()) {
                json = aes.decryptStr(json)
            }
            GSON.fromJsonArray<Server>(json).getOrNull()?.let {
                appDb.serverDao.insert(*it.toTypedArray())
            }
        }?.onFailure {
            AppLog.put("恢复服务器配置出错\n${it.localizedMessage}", it)
        }
        restoredCookies?.filter { cookie -> cookie.url.isNotBlank() }?.forEach { cookie ->
            if ('|' in cookie.url) {
                appDb.cookieDao.insert(cookie)
            } else {
                CookieStore.restoreCookie(cookie.url, cookie.cookie)
            }
        }
        restoredRuntimeSourceCaches?.takeIf { it.isNotEmpty() }?.let { caches ->
            // REPLACE updates matching keys while leaving local-only runtime entries intact.
            appDb.cacheDao.insert(*caches.toTypedArray())
            AppCacheManager.clearSourceVariables()
        }
        File(path, DirectLinkUpload.ruleFileName).takeIf {
            !lanTransfer && it.exists()
        }?.runCatching {
            val json = readText()
            ACache.get(cacheDir = false).put(DirectLinkUpload.ruleFileName, json)
        }?.onFailure {
            AppLog.put("恢复直链上传出错\n${it.localizedMessage}", it)
        }
        //恢复主题配置
        File(path, ThemeConfig.configFileName).takeIf {
            it.exists()
        }?.runCatching {
            FileUtils.delete(ThemeConfig.configFilePath)
            copyTo(File(ThemeConfig.configFilePath))
            ThemeConfig.upConfig()
        }?.onFailure {
            AppLog.put("恢复主题出错\n${it.localizedMessage}", it)
        }
        File(path, BookCover.configFileName).takeIf {
            it.exists()
        }?.runCatching {
            val json = readText()
            BookCover.saveCoverRule(json)
        }?.onFailure {
            AppLog.put("恢复封面规则出错\n${it.localizedMessage}", it)
        }
        if (!BackupConfig.ignoreReadConfig) {
            //恢复阅读界面配置
            File(path, ReadBookConfig.configFileName).takeIf {
                it.exists()
            }?.runCatching {
                FileUtils.delete(ReadBookConfig.configFilePath)
                copyTo(File(ReadBookConfig.configFilePath))
                ReadBookConfig.initConfigs()
            }?.onFailure {
                AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it)
            }
            File(path, ReadBookConfig.shareConfigFileName).takeIf {
                it.exists()
            }?.runCatching {
                FileUtils.delete(ReadBookConfig.shareConfigFilePath)
                copyTo(File(ReadBookConfig.shareConfigFilePath))
                ReadBookConfig.initShareConfig()
            }?.onFailure {
                AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it)
            }
        }
        //AppWebDav.downBgs()
        restoredPreferences?.let { map ->
            val edit = appCtx.defaultSharedPreferences.edit()

            map.forEach { (key, value) ->
                if (BackupConfig.keyIsNotIgnore(key) &&
                    (!lanTransfer || key !in lanTransferIgnoredPrefKeys)
                ) {
                    when (key) {
                        PreferKey.webDavPassword -> {
                            kotlin.runCatching {
                                aes.decryptStr(value.toString())
                            }.getOrNull()?.let {
                                edit.putString(key, it)
                            } ?: let {
                                if (appCtx.getPrefString(PreferKey.webDavPassword)
                                        .isNullOrBlank()
                                ) {
                                    edit.putString(key, value.toString())
                                }
                            }
                        }

                        else -> when (value) {
                            is Int -> edit.putInt(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Long -> edit.putLong(key, value)
                            is Float -> edit.putFloat(key, value)
                            is String -> edit.putString(key, value)
                            is Set<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                edit.putStringSet(key, value as Set<String>)
                            }
                        }
                    }
                }
            }
            if (PreferKey.bookshelfReadProgressMode !in map &&
                PreferKey.showBookshelfReadProgress in map
            ) {
                val legacyEnabled = when (val value = map[PreferKey.showBookshelfReadProgress]) {
                    is Boolean -> value
                    is String -> value.toBooleanStrictOrNull()
                    else -> null
                }
                legacyEnabled?.let { enabled ->
                    edit.putInt(
                        PreferKey.bookshelfReadProgressMode,
                        if (enabled) {
                            BookshelfReadProgressMode.STANDARD
                        } else {
                            BookshelfReadProgressMode.HIDDEN
                        },
                    )
                }
            }
            if (!BackupConfig.ignoreReadConfig &&
                PreferKey.showReadTitleChapterNameOnly !in map
            ) {
                edit.putBoolean(PreferKey.showReadTitleChapterNameOnly, false)
            }
            edit.apply()
        }
        restoredVideoPreferences?.let { map ->
            appCtx.getSharedPreferences(VIDEO_PREF_NAME, Context.MODE_PRIVATE).edit().apply {
                map.forEach { (key, value) ->
                    when (value) {
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            putStringSet(key, value as Set<String>)
                        }
                    }
                }
                apply()
            }
        }
        ReadBookConfig.apply {
            comicStyleSelect = appCtx.getPrefInt(PreferKey.comicStyleSelect)
            readStyleSelect = appCtx.getPrefInt(PreferKey.readStyleSelect)
            shareLayout = appCtx.getPrefBoolean(PreferKey.shareLayout)
            hideStatusBar = appCtx.getPrefBoolean(PreferKey.hideStatusBar)
            hideNavigationBar = appCtx.getPrefBoolean(PreferKey.hideNavigationBar)
            autoReadSpeed = appCtx.getPrefInt(PreferKey.autoReadSpeed, 46)
        }
        val coverRestoreResult =
            restoreBackupMediaDirectory(File(path), appCtx.externalFiles, "covers")
            .onFailure {
                AppLog.put("恢复封面图片出错\n${it.localizedMessage}", it)
            }
        val backgroundRestoreResult = if (!BackupConfig.ignoreReadConfig) {
            restoreBackupMediaDirectory(File(path), appCtx.externalFiles, "bg")
                .onFailure {
                    AppLog.put("恢复阅读背景图片出错\n${it.localizedMessage}", it)
                }
        } else {
            null
        }
        coverRestoreResult.getOrThrow()
        backgroundRestoreResult?.getOrThrow()
        if (!restoredAutoTasks.isNullOrEmpty()) {
            appDb.autoTaskRuleDao.upsert(*restoredAutoTasks.toTypedArray())
        }
        AutoTaskScheduler.refresh(appCtx)
        appCtx.toastOnUi(R.string.restore_success)
        withContext(Main) {
            delay(100)
            if (!BuildConfig.DEBUG) {
                LauncherIconHelp.changeIcon(appCtx.getPrefString(PreferKey.launcherIcon))
            }
            WallpaperTheme.syncWithPreferences(appCtx)
            ThemeConfig.applyDayNight(appCtx)
        }
    }

    private inline fun <reified T> fileToListT(path: String, fileName: String): List<T>? {
        try {
            val file = File(path, fileName)
            if (file.exists()) {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件大小 ${file.length()}")
                FileInputStream(file).use {
                    return GSON.fromJsonArray<T>(it).getOrThrow().also { list ->
                        LogUtils.d(TAG, "阅读恢复备份 $fileName 列表大小 ${list.size}")
                    }
                }
            } else {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件不存在")
            }
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取解析出错\n${e.localizedMessage}", e)
            appCtx.toastOnUi("$fileName\n读取文件出错\n${e.localizedMessage}")
        }
        return null
    }

    private fun applyLegacyHighlightOwners(highlights: List<BookHighlight>) {
        highlights.forEach { highlight ->
            val bookUrl = highlight.bookUrl.ifBlank {
                appDb.bookDao.getBook(highlight.bookName, highlight.bookAuthor)?.bookUrl.orEmpty()
            }
            val chapterUrl = highlight.chapterUrl.ifBlank {
                appDb.bookChapterDao.getChapter(bookUrl, highlight.chapterIndex)
                    ?.takeIf { it.title == highlight.chapterName }
                    ?.url
                    .orEmpty()
            }
            highlight.bindLegacyOwner(bookUrl, chapterUrl)
        }
    }

}

internal fun applyLegacyHighlightStyles(json: String, highlights: List<BookHighlight>) {
    val legacy = GSON.fromJsonObject<List<Map<String, Any?>>>(json).getOrNull()
    highlights.forEachIndexed { index, highlight ->
        if (highlight.style.isNullOrBlank()) {
            val raw = legacy?.getOrNull(index)
            val fill = (raw?.get("bgColor") as? Number)?.toInt() ?: 0
            val textColor = (raw?.get("textColor") as? Number)?.toInt() ?: 0
            if (fill != 0 || textColor != 0) {
                highlight.applyStyle(HighlightStyle(fill = fill, textColor = textColor))
            }
        }
    }
}
