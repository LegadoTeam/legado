package io.legado.app.help.book

import android.os.ParcelFileDescriptor
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

internal class ContentSaveFence {
    private val states = ConcurrentHashMap<ContentSaveKey, ContentSaveState>()

    fun state(key: ContentSaveKey): ContentSaveState = states[key] ?: ContentSaveState()

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

    suspend fun saveContent(
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

    internal fun contentSaveToken(book: Book, bookChapter: BookChapter): ContentSaveToken {
        val key = contentSaveKey(book, bookChapter)
        return ContentSaveToken(
            key,
            book.getFolderName(),
            contentSaveFence.state(key).version,
        )
    }

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
        if (isImageExist(book, src)) {
            return
        }
        val mutex = synchronized(this) {
            downloadImages.getOrPut(src) { Mutex() }
        }
        mutex.lock()
        try {
            if (isImageExist(book, src)) {
                return
            }
            val analyzeUrl = AnalyzeUrl(
                src, source = bookSource, coroutineContext = currentCoroutineContext()
            )
            val bytes = analyzeUrl.getByteArrayAwait()
            //某些图片被加密，需要进一步解密
            runScriptWithContext {
                ImageUtils.decode(
                    src, bytes, isCover = false, bookSource, book
                )
            }?.let {
                if (!checkImage(it)) {
                    // 如果部分图片失效，每次进入正文都会花很长时间再次获取图片数据
                    // 所以无论如何都要将数据写入到文件里
                    // throw NoStackTraceException("数据异常")
                    AppLog.put("${book.name} ${chapter?.title} 图片 $src 下载错误 数据异常")
                }
                writeImage(book, src, it)
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

    private fun checkImage(bytes: ByteArray): Boolean {
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
    ): String? {
        val file = downloadDir.getFile(
            cacheFolderName,
            folderName,
            fileName,
        )
        if (file.exists()) {
            val string = file.readText()
            if (string.isEmpty()) {
                return null
            }
            return string
        }
        if (book.isLocal) {
            val string = LocalBook.getContent(book, bookChapter)
            if (string != null && book.isEpub) {
                saveText(book, bookChapter, string)
            }
            return string
        }
        return null
    }

    /**
     * 删除章节内容
     */
    fun delContent(book: Book, bookChapter: BookChapter) {
        val folderName = book.getFolderName()
        val fileName = contentSaveFileName(book, bookChapter)
            ?: bookChapter.getFileName()
        FileUtils.createFileIfNotExist(
            downloadDir,
            cacheFolderName,
            folderName,
            fileName,
        ).delete()
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
