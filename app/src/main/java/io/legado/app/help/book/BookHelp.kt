package io.legado.app.help.book

import android.os.ParcelFileDescriptor
import androidx.core.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import com.script.rhino.runScriptWithContext
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.getFolderName
import io.legado.app.data.entities.isEpub
import io.legado.app.help.config.AppConfig
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.exists
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.apache.commons.text.similarity.JaccardSimilarity
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class ContentSaveKey(val bookUrl: String, val chapterIndex: Int)
internal data class ContentSaveState(val version: Long = 0L, val fileName: String? = null)
internal sealed interface ChapterSourceMatch {
    data class Unique(val targetPosition: Int) : ChapterSourceMatch
    data class Ambiguous(val targetPositions: List<Int>) : ChapterSourceMatch
    data object Missing : ChapterSourceMatch
}

data class ContentSaveToken internal constructor(
    internal val key: ContentSaveKey,
    internal val folderName: String,
    val version: Long,
)

internal data class PendingResourceChapter(
    val chapter: BookChapter,
    val token: ContentSaveToken,
    val oldFileName: String,
    val content: String,
    val file: File,
)

internal class ContentSaveFence {
    private val states = ConcurrentHashMap<ContentSaveKey, ContentSaveState>()

    @Synchronized
    fun state(key: ContentSaveKey): ContentSaveState = states[key] ?: ContentSaveState()

    // ponytail: serialize cache publication; use per-book locks if disk contention becomes measurable.
    fun <T> exclusive(block: () -> T): T = synchronized(this, block)

    @Synchronized
    fun writeIfCurrent(
        key: ContentSaveKey,
        expectedVersion: Long,
        fileName: String,
        write: () -> Unit,
    ): Boolean {
        var written = false
        states.compute(key) { _, current ->
            if ((current?.version ?: 0L) == expectedVersion) {
                write()
                written = true
                current?.copy(fileName = fileName)
            } else {
                current
            }
        }
        return written
    }

    @Synchronized
    fun replace(key: ContentSaveKey, fileName: String, write: () -> Unit) {
        var failure: Throwable? = null
        states.compute(key) { _, current ->
            val nextVersion = (current?.version ?: 0L) + 1L
            try {
                write()
                ContentSaveState(nextVersion, fileName)
            } catch (error: Throwable) {
                failure = error
                ContentSaveState(nextVersion, current?.fileName)
            }
        }
        failure?.let { throw it }
    }
}

@Suppress("unused", "ConstPropertyName")
object BookHelp {
    private val downloadDir: File = appCtx.externalFiles
    private const val cacheFolderName = "book_cache"
    private const val cacheImageFolderName = "images"
    private const val cacheEpubFolderName = "epub"
    private val downloadImages = ConcurrentHashMap<String, Mutex>()
    // Guarded by this, together with image invalidation and writes.
    private val imageVersions = hashMapOf<String, Long>()
    private val contentSaveFence = ContentSaveFence()

    val cachePath = FileUtils.getPath(downloadDir, cacheFolderName)

    fun clearCache() {
        FileUtils.delete(
            FileUtils.getPath(downloadDir, cacheFolderName)
        )
    }

    fun clearCache(book: Book) {
        val filePath = FileUtils.getPath(downloadDir, cacheFolderName, book.getFolderName())
        FileUtils.delete(filePath)
    }

    fun updateCacheFolder(oldBook: Book, newBook: Book) {
        val oldFolderName = oldBook.getFolderNameNoCache()
        val newFolderName = newBook.getFolderNameNoCache()
        if (oldFolderName == newFolderName) return
        val oldFolderPath = FileUtils.getPath(
            downloadDir,
            cacheFolderName,
            oldFolderName
        )
        val newFolderPath = FileUtils.getPath(
            downloadDir,
            cacheFolderName,
            newFolderName
        )
        FileUtils.move(oldFolderPath, newFolderPath)
    }

    /**
     * 清除已删除书的缓存 解压缓存
     */
    suspend fun clearInvalidCache() {
        withContext(IO) {
            val bookFolderNames = hashSetOf<String>()
            val originNames = hashSetOf<String>()
            val cleanupSnapshot = appDb.bookDao.getCacheCleanupSnapshot(
                includeImageBooks = AppConfig.imageRetainNum > 0,
            )
            cleanupSnapshot.imageBooks.forEach(::clearComicCache)
            cleanupSnapshot.books.forEach {
                bookFolderNames.add(it.getFolderName())
                if (it.isEpub) originNames.add(it.originName)
            }
            downloadDir.getFile(cacheFolderName)
                .listFiles()?.forEach { bookFile ->
                    if (!bookFolderNames.contains(bookFile.name)) {
                        FileUtils.delete(bookFile.absolutePath)
                    }
                }
            downloadDir.getFile(cacheEpubFolderName)
                .listFiles()?.forEach { epubFile ->
                    if (!originNames.contains(epubFile.name)) {
                        FileUtils.delete(epubFile.absolutePath)
                    }
                }
            FileUtils.delete(ArchiveUtils.TEMP_PATH)
            val filesDir = appCtx.filesDir
            FileUtils.delete("$filesDir/shareBookSource.json")
            FileUtils.delete("$filesDir/shareRssSource.json")
            FileUtils.delete("$filesDir/books.json")
        }
    }

    //清除已经看过的漫画数据
    private fun clearComicCache(book: Book) {
        //只处理漫画
        //为0的时候，不清除已缓存数据
        if (!book.isImage || AppConfig.imageRetainNum == 0) {
            return
        }
        //向前保留设定数量，向后保留预下载数量
        val startIndex = book.durChapterIndex - AppConfig.imageRetainNum
        val endIndex = book.durChapterIndex + AppConfig.preDownloadNum
        val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl, startIndex, endIndex)
        val imgNames = hashSetOf<String>()
        //获取需要保留章节的图片信息
        chapterList.forEach {
            val content = getContent(book, it)
            if (content != null) {
                val matcher = AppPattern.imgPattern.matcher(content)
                while (matcher.find()) {
                    val src = matcher.group(1) ?: continue
                    val mSrc = NetworkUtils.getAbsoluteURL(it.url, src)
                    imgNames.add("${MD5Utils.md5Encode16(mSrc)}.${getImageSuffix(mSrc)}")
                }
            }
        }
        downloadDir.getFile(
            cacheFolderName,
            book.getFolderName(),
            cacheImageFolderName
        ).listFiles()?.forEach { imgFile ->
            if (!imgNames.contains(imgFile.name)) {
                imgFile.delete()
            }
        }
    }

    fun saveContent(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String,
        token: ContentSaveToken = contentSaveToken(book, bookChapter),
        saveChapterMetadata: Boolean = false,
    ): Boolean {
        return try {
            if (token.key.bookUrl != book.bookUrl ||
                token.key.chapterIndex != bookChapter.index ||
                token.folderName != book.getFolderName()
            ) {
                return false
            }
            val fileName = bookChapter.getFileName()
            val saved = contentSaveFence.writeIfCurrent(
                token.key,
                token.version,
                fileName,
            ) {
                if (content.isNotEmpty()) {
                    writeText(book, bookChapter, token.folderName, fileName, content)
                }
                if (saveChapterMetadata) {
                    appDb.bookChapterDao.updateContentMetadata(
                        bookChapter.bookUrl,
                        bookChapter.index,
                        bookChapter.title,
                        bookChapter.imgUrl,
                    )
                }
            }
            if (saved) {
                //saveImages(bookSource, book, bookChapter, content)
                postEvent(EventBus.SAVE_CONTENT, Pair(book, bookChapter))
            }
            saved
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("保存正文失败 ${book.name} ${bookChapter.title}", e)
            false
        }
    }

    fun saveText(
        book: Book,
        bookChapter: BookChapter,
        content: String,
        saveChapterMetadata: Boolean = false,
    ) {
        if (content.isEmpty()) return
        val folderName = book.getFolderName()
        val fileName = bookChapter.getFileName()
        contentSaveFence.replace(contentSaveKey(book, bookChapter), fileName) {
            writeText(book, bookChapter, folderName, fileName, content)
            if (saveChapterMetadata) {
                appDb.bookChapterDao.updateContentMetadata(
                    bookChapter.bookUrl,
                    bookChapter.index,
                    bookChapter.title,
                    bookChapter.imgUrl,
                )
            }
        }
    }

    fun isContentReversed(book: Book, chapter: BookChapter): Boolean {
        val fileName = contentSaveFileName(book, chapter) ?: chapter.getFileName()
        val file = downloadDir.getFile(cacheFolderName, book.getFolderName(), fileName)
        val marker = File(file.path + ".reversed")
        return runCatching {
            marker.isFile && file.isFile &&
                marker.bufferedReader().use { it.readLine() } == file.inputStream().use(MD5Utils::md5Encode)
        }.getOrDefault(false)
    }

    fun reverseContent(book: Book, chapter: BookChapter): Boolean {
        val folderName = book.getFolderName()
        val fileName = contentSaveFileName(book, chapter) ?: chapter.getFileName()
        val file = downloadDir.getFile(cacheFolderName, folderName, fileName)
        if (!file.isFile || file.length() == 0L) return false
        var saved = false
        contentSaveFence.replace(contentSaveKey(book, chapter), fileName) {
            if (!file.isFile) return@replace
            val content = file.readText().takeIf { it.isNotEmpty() } ?: return@replace
            val wasReversed = isContentReversed(book, chapter)
            val marker = File(file.path + ".reversed")
            // Restore the original verbatim, even when reversing plain text
            // happened to create a sequence that looks like reader markup.
            val reversed = if (wasReversed) marker.readText().substringAfter('\n')
                else reverseContentText(content)
            // Prepare the undo data before changing the cache. The fingerprint
            // only becomes valid after the atomic content write succeeds.
            if (!wasReversed) {
                try {
                    marker.writeText(MD5Utils.md5Encode(reversed) + "\n" + content)
                } catch (error: Throwable) {
                    if (marker.isFile) marker.delete()
                    throw error
                }
            }
            val atomicFile = AtomicFile(file)
            var output: FileOutputStream? = null
            try {
                output = atomicFile.startWrite()
                output.write(reversed.toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(output)
                output = null
                if (file.readText() != reversed) throw IOException("Reversed content was not saved")
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                if (!wasReversed) marker.delete()
                throw error
            }
            if (wasReversed) marker.delete()
            saved = true
        }
        return saved
    }

    internal fun contentSaveToken(book: Book, bookChapter: BookChapter): ContentSaveToken {
        val key = contentSaveKey(book, bookChapter)
        return ContentSaveToken(
            key,
            book.getFolderName(),
            contentSaveFence.state(key).version,
        )
    }

    internal fun isContentSaveCurrent(token: ContentSaveToken): Boolean =
        contentSaveFence.state(token.key).version == token.version

    internal fun resourceStagingDir(book: Book): File {
        val parent = FileUtils.createFolderIfNotExist(downloadDir, cacheFolderName, book.getFolderName())
        return File.createTempFile(".resource-refresh-", ".tmp", parent).apply {
            if (!delete() || !mkdir()) throw IOException("Cannot prepare resource refresh")
        }
    }

    @Synchronized
    internal fun imageSaveVersion(book: Book, src: String): Long =
        imageVersions[getImage(book, src).absolutePath] ?: 0L

    internal fun commitResources(
        book: Book,
        chapters: List<PendingResourceChapter>,
        images: Map<String, File>,
        imageTokens: Map<String, Long>,
    ) = contentSaveFence.exclusive { synchronized(this) {
        if (chapters.any { !isContentSaveCurrent(it.token) || it.token.folderName != book.getFolderName() ||
                it.token.key.bookUrl != book.bookUrl || it.token.key.chapterIndex != it.chapter.index } ||
            imageTokens.any { (src, version) -> imageSaveVersion(book, src) != version }) {
            throw IOException("资源已被其他操作更新，请重试刷新")
        }
        val files = linkedMapOf<File, File?>()
        chapters.forEach { pending ->
            val current = downloadDir.getFile(cacheFolderName, pending.token.folderName,
                contentSaveFileName(book, pending.chapter) ?: pending.oldFileName)
            val target = downloadDir.getFile(cacheFolderName, pending.token.folderName,
                pending.chapter.getFileName())
            if (current != target) files[current] = null
            files[File(current.path + ".reversed")] = null
            files[File(target.path + ".reversed")] = null
            files[target] = pending.file
        }
        images.forEach { (src, file) -> files[getImage(book, src)] = file }
        replaceResourceFiles(files) {
            appDb.runInTransaction {
                chapters.forEach { pending ->
                    val chapter = pending.chapter
                    appDb.bookChapterDao.updateResourceMetadata(book.bookUrl, chapter.index,
                        chapter.title, chapter.imgUrl, chapter.variable)
                    if (book.isOnLineTxt && AppConfig.tocCountWords) {
                        val wordCount = StringUtils.wordCountFormat(pending.content.length)
                        chapter.wordCount = wordCount
                        appDb.bookChapterDao.upWordCount(book.bookUrl, chapter.url, wordCount)
                    }
                }
            }
        }
        chapters.forEach {
            contentSaveFence.replace(it.token.key, it.chapter.getFileName()) {}
        }
        images.keys.forEach { src ->
            val path = getImage(book, src).absolutePath
            imageVersions[path] = (imageVersions[path] ?: 0L) + 1L
        }
    } }

    private fun contentSaveFileName(book: Book, bookChapter: BookChapter): String? {
        return contentSaveFence.state(contentSaveKey(book, bookChapter)).fileName
    }

    private fun contentSaveKey(book: Book, bookChapter: BookChapter) =
        ContentSaveKey(book.bookUrl, bookChapter.index)

    private fun writeText(
        book: Book,
        bookChapter: BookChapter,
        folderName: String,
        fileName: String,
        content: String,
    ) {
        //保存文本
        FileUtils.createFileIfNotExist(
            downloadDir,
            cacheFolderName,
            folderName,
            fileName,
        ).writeText(content)
        // A fresh download or a manual edit replaces the reversed cache.
        File(downloadDir.getFile(cacheFolderName, folderName, fileName).path + ".reversed").delete()
        if (book.isOnLineTxt && AppConfig.tocCountWords) {
            val wordCount = StringUtils.wordCountFormat(content.length)
            bookChapter.wordCount = wordCount
            appDb.bookChapterDao.upWordCount(bookChapter.bookUrl, bookChapter.url, wordCount)
        }
    }

    fun flowImages(bookChapter: BookChapter, content: String): Flow<String> {
        return flow {
            val matcher = AppPattern.imgPattern.matcher(content)
            while (matcher.find()) {
                val src = matcher.group(1) ?: continue
                val mSrc = NetworkUtils.getAbsoluteURL(bookChapter.url, src)
                emit(mSrc)
            }
        }
    }

    suspend fun saveImages(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String,
        concurrency: Int = AppConfig.threadCount
    ) = coroutineScope {
        flowImages(bookChapter, content).onEachParallel(concurrency) { mSrc ->
            saveImage(bookSource, book, mSrc, bookChapter)
        }.collect()
    }

    suspend fun saveImage(
        bookSource: BookSource?,
        book: Book,
        src: String,
        chapter: BookChapter? = null
    ) {
        val imagePath = getImage(book, src).absolutePath
        val version = synchronized(this) {
            if (isImageExist(book, src)) return
            imageVersions[imagePath] ?: 0L
        }
        val mutex = synchronized(this) {
            downloadImages.getOrPut(src) { Mutex() }
        }
        mutex.lock()
        try {
            if (isImageExist(book, src)) {
                return
            }
            fetchImage(bookSource, book, src)?.let {
                if (!checkImage(it)) {
                    // 如果部分图片失效，每次进入正文都会花很长时间再次获取图片数据
                    // 所以无论如何都要将数据写入到文件里
                    // throw NoStackTraceException("数据异常")
                    AppLog.put("${book.name} ${chapter?.title} 图片 $src 下载错误 数据异常")
                }
                synchronized(this) {
                    if ((imageVersions[imagePath] ?: 0L) == version) {
                        writeImage(book, src, it)
                    }
                }
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            val msg = "${book.name} ${chapter?.title} 图片 $src 下载失败\n${e.localizedMessage}"
            AppLog.put(msg, e)
        } finally {
            downloadImages.remove(src)
            mutex.unlock()
        }
    }

    fun getImage(book: Book, src: String): File {
        return downloadDir.getFile(
            cacheFolderName,
            book.getFolderName(),
            cacheImageFolderName,
            "${MD5Utils.md5Encode16(src)}.${getImageSuffix(src)}"
        )
    }

    @Synchronized
    fun writeImage(book: Book, src: String, bytes: ByteArray) {
        getImage(book, src).createFileIfNotExist().writeBytes(bytes)
    }

    internal suspend fun fetchImage(bookSource: BookSource?, book: Book, src: String): ByteArray? {
        val bytes = AnalyzeUrl(src, source = bookSource, coroutineContext = currentCoroutineContext())
            .getByteArrayAwait()
        return runScriptWithContext { ImageUtils.decode(src, bytes, isCover = false, bookSource, book) }
    }

    @Synchronized
    fun delImage(book: Book, src: String) {
        val file = getImage(book, src)
        imageVersions[file.absolutePath] = (imageVersions[file.absolutePath] ?: 0L) + 1L
        if (file.exists() && !file.delete()) {
            throw IOException("删除图片缓存失败: ${file.name}")
        }
    }

    @Synchronized
    fun isImageExist(book: Book, src: String): Boolean {
        return getImage(book, src).exists()
    }

    fun getImageSuffix(src: String): String {
        return UrlUtil.getSuffix(src, "jpg")
    }

    @Throws(IOException::class, FileNotFoundException::class)
    fun getEpubFile(book: Book): ZipFile {
        val uri = book.getLocalUri()
        if (uri.isContentScheme()) {
            FileUtils.createFolderIfNotExist(downloadDir, cacheEpubFolderName)
            val path = FileUtils.getPath(downloadDir, cacheEpubFolderName, book.originName)
            val file = File(path)
            val doc = DocumentFile.fromSingleUri(appCtx, uri)
                ?: throw IOException("文件不存在")
            if (!file.exists() || doc.lastModified() > book.latestChapterTime) {
                LocalBook.getBookInputStream(book).use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
            return ZipFile(file)
        }
        return ZipFile(uri.path)
    }

    /**
     * 获取本地书籍文件的ParcelFileDescriptor
     *
     * @param book
     * @return
     */
    @Throws(IOException::class, FileNotFoundException::class)
    fun getBookPFD(book: Book): ParcelFileDescriptor? {
        val uri = book.getLocalUri()
        return if (uri.isContentScheme()) {
            appCtx.contentResolver.openFileDescriptor(uri, "r")
        } else {
            ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    fun getChapterFiles(book: Book): HashSet<String> {
        val fileNames = hashSetOf<String>()
        if (book.isLocalTxt) {
            return fileNames
        }
        FileUtils.createFolderIfNotExist(
            downloadDir,
            subDirs = arrayOf(cacheFolderName, book.getFolderName())
        ).list()?.let {
            fileNames.addAll(it)
        }
        return fileNames
    }

    /**
     * 检测该章节是否下载
     */
    fun hasContent(book: Book, bookChapter: BookChapter): Boolean {
        return if (book.isLocalTxt ||
            (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title))
        ) {
            true
        } else {
            val fileName = contentSaveFileName(book, bookChapter)
                ?: bookChapter.getFileName()
            downloadDir.exists(
                cacheFolderName,
                book.getFolderName(),
                fileName,
            )
        }
    }

    /**
     * 检测图片是否下载
     */
    fun hasImageContent(book: Book, bookChapter: BookChapter): Boolean {
        if (!hasContent(book, bookChapter)) {
            return false
        }
        var ret = true
        getContent(book, bookChapter)?.let {
            val matcher = AppPattern.imgPattern.matcher(it)
            while (matcher.find()) {
                val src = matcher.group(1)!!
                val image = getImage(book, src)
                if (!image.exists()) {
                    ret = false
                    continue
                }
                if (BitmapUtils.getImageSize(image.absolutePath) == null) {
                    if (SvgUtils.getSize(image.absolutePath) != null) {
                        continue
                    }
                    ret = false
                    image.delete()
                }
            }
        }
        return ret
    }

    internal fun checkImage(bytes: ByteArray): Boolean {
        return BitmapUtils.isImage(bytes) ||
            SvgUtils.getSize(ByteArrayInputStream(bytes)) != null
    }

    /**
     * 读取章节内容
     */
    fun getContent(book: Book, bookChapter: BookChapter): String? {
        val fileName = contentSaveFileName(book, bookChapter)
            ?: bookChapter.getFileName()
        return readContent(book, bookChapter, book.getFolderName(), fileName)
    }

    internal fun getContent(
        book: Book,
        bookChapter: BookChapter,
        token: ContentSaveToken,
    ): String? {
        if (token.key.bookUrl != book.bookUrl ||
            token.key.chapterIndex != bookChapter.index ||
            token.folderName != book.getFolderName()
        ) {
            return null
        }
        val fileName = contentSaveFence.state(token.key).fileName
            ?: bookChapter.getFileName()
        return readContent(book, bookChapter, token.folderName, fileName)
    }

    private fun readContent(
        book: Book,
        bookChapter: BookChapter,
        folderName: String,
        fileName: String,
    ): String? = contentSaveFence.exclusive {
        val file = downloadDir.getFile(
            cacheFolderName,
            folderName,
            fileName,
        )
        val token = contentSaveToken(book, bookChapter)
        if (file.exists()) {
            val string = file.readText()
            if (string.isEmpty()) {
                return@exclusive null
            }
            if (book.isEpub) {
                val repaired = runCatching {
                    EpubFile.repairCachedContent(book, bookChapter, string)
                }.getOrDefault(string)
                if (repaired != string) {
                    contentSaveFence.writeIfCurrent(token.key, token.version, fileName) {
                        file.writeText(repaired)
                    }
                }
                return@exclusive repaired
            }
            return@exclusive string
        }
        if (book.isLocal) {
            val string = LocalBook.getContent(book, bookChapter)
            if (string != null && book.isEpub) {
                // Materializing an EPUB cache is part of this read, not a replacement edit.
                contentSaveFence.writeIfCurrent(token.key, token.version, fileName) {
                    writeText(book, bookChapter, folderName, fileName, string)
                }
            }
            return@exclusive string
        }
        null
    }

    /**
     * 删除章节内容
     */
    fun delContent(book: Book, bookChapter: BookChapter) {
        val folderName = book.getFolderName()
        val fileName = contentSaveFileName(book, bookChapter)
            ?: bookChapter.getFileName()
        // A response started before a refresh must not repopulate the invalidated chapter.
        contentSaveFence.replace(contentSaveKey(book, bookChapter), fileName) {
            FileUtils.createFileIfNotExist(
                downloadDir,
                cacheFolderName,
                folderName,
                fileName,
            ).delete()
            File(downloadDir.getFile(cacheFolderName, folderName, fileName).path + ".reversed").delete()
        }
    }

    /**
     * 设置是否禁用正文的去除重复标题,针对单个章节
     */
    fun setRemoveSameTitle(book: Book, bookChapter: BookChapter, removeSameTitle: Boolean) {
        val fileName = bookChapter.getFileName("nr")
        val contentProcessor = ContentProcessor.get(book)
        if (removeSameTitle) {
            val path = FileUtils.getPath(
                downloadDir,
                cacheFolderName,
                book.getFolderName(),
                fileName
            )
            contentProcessor.removeSameTitleCache.remove(fileName)
            File(path).delete()
        } else {
            FileUtils.createFileIfNotExist(
                downloadDir,
                cacheFolderName,
                book.getFolderName(),
                fileName
            )
            contentProcessor.removeSameTitleCache.add(fileName)
        }
    }

    /**
     * 获取是否去除重复标题
     */
    fun removeSameTitle(book: Book, bookChapter: BookChapter): Boolean {
        val path = FileUtils.getPath(
            downloadDir,
            cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName("nr")
        )
        return !File(path).exists()
    }

    /**
     * 格式化书名
     */
    fun formatBookName(name: String): String {
        return name
            .replace(AppPattern.nameRegex, "")
            .trim { it <= ' ' }
    }

    /**
     * 格式化作者
     */
    fun formatBookAuthor(author: String): String {
        return author
            .replace(AppPattern.authorRegex, "")
            .trim { it <= ' ' }
    }

    /**
     * 根据目录名获取当前章节
     */
    fun getDurChapter(
        oldDurChapterIndex: Int,
        oldDurChapterName: String?,
        newChapterList: List<BookChapter>,
        oldChapterListSize: Int = 0,
        searchAllChapterNumbers: Boolean = false,
    ): Int {
        if (oldDurChapterIndex <= 0) return 0
        if (newChapterList.isEmpty()) return oldDurChapterIndex
        val oldChapterNum = getChapterNum(oldDurChapterName)
        val newChapterSize = newChapterList.size
        val durIndex =
            if (oldChapterListSize == 0) oldDurChapterIndex
            else (oldDurChapterIndex.toLong() * newChapterSize / oldChapterListSize).toInt()
        val min = max(0, min(oldDurChapterIndex, durIndex) - 10)
        val max = min(newChapterSize - 1, max(oldDurChapterIndex, durIndex) + 10)
        findNearestChapterTitleIndex(
            oldDurChapterName,
            newChapterList,
            min..max,
            durIndex,
        )?.let { return it }
        if (searchAllChapterNumbers && oldChapterNum > 0) {
            findNearestChapterNumberIndex(
                newChapterList.map { getChapterNum(it.title) },
                oldChapterNum,
                durIndex,
            )?.let { return it }
        }
        if (oldChapterNum > 0) {
            for (i in min..max) {
                if (getChapterNum(newChapterList[i].title) == oldChapterNum) return i
            }
        }
        return min(max(0, newChapterList.size - 1), oldDurChapterIndex)
    }

    fun getDurChapter(
        oldBook: Book,
        newChapterList: List<BookChapter>
    ): Int {
        return oldBook.run {
            getDurChapter(durChapterIndex, durChapterTitle, newChapterList, totalChapterNum)
        }
    }

}

internal fun matchChapterSource(
    originalChapter: BookChapter,
    targetChapters: List<BookChapter>,
): ChapterSourceMatch {
    val candidates = targetChapters.withIndex().filterNot { it.value.isVolume }
    val originalName = getPureChapterName(originalChapter.title)
    if (originalName.isNotEmpty()) {
        val titleMatches = candidates.mapNotNull { (position, chapter) ->
            position.takeIf { originalName == getPureChapterName(chapter.title) }
        }
        chapterSourceMatch(titleMatches)?.let { return it }
    }
    val originalNumber = getChapterNum(originalChapter.title)
    if (originalNumber > 0) {
        val numberMatches = candidates.mapNotNull { (position, chapter) ->
            position.takeIf { getChapterNum(chapter.title) == originalNumber }
        }
        chapterSourceMatch(numberMatches)?.let { return it }
    }
    return ChapterSourceMatch.Missing
}

private fun chapterSourceMatch(positions: List<Int>): ChapterSourceMatch? {
    return when (positions.size) {
        0 -> null
        1 -> ChapterSourceMatch.Unique(positions.first())
        else -> ChapterSourceMatch.Ambiguous(positions)
    }
}

private val chapterNamePattern1 by lazy {
    Pattern.compile(
        ".*?第([\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话]"
    )
}

@Suppress("RegExpSimplifiable")
private val chapterNamePattern2 by lazy {
    Pattern.compile(
        "^(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[,:、])*([\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)(?:[,:、]|\\.[^\\d])"
    )
}

private val regexA by lazy {
    "\\s".toRegex()
}

private fun getChapterNum(chapterName: String?): Int {
    chapterName ?: return -1
    val chapterName1 = StringUtils.fullToHalf(chapterName).replace(regexA, "")
    return StringUtils.stringToInt(
        (
                chapterNamePattern1.matcher(chapterName1).takeIf { it.find() }
                    ?: chapterNamePattern2.matcher(chapterName1).takeIf { it.find() }
                )?.group(1)
            ?: "-1"
    )
}

private val regexOther by lazy {
    // 所有非字母数字中日韩文字 CJK区+扩展A-F区
    @Suppress("RegExpDuplicateCharacterInClass")
    "[^\\w\\u4E00-\\u9FEF〇\\u3400-\\u4DBF\\u20000-\\u2A6DF\\u2A700-\\u2EBEF]".toRegex()
}

@Suppress("RegExpUnnecessaryNonCapturingGroup", "RegExpSimplifiable")
private val regexB by lazy {
    //章节序号，排除处于结尾的状况，避免将章节名替换为空字串
    "^.*?第(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话](?!$)|^(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[,:、])*(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)(?:[,:、](?!$)|\\.(?=[^\\d]))".toRegex()
}

private val regexC by lazy {
    //前后附加内容，整个章节名都在括号中时只剔除首尾括号，避免将章节名替换为空字串
    "(?!^)(?:[〖【《〔\\[{(][^〖【《〔\\[{()〕》】〗\\]}]+)?[)〕》】〗\\]}]$|^[〖【《〔\\[{(](?:[^〖【《〔\\[{()〕》】〗\\]}]+[〕》】〗\\]})])?(?!$)".toRegex()
}

private fun getPureChapterName(chapterName: String?): String {
    return if (chapterName == null) "" else StringUtils.fullToHalf(chapterName)
        .replace(regexA, "")
        .replace(regexB, "")
        .replace(regexC, "")
        .replace(regexOther, "")
}

private val jaccardSimilarity by lazy {
    JaccardSimilarity()
}

internal fun findNearestChapterTitleIndex(
    oldChapterName: String?,
    newChapterList: List<BookChapter>,
    range: IntRange,
    expectedIndex: Int,
): Int? {
    val oldName = getPureChapterName(oldChapterName)
    if (oldName.isEmpty()) return null
    var bestSimilarity = 0.0
    var bestIndex = 0
    for (i in range) {
        val similarity = jaccardSimilarity.apply(
            oldName,
            getPureChapterName(newChapterList[i].title),
        )
        if (similarity > bestSimilarity ||
            similarity == bestSimilarity && abs(i - expectedIndex) < abs(bestIndex - expectedIndex)
        ) {
            bestSimilarity = similarity
            bestIndex = i
        }
    }
    return bestIndex.takeIf { bestSimilarity > 0.96 }
}

internal fun findNearestChapterNumberIndex(
    chapterNumbers: List<Int>,
    chapterNumber: Int,
    expectedIndex: Int,
): Int? {
    return chapterNumbers.indices
        .filter { chapterNumbers[it] == chapterNumber }
        .minByOrNull { abs(it - expectedIndex) }
}
