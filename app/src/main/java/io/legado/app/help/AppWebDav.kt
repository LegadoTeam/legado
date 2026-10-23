package io.legado.app.help

import android.net.Uri
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.Restore
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isJson
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.removePref
import io.legado.app.utils.runCatchingCancellable
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * webDav初始化会访问网络,不要放到主线程
 */
object AppWebDav {
    private const val defaultWebDavUrl = "https://dav.jianguoyun.com/dav/"
    private val bookProgressUrl get() = "${rootWebDavUrl}bookProgress/"
    private val exportsWebDavUrl get() = "${rootWebDavUrl}books/"
    private val bgWebDavUrl get() = "${rootWebDavUrl}background/"

    @Volatile
    var authorization: Authorization? = null
        private set

    @Volatile
    var defaultBookWebDav: RemoteBookWebDav? = null

    val isOk get() = authorization != null

    val isJianGuoYun get() = rootWebDavUrl.startsWith(defaultWebDavUrl, true)

    private val configMutex = Mutex()

    // 在 IO 线程初始化，避免首次访问 AppWebDav 时阻塞主线程
    private val initialConfig = CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
        refreshConfig()
    }

    private suspend fun awaitConfig() {
        initialConfig.await()
        // 等待正在进行的配置刷新完成
        configMutex.withLock { }
    }

    private val rootWebDavUrl: String
        get() {
            val configUrl = appCtx.getPrefString(PreferKey.webDavUrl)?.trim()
            var url = if (configUrl.isNullOrEmpty()) defaultWebDavUrl else configUrl
            if (!url.endsWith("/")) url = "${url}/"
            AppConfig.webDavDir?.trim()?.let {
                if (it.isNotEmpty()) {
                    url = "${url}${it}/"
                }
            }
            return url
        }

    suspend fun upConfig() {
        // 先完成首次初始化，防止旧配置覆盖手动更新的账号
        initialConfig.await()
        refreshConfig()
    }

    private suspend fun refreshConfig(): Result<Unit> = withContext(Dispatchers.IO) {
        configMutex.withLock {
            runCatchingCancellable {
                authorization = null
                defaultBookWebDav = null
                val account = appCtx.getPrefString(PreferKey.webDavAccount)
                val password = appCtx.getPrefString(PreferKey.webDavPassword)
                if (!account.isNullOrEmpty() && !password.isNullOrEmpty()) {
                    val mAuthorization = Authorization(account, password)
                    checkAuthorization(mAuthorization)
                    WebDav(rootWebDavUrl, mAuthorization).makeAsDir()
                    WebDav(bookProgressUrl, mAuthorization).makeAsDir()
                    WebDav(exportsWebDavUrl, mAuthorization).makeAsDir()
                    WebDav(bgWebDavUrl, mAuthorization).makeAsDir()
                    val rootBooksUrl = "${rootWebDavUrl}books/"
                    defaultBookWebDav = RemoteBookWebDav(rootBooksUrl, mAuthorization)
                    authorization = mAuthorization
                }
            }.onFailure { error ->
                // 日志写入失败不应阻止后续重试
                runCatching {
                    AppLog.put("WebDav初始化失败\n${error.localizedMessage}", error)
                }
            }
        }
    }

    @Throws(WebDavException::class)
    private suspend fun checkAuthorization(authorization: Authorization) {
        if (!WebDav(rootWebDavUrl, authorization).check()) {
            appCtx.removePref(PreferKey.webDavPassword)
            appCtx.toastOnUi(R.string.webdav_application_authorization_error)
            throw WebDavException(appCtx.getString(R.string.webdav_application_authorization_error))
        }
    }

    @Throws(Exception::class)
    suspend fun getBackupNames(): ArrayList<String> {
        awaitConfig()
        val names = arrayListOf<String>()
        authorization?.let {
            var files = WebDav(rootWebDavUrl, it).listFiles()
            files = files.sortedWith { o1, o2 ->
                AlphanumComparator.compare(o1.displayName, o2.displayName)
            }.reversed()
            files.forEach { webDav ->
                val name = webDav.displayName
                if (name.startsWith("backup")) {
                    names.add(name)
                }
            }
        } ?: throw NoStackTraceException("webDav没有配置")
        return names
    }

    @Throws(WebDavException::class)
    suspend fun restoreWebDav(name: String) {
        awaitConfig()
        authorization?.let {
            val webDav = WebDav(rootWebDavUrl + name, it)
            val archive = File.createTempFile("webdav-restore-", ".zip", appCtx.cacheDir)
            try {
                webDav.downloadTo(archive.absolutePath, true)
                Restore.restoreOrThrow(appCtx, Uri.fromFile(archive))
            } finally {
                archive.delete()
            }
        }
    }

    suspend fun hasBackUp(backUpName: String): Boolean {
        awaitConfig()
        authorization?.let {
            val url = "$rootWebDavUrl${backUpName}"
            return WebDav(url, it).exists()
        }
        return false
    }

    suspend fun lastBackUp(): Result<WebDavFile?> {
        awaitConfig()
        return runCatchingCancellable {
            authorization?.let {
                var lastBackupFile: WebDavFile? = null
                WebDav(rootWebDavUrl, it).listFiles().reversed().forEach { webDavFile ->
                    if (webDavFile.displayName.startsWith("backup")) {
                        if (lastBackupFile == null
                            || webDavFile.lastModify > lastBackupFile.lastModify
                        ) {
                            lastBackupFile = webDavFile
                        }
                    }
                }
                lastBackupFile
            }
        }
    }

    /**
     * webDav备份
     * @param fileName 备份文件名
     */
    @Throws(Exception::class)
    suspend fun backUpWebDav(fileName: String) {
        awaitConfig()
        val authorization = authorization ?: throw NoStackTraceException("webDav未配置或授权失败")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络未连接")
        val putUrl = "$rootWebDavUrl$fileName"
        WebDav(putUrl, authorization).upload(Backup.zipFilePath)
        // 上传成功后清理同一设备的历史备份，只保留刚上传的这份
        kotlin.runCatching {
            pruneOldBackups(authorization, fileName)
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("清理旧备份失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 清理 WebDAV 上的历史备份，只保留最近 [AppConfig.webDavBackupKeepCount] 份。
     *
     * 阅读的备份名是 `backup<yyyy-MM-dd>-<deviceName>.zip`，每备份一次就新建一个文件，
     * 不同日期的不会互相覆盖，于是长期使用会不断累积（实测 4 个多月攒了 17 个）。
     *
     * 保留份数由「WebDAV 备份保留数量」设置控制，**默认 0 表示不自动清理**，
     * 需要用户显式填正整数才生效（填 1 即只保留最新一份）。
     * 默认不清理是为了避免存量用户升级后，历史备份在下一次备份时被一次性删掉。
     *
     * 顺序上先上传成功再清理，最坏情况只是多留一份（下次备份会再清），不会丢数据。
     * 失败只记日志，不影响备份本身。
     */
    private suspend fun pruneOldBackups(authorization: Authorization, currentFileName: String) {
        val keep = AppConfig.webDavBackupKeepCount
        if (keep <= 0) return
        val all = WebDav(rootWebDavUrl, authorization).listFiles()
            .filter { !it.isDir && it.displayName.startsWith("backup") && it.displayName.endsWith(".zip") }
            .sortedByDescending { it.displayName }
        // 先把本次刚上传的文件排除，再决定要删哪些。
        //
        // 不能直接 `all.drop(keep)`：服务器 PROPFIND 可能尚未列出刚上传的文件
        // （缓存/最终一致性），此时 all 里根本没有 currentFileName，
        // drop(keep) 会把旧备份当成「最新 N 份」保留下来，一份都不删，
        // 表现就是「设置填了 1，却还是两份」。
        // 排除 currentFileName 后，本次上传的文件无论如何都不会被删，
        // 剩余名额固定为 keep - 1 份历史备份。
        val others = all.filter { it.displayName != currentFileName }
        others.drop((keep - 1).coerceAtLeast(0)).forEach { file ->
            kotlin.runCatching {
                WebDav(file.path, authorization).delete()
            }
        }
    }

    /**
     * 获取云端所有背景名称
     */
    private suspend fun getAllBgWebDavFiles(): Result<List<WebDavFile>> {
        return kotlin.runCatching {
            if (!NetworkUtils.isAvailable())
                throw NoStackTraceException("网络未连接")
            authorization.let {
                it ?: throw NoStackTraceException("webDav未配置")
                WebDav(bgWebDavUrl, it).listFiles()
            }
        }
    }

    /**
     * 上传背景图片
     */
    suspend fun upBgs(files: Array<File>) {
        awaitConfig()
        val authorization = authorization ?: throw NoStackTraceException("webDav未配置或授权失败")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络未连接")
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
        files.forEach {
            if (!bgWebDavFiles.contains(it.name) && it.exists()) {
                WebDav("$bgWebDavUrl${it.name}", authorization)
                    .upload(it)
            }
        }
    }

    /**
     * 下载背景图片
     */
    suspend fun downBgs() {
        awaitConfig()
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
    }

    @Suppress("unused")
    suspend fun exportWebDav(byteArray: ByteArray, fileName: String) {
        awaitConfig()
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(byteArray, FileUtils.getMimeType(fileName))
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun exportWebDav(uri: Uri, fileName: String) {
        awaitConfig()
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(uri, FileUtils.getMimeType(fileName))
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun uploadBookProgress(
        book: Book,
        toast: Boolean = false,
        onSuccess: (() -> Unit)? = null
    ) {
        awaitConfig()
        val authorization = authorization ?: return
        if (!AppConfig.syncBookProgress) return
        if (!NetworkUtils.isAvailable()) return
        try {
            val bookProgress = BookProgress(book)
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(book.name, book.author)
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            book.syncTime = System.currentTimeMillis()
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e, toast)
        }
    }

    suspend fun uploadBookProgress(bookProgress: BookProgress, onSuccess: (() -> Unit)? = null) {
        awaitConfig()
        try {
            val authorization = authorization ?: return
            if (!AppConfig.syncBookProgress) return
            if (!NetworkUtils.isAvailable()) return
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(bookProgress.name, bookProgress.author)
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e)
        }
    }

    private fun getProgressUrl(name: String, author: String): String {
        return bookProgressUrl + getProgressFileName(name, author)
    }

    private fun getProgressFileName(name: String, author: String): String {
        return UrlUtil.replaceReservedChar("${name}_${author}".normalizeFileName()) + ".json"
    }

    /**
     * 获取书籍进度
     */
    suspend fun getBookProgress(book: Book): BookProgress? {
        awaitConfig()
        val url = getProgressUrl(book.name, book.author)
        kotlin.runCatching {
            val authorization = authorization ?: return null
            WebDav(url, authorization).download().let { byteArray ->
                val json = String(byteArray)
                if (json.isJson()) {
                    return GSON.fromJsonObject<BookProgress>(json).getOrNull()
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("获取书籍进度失败\n${it.localizedMessage}", it)
        }
        return null
    }

    suspend fun downloadAllBookProgress() {
        awaitConfig()
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bookProgressFiles = WebDav(bookProgressUrl, authorization).listFiles()
        val map = hashMapOf<String, WebDavFile>()
        bookProgressFiles.forEach {
            map[it.displayName] = it
        }
        appDb.bookDao.all.forEach { book ->
            val progressFileName = getProgressFileName(book.name, book.author)
            val webDavFile = map[progressFileName] ?: return@forEach
            if (webDavFile.lastModify <= book.syncTime) {
                //本地同步时间大于上传时间不用同步
                return@forEach
            }
            getBookProgress(book)?.let { bookProgress ->
                if (bookProgress.durChapterIndex > book.durChapterIndex
                    || (bookProgress.durChapterIndex == book.durChapterIndex
                            && bookProgress.durChapterPos > book.durChapterPos)
                ) {
                    book.durChapterIndex = bookProgress.durChapterIndex
                    book.durChapterPos = bookProgress.durChapterPos
                    book.durChapterTitle = bookProgress.durChapterTitle
                    book.durChapterTime = bookProgress.durChapterTime
                    book.syncTime = System.currentTimeMillis()
                    book.update()
                }
            }
        }
    }

}
