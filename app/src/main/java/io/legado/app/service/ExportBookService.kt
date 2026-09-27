package io.legado.app.service

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.provider.DocumentsContract
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.app.NotificationCompat
import androidx.core.graphics.withSave
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.cnCompare
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.exists
import io.legado.app.utils.find
import io.legado.app.utils.list
import io.legado.app.utils.mapAsync
import io.legado.app.utils.mapAsyncIndexed
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeFile
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.ag2s.epublib.domain.Author
import me.ag2s.epublib.domain.Date
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.FileResourceProvider
import me.ag2s.epublib.domain.LazyResource
import me.ag2s.epublib.domain.LazyResourceProvider
import me.ag2s.epublib.domain.Metadata
import me.ag2s.epublib.domain.Resource
import me.ag2s.epublib.domain.TOCReference
import me.ag2s.epublib.epub.EpubWriter
import me.ag2s.epublib.epub.EpubWriterProcessor
import me.ag2s.epublib.util.ResourceUtil
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

internal fun findPdfPageLineEnd(
    lineTops: IntArray,
    lineBottoms: IntArray,
    startLine: Int,
    availableHeight: Int
): Int {
    require(lineTops.size == lineBottoms.size)
    if (startLine !in lineTops.indices) return lineTops.size
    var endLine = startLine
    while (endLine < lineBottoms.size &&
        lineBottoms[endLine] - lineTops[startLine] <= availableHeight
    ) {
        endLine++
    }
    return maxOf(startLine + 1, endLine).coerceAtMost(lineTops.size)
}

internal sealed interface PdfContentBlock {
    data class Text(val value: String) : PdfContentBlock
    data class Image(val source: String) : PdfContentBlock
}

internal fun splitPdfContentBlocks(content: String): List<PdfContentBlock> {
    val blocks = arrayListOf<PdfContentBlock>()
    val matcher = AppPattern.imgPattern.matcher(content)
    var start = 0
    while (matcher.find()) {
        if (matcher.start() > start) {
            blocks.add(PdfContentBlock.Text(content.substring(start, matcher.start())))
        }
        matcher.group(1)?.let { blocks.add(PdfContentBlock.Image(it)) }
        start = matcher.end()
    }
    if (start < content.length) {
        blocks.add(PdfContentBlock.Text(content.substring(start)))
    }
    return blocks
}

internal fun imagePdfContentBlocks(
    content: String?,
    isVolume: Boolean
): List<PdfContentBlock.Image> {
    if (content == null) {
        if (isVolume) return emptyList()
        throw NoStackTraceException("图片章节正文未缓存")
    }
    val images = splitPdfContentBlocks(content).filterIsInstance<PdfContentBlock.Image>()
    if (images.isEmpty() && !isVolume) {
        throw NoStackTraceException("图片章节没有可导出的图片")
    }
    return images
}

internal fun calculatePdfBitmapSampleSize(
    width: Int,
    height: Int,
    requestedWidth: Int,
    requestedHeight: Int
): Int {
    if (width <= 0 || height <= 0 || requestedWidth <= 0 || requestedHeight <= 0) return 1
    val reduction = max(
        width / requestedWidth.toFloat(),
        height / requestedHeight.toFloat()
    )
    var sampleSize = 1
    while (sampleSize * 2f <= reduction) sampleSize *= 2
    return sampleSize
}

internal fun <T> replaceStagedExport(
    current: T?,
    backupCurrent: (T) -> T?,
    activateStaged: () -> T?,
    restoreCurrent: (T) -> T?,
    deleteBackup: (T) -> Unit
): T {
    val backup = current?.let {
        checkNotNull(backupCurrent(it)) { "无法备份已有导出文件" }
    }
    fun restore() {
        backup?.let {
            checkNotNull(restoreCurrent(it)) { "无法恢复原导出文件" }
        }
    }
    return try {
        val installed = checkNotNull(activateStaged()) { "无法替换导出文件" }
        backup?.let { runCatching { deleteBackup(it) } }
        installed
    } catch (error: Throwable) {
        val restoreError = runCatching { restore() }.exceptionOrNull()
        if (restoreError == null) {
            backup?.let { runCatching { deleteBackup(it) } }
        } else {
            error.addSuppressed(restoreError)
        }
        throw error
    }
}

/**
 * 导出书籍服务
 */
class ExportBookService : BaseService() {

    companion object {
        val exportProgress = ConcurrentHashMap<String, Int>()
        val exportMsg = ConcurrentHashMap<String, String>()
    }

    data class ExportConfig(
        val path: String,
        val type: String,
        val epubSize: Int = 1,
        val epubScope: String? = null
    )

    private val groupKey = "${appCtx.packageName}.exportBook"
    private val waitExportBooks = linkedMapOf<String, ExportConfig>()
    private var exportJob: Job? = null
    private var notificationContentText = appCtx.getString(R.string.service_starting)


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> kotlin.runCatching {
                val bookUrl = intent.getStringExtra("bookUrl")!!
                if (!exportProgress.contains(bookUrl)) {
                    val exportConfig = ExportConfig(
                        path = intent.getStringExtra("exportPath")!!,
                        type = intent.getStringExtra("exportType")!!,
                        epubSize = intent.getIntExtra("epubSize", 1),
                        epubScope = intent.getStringExtra("epubScope")
                    )
                    waitExportBooks[bookUrl] = exportConfig
                    exportMsg[bookUrl] = getString(R.string.export_wait)
                    postEvent(EventBus.EXPORT_BOOK, bookUrl)
                    export()
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }

            IntentAction.stop -> {
                notificationManager.cancel(NotificationId.ExportBook)
                stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        exportProgress.clear()
        exportMsg.clear()
        waitExportBooks.keys.forEach {
            postEvent(EventBus.EXPORT_BOOK, it)
        }
    }

    @SuppressLint("MissingPermission")
    override fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_export)
            .setSubText(getString(R.string.export_book))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(groupKey)
            .setGroupSummary(true)
        startForeground(NotificationId.ExportBookService, notification.build())
    }

    private fun upExportNotification(finish: Boolean = false) {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_export)
            .setSubText(getString(R.string.export_book))
            .setContentIntent(activityPendingIntent<CacheActivity>("cacheActivity"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentText(notificationContentText)
            .setDeleteIntent(servicePendingIntent<ExportBookService>(IntentAction.stop))
            .setGroup(groupKey)
            .setOnlyAlertOnce(true)
        if (!finish) {
            notification.setOngoing(true)
            notification.addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<ExportBookService>(IntentAction.stop)
            )
        }
        notificationManager.notify(NotificationId.ExportBook, notification.build())
    }

    private fun export() {
        if (exportJob?.isActive == true) {
            return
        }
        exportJob = lifecycleScope.launch(IO) {
            while (isActive) {
                val (bookUrl, exportConfig) = waitExportBooks.entries.firstOrNull() ?: let {
                    notificationContentText = "导出完成"
                    upExportNotification(true)
                    stopSelf()
                    return@launch
                }
                exportProgress[bookUrl] = 0
                waitExportBooks.remove(bookUrl)
                val book = appDb.bookDao.getBook(bookUrl)
                try {
                    book ?: throw NoStackTraceException("获取${bookUrl}书籍出错")
                    refreshChapterList(book)
                    notificationContentText = getString(
                        R.string.export_book_notification_content,
                        book.name,
                        waitExportBooks.size
                    )
                    upExportNotification()
                    when (exportConfig.type) {
                        "epub" -> {
                            if (exportConfig.epubScope.isNullOrBlank()) {
                                exportEpub(exportConfig.path, book)
                            } else {
                                CustomExporter(
                                    exportConfig.epubScope,
                                    exportConfig.epubSize
                                ).export(exportConfig.path, book)
                            }
                        }

                        "pdf" -> exportPdf(exportConfig.path, book)
                        "txt" -> exportTxt(exportConfig.path, book)
                        else -> throw NoStackTraceException("未知导出类型: ${exportConfig.type}")
                    }
                    exportMsg[book.bookUrl] = getString(R.string.export_success)
                } catch (e: Throwable) {
                    ensureActive()
                    exportMsg[bookUrl] = e.localizedMessage ?: "ERROR"
                    AppLog.put("导出书籍<${book?.name ?: bookUrl}>出错", e)
                } finally {
                    exportProgress.remove(bookUrl)
                    postEvent(EventBus.EXPORT_BOOK, bookUrl)
                }
            }
        }
    }

    private fun refreshChapterList(book: Book) {
        if (!book.isLocalModified()) {
            return
        }
        kotlin.runCatching {
            LocalBook.getChapterList(book)
        }.onSuccess {
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*it.toTypedArray())
            book.update()
            ReadBook.onChapterListUpdated(book)
        }
    }

    private data class SrcData(
        val chapterTitle: String,
        val index: Int,
        val src: String
    )

    private fun getChapterContentForExport(book: Book, chapter: BookChapter): String? {
        return resolveExportChapterContent(
            content = BookHelp.getContent(book, chapter),
            isVolume = chapter.isVolume
        )?.let(::sanitizeExportContent)
    }

    private suspend fun exportTxt(path: String, book: Book) {
        exportMsg.remove(book.bookUrl)
        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
        val fileDoc = FileDoc.fromDir(path)
        exportTxt(fileDoc, book)
    }

    private suspend fun exportTxt(fileDoc: FileDoc, book: Book) {
        val filename = book.getExportFileName("txt")
        fileDoc.find(filename)?.delete()

        val bookDoc = fileDoc.createFileIfNotExist(filename)
        val charset = Charset.forName(AppConfig.exportCharset)
        bookDoc.openOutputStream().getOrThrow().bufferedWriter(charset).use { bw ->
            getAllContents(book) { text, srcList ->
                bw.write(text)
                srcList?.forEach {
                    val vFile = BookHelp.getImage(book, it.src)
                    if (vFile.exists()) {
                        fileDoc.createFileIfNotExist(
                            "${it.index}-${MD5Utils.md5Encode16(it.src)}.jpg",
                            subDirs = arrayOf(
                                "${book.name}_${book.author}",
                                "images",
                                it.chapterTitle
                            )
                        ).writeFile(vFile)
                    }
                }
            }
        }
        if (AppConfig.exportToWebDav) {
            // 导出到webdav
            AppWebDav.exportWebDav(bookDoc.uri, filename)
        }
    }

    private suspend fun exportPdf(path: String, book: Book) {
        exportMsg.remove(book.bookUrl)
        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
        exportPdf(FileDoc.fromDir(path), book)
    }

    private suspend fun exportPdf(fileDoc: FileDoc, book: Book) {
        if (book.isPdf) {
            throw NoStackTraceException("PDF 导出暂不支持本地 PDF")
        }
        val filename = book.getExportFileName("pdf")
        val fileStem = filename.substringBeforeLast('.', filename)
        val nonce = System.nanoTime().toString(16)
        val stagingName = ".$fileStem.$nonce.pending.pdf"
        val backupName = ".$fileStem.$nonce.previous.pdf"
        val pageWidth = 595
        val pageHeight = 842
        val margin = 48f
        val contentWidth = (pageWidth - margin * 2).toInt()
        val contentBottom = pageHeight - margin
        val paragraphSpacing = 6f
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 12f
        }
        val chapterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 14f
        }
        val pdf = PdfDocument()
        var pageNumber = 0
        var currentPage: PdfDocument.Page? = null
        var y = margin

        fun finishPage() {
            currentPage?.let { pdf.finishPage(it) }
            currentPage = null
        }

        fun startPage() {
            finishPage()
            val pageInfo = PdfDocument.PageInfo.Builder(
                pageWidth,
                pageHeight,
                ++pageNumber
            ).create()
            currentPage = pdf.startPage(pageInfo).apply {
                canvas.drawColor(Color.WHITE)
            }
            y = margin
        }

        fun drawTextBlock(
            rawText: String,
            paint: TextPaint,
            spacing: Float = paragraphSpacing,
            preserveLeadingWhitespace: Boolean = false
        ) {
            val text = if (preserveLeadingWhitespace) rawText.trimEnd() else rawText.trim()
            if (text.isBlank()) return
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1.15f)
                .setIncludePad(false)
                .build()
            if (layout.lineCount == 0) return
            val lineTops = IntArray(layout.lineCount) { layout.getLineTop(it) }
            val lineBottoms = IntArray(layout.lineCount) { layout.getLineBottom(it) }
            var startLine = 0
            while (startLine < layout.lineCount) {
                if (currentPage == null) startPage()
                var availableHeight = (contentBottom - y).toInt().coerceAtLeast(0)
                val firstLineHeight = lineBottoms[startLine] - lineTops[startLine]
                if (availableHeight < firstLineHeight && y > margin) {
                    startPage()
                    availableHeight = (contentBottom - y).toInt()
                }
                val endLine = findPdfPageLineEnd(
                    lineTops,
                    lineBottoms,
                    startLine,
                    availableHeight
                )
                val blockTop = lineTops[startLine]
                val blockBottom = lineBottoms[endLine - 1]
                val blockHeight = (blockBottom - blockTop).toFloat()
                currentPage!!.canvas.withSave {
                    clipRect(margin, y, margin + contentWidth.toFloat(), y + blockHeight)
                    translate(margin, y - blockTop.toFloat())
                    layout.draw(this)
                }
                y += blockHeight
                startLine = endLine
                if (startLine < layout.lineCount) startPage()
            }
            y += spacing
        }

        fun drawImage(path: String): Boolean {
            val bitmap = decodePdfBitmap(
                path,
                contentWidth,
                (contentBottom - margin).toInt()
            ) ?: return false
            try {
                if (currentPage == null) startPage()
                val fullPageHeight = contentBottom - margin
                val fullPageScale = minOf(
                    1f,
                    contentWidth / bitmap.width.toFloat(),
                    fullPageHeight / bitmap.height.toFloat()
                )
                if (y + bitmap.height * fullPageScale > contentBottom && y > margin) {
                    startPage()
                }
                val availableHeight = (contentBottom - y).coerceAtLeast(1f)
                val scale = minOf(
                    1f,
                    contentWidth / bitmap.width.toFloat(),
                    availableHeight / bitmap.height.toFloat()
                )
                val drawWidth = max(1f, bitmap.width * scale)
                val drawHeight = max(1f, bitmap.height * scale)
                val left = margin + (contentWidth - drawWidth) / 2f
                currentPage!!.canvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(left, y, left + drawWidth, y + drawHeight),
                    null
                )
                y += drawHeight + paragraphSpacing
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            return true
        }

        suspend fun renderPdf(stagingDoc: FileDoc) {
            var renderingError: Throwable? = null
            try {
                drawTextBlock(book.name, titlePaint, 12f)
                drawTextBlock(getString(R.string.author_show, book.getRealAuthor()), metaPaint, 6f)
                drawTextBlock(
                    getString(
                        R.string.intro_show,
                        "\n" + HtmlFormatter.format(book.getDisplayIntro())
                    ),
                    metaPaint,
                    16f
                )
                val useReplace = AppConfig.exportUseReplace && book.getUseReplaceRule()
                val contentProcessor = ContentProcessor.get(book.name, book.origin)
                val titleReplaceRules = contentProcessor.getTitleReplaceRules()
                val replaceBook = book.toReplaceBook()
                val bookSource = book.getBookSource()
                appDb.bookChapterDao.getChapterList(book.bookUrl)
                    .forEachIndexed { index, chapter ->
                        currentCoroutineContext().ensureActive()
                        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
                        exportProgress[book.bookUrl] = index
                        val exportChapter = chapter.copy(isVip = false)
                        val rawContent = getChapterContentForExport(book, exportChapter)
                        val contentBlocks: List<PdfContentBlock> = if (book.isImage) {
                            imagePdfContentBlocks(rawContent, exportChapter.isVolume)
                        } else {
                            val textContent = rawContent ?: return@forEachIndexed
                            val displayContent = sanitizeExportContent(
                                contentProcessor.getContent(
                                    book,
                                    exportChapter,
                                    textContent,
                                    includeTitle = false,
                                    useReplace = useReplace,
                                    chineseConvert = false,
                                    reSegment = false
                                ).toString()
                            )
                            splitPdfContentBlocks(displayContent)
                        }
                        if (!AppConfig.exportNoChapterName) {
                            drawTextBlock(
                                exportChapter.getDisplayTitle(
                                    titleReplaceRules,
                                    useReplace = useReplace,
                                    replaceBook = replaceBook
                                ).replace("\uD83D\uDD12", ""),
                                chapterPaint,
                                10f
                            )
                        }
                        contentBlocks.forEach { block ->
                            when (block) {
                                is PdfContentBlock.Text -> block.value.lineSequence()
                                    .forEach { line ->
                                        drawTextBlock(
                                            line,
                                            bodyPaint,
                                            preserveLeadingWhitespace = true
                                        )
                                    }

                                is PdfContentBlock.Image -> {
                                    currentCoroutineContext().ensureActive()
                                    val src = NetworkUtils.getAbsoluteURL(
                                        exportChapter.url,
                                        block.source
                                    )
                                    val image = ImageProvider.cacheImage(book, src, bookSource)
                                    val rendered = image.exists() && drawImage(image.absolutePath)
                                    if (book.isImage && !rendered) {
                                        throw NoStackTraceException(
                                            "图片导出失败: ${exportChapter.title}"
                                        )
                                    }
                                }
                            }
                        }
                        y += 8f
                    }
                finishPage()
                stagingDoc.openOutputStream().getOrThrow().use { pdf.writeTo(it) }
            } catch (error: Throwable) {
                renderingError = error
                throw error
            } finally {
                var closeError: Throwable? = null
                currentPage?.let { page ->
                    runCatching { pdf.finishPage(page) }
                        .onFailure { closeError = it }
                    currentPage = null
                }
                runCatching { pdf.close() }.onFailure { error ->
                    closeError?.addSuppressed(error) ?: run { closeError = error }
                }
                closeError?.let { error ->
                    renderingError?.addSuppressed(error) ?: throw error
                }
            }
        }

        val stagingDoc = fileDoc.createFileIfNotExist(stagingName)
        val bookDoc = try {
            renderPdf(stagingDoc)
            currentCoroutineContext().ensureActive()
            installPdfExport(fileDoc, stagingDoc, filename, backupName)
        } finally {
            cleanupExportFile(stagingDoc, "PDF 临时文件")
        }

        if (AppConfig.exportToWebDav) {
            AppWebDav.exportWebDav(bookDoc.uri, filename)
        }
    }

    private fun decodePdfBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return SvgUtils.createBitmap(path, reqWidth, reqHeight)
        }
        val sampleSize = calculatePdfBitmapSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            reqWidth,
            reqHeight
        )
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    private fun renameExportFile(file: FileDoc, newName: String): FileDoc? {
        file.asFile()?.let { source ->
            val target = source.parentFile?.resolve(newName) ?: return null
            if (source.renameTo(target)) return FileDoc.fromFile(target)
        }
        if (file.isContentScheme) {
            val renamedUri = runCatching {
                DocumentsContract.renameDocument(appCtx.contentResolver, file.uri, newName)
            }.getOrNull()
            if (renamedUri != null) return FileDoc.fromUri(renamedUri, false)
        }
        return null
    }

    private fun installPdfExport(
        folder: FileDoc,
        staging: FileDoc,
        filename: String,
        backupName: String
    ): FileDoc {
        val current = folder.find(filename)
        return replaceStagedExport(
            current = current,
            backupCurrent = { renameExportFile(it, backupName) },
            activateStaged = { renameExportFile(staging, filename) },
            restoreCurrent = { renameExportFile(it, filename) },
            deleteBackup = { cleanupExportFile(it, "PDF 备份文件") }
        )
    }

    private fun cleanupExportFile(file: FileDoc, description: String) {
        val error = runCatching {
            if (file.exists()) {
                file.delete()
                check(!file.exists()) { "无法删除$description: ${file.name}" }
            }
        }.exceptionOrNull() ?: return
        AppLog.put("清理${description}失败", error, true)
    }

    private suspend fun getAllContents(
        book: Book,
        append: (text: String, srcList: ArrayList<SrcData>?) -> Unit
    ) = coroutineScope {
        val useReplace = AppConfig.exportUseReplace && book.getUseReplaceRule()
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val qy = "${book.name}\n${
            getString(R.string.author_show, book.getRealAuthor())
        }\n${
            getString(
                R.string.intro_show,
                "\n" + HtmlFormatter.format(book.getDisplayIntro())
            )
        }"
        append(sanitizeExportContent(qy), null)
        val threads = if (AppConfig.parallelExportBook) {
            AppConst.MAX_THREAD
        } else {
            1
        }
        flow {
            appDb.bookChapterDao.getChapterList(book.bookUrl).forEach { chapter ->
                emit(chapter)
            }
        }.mapAsync(threads) { chapter ->
            getExportData(book, chapter, contentProcessor, useReplace)
        }.collectIndexed { index, result ->
            postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
            exportProgress[book.bookUrl] = index
            append.invoke(result.first, result.second)
        }

    }

    private fun getExportData(
        book: Book,
        chapter: BookChapter,
        contentProcessor: ContentProcessor,
        useReplace: Boolean
    ): Pair<String, ArrayList<SrcData>?> {
        val content = getChapterContentForExport(book, chapter)
            ?: return Pair("", null)
        val content1 = HtmlFormatter.format(
            contentProcessor.getContent(
                book,
                // 不导出vip标识
                chapter.apply { isVip = false },
                removeExportImages(content),
                includeTitle = !AppConfig.exportNoChapterName,
                useReplace = useReplace,
                chineseConvert = false,
                reSegment = false
            ).toString()
        )
        if (AppConfig.exportPictureFile) {
            //txt导出图片文件
            val srcList = arrayListOf<SrcData>()
            content.split("\n").forEachIndexed { index, text ->
                val matcher = AppPattern.imgPattern.matcher(text)
                while (matcher.find()) {
                    matcher.group(1)?.let {
                        val src = NetworkUtils.getAbsoluteURL(chapter.url, it)
                        srcList.add(SrcData(chapter.title, index, src))
                    }
                }
            }
            return Pair("\n\n$content1", srcList)
        } else {
            return Pair("\n\n$content1", null)
        }
    }

    /**
     * 导出Epub
     */
    private suspend fun exportEpub(path: String, book: Book) {
        exportMsg.remove(book.bookUrl)
        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
        val fileDoc = FileDoc.fromDir(path)
        exportEpub(fileDoc, book)
    }

    private suspend fun exportEpub(fileDoc: FileDoc, book: Book) {
        val filename = book.getExportFileName("epub")
        fileDoc.find(filename)?.delete()

        val epubBook = EpubBook()
        epubBook.version = "2.0"
        //set metadata
        setEpubMetadata(book, epubBook)
        //set cover
        setCover(book, epubBook)
        //set css
        val contentModel = setAssets(fileDoc, book, epubBook)

        //设置正文
        setEpubContent(contentModel, book, epubBook)

        val bookDoc = fileDoc.createFileIfNotExist(filename)
        bookDoc.openOutputStream().getOrThrow().buffered().use { bookOs ->
            EpubWriter().write(epubBook, bookOs)
        }

        if (AppConfig.exportToWebDav) {
            // 导出到webdav
            AppWebDav.exportWebDav(bookDoc.uri, filename)
        }
    }

    private fun setAssets(doc: FileDoc, book: Book, epubBook: EpubBook): String {
        val customPath = doc.find("Asset")
        val contentModel = if (customPath == null) {//使用内置模板
            setAssets(book, epubBook)
        } else {//外部模板
            setAssetsExternal(customPath, book, epubBook)
        }

        return contentModel
    }

    private fun setAssetsExternal(doc: FileDoc, book: Book, epubBook: EpubBook): String {
        var contentModel = ""
        doc.list()!!.forEach { folder ->
            if (folder.isDir && folder.name == "Text") {
                folder.list()!!.sortedWith { o1, o2 ->
                    o1.name.cnCompare(o2.name)
                }.forEach loop@{ file ->
                    if (file.isDir) {
                        return@loop
                    }
                    when {
                        //正文模板
                        file.name.equals("chapter.html", true)
                                || file.name.equals("chapter.xhtml", true) -> {
                            contentModel = file.readText()
                        }
                        //封面等其他模板
                        file.name.endsWith("html", true) -> {
                            epubBook.addSection(
                                FileUtils.getNameExcludeExtension(file.name),
                                ResourceUtil.createPublicResource(
                                    book.name,
                                    book.getRealAuthor(),
                                    book.getDisplayIntro(),
                                    book.kind,
                                    book.wordCount,
                                    file.readText(),
                                    "${folder.name}/${file.name}"
                                )
                            )
                        }
                        //其他格式文件当做资源文件
                        else -> {
                            epubBook.resources.add(
                                Resource(
                                    file.readBytes(),
                                    "${folder.name}/${file.name}"
                                )
                            )
                        }
                    }
                }
            } else if (folder.isDir) {
                //资源文件
                folder.list()!!.forEach loop2@{
                    if (it.isDir) {
                        return@loop2
                    }
                    epubBook.resources.add(
                        Resource(
                            it.readBytes(),
                            "${folder.name}/${it.name}"
                        )
                    )
                }
            } else {//Asset下面的资源文件
                epubBook.resources.add(
                    Resource(
                        folder.readBytes(),
                        folder.name
                    )
                )
            }
        }
        return contentModel
    }

    private fun setAssets(book: Book, epubBook: EpubBook): String {
        epubBook.resources.add(
            Resource(
                appCtx.assets.open("epub/fonts.css").readBytes(),
                "Styles/fonts.css"
            )
        )
        epubBook.resources.add(
            Resource(
                appCtx.assets.open("epub/main.css").readBytes(),
                "Styles/main.css"
            )
        )
        epubBook.resources.add(
            Resource(
                appCtx.assets.open("epub/logo.png").readBytes(),
                "Images/logo.png"
            )
        )
        epubBook.addSection(
            getString(R.string.img_cover),
            ResourceUtil.createPublicResource(
                book.name,
                book.getRealAuthor(),
                book.getDisplayIntro(),
                book.kind,
                book.wordCount,
                String(appCtx.assets.open("epub/cover.html").readBytes()),
                "Text/cover.html"
            )
        )
        epubBook.addSection(
            getString(R.string.book_intro),
            ResourceUtil.createPublicResource(
                book.name,
                book.getRealAuthor(),
                book.getDisplayIntro(),
                book.kind,
                book.wordCount,
                String(appCtx.assets.open("epub/intro.html").readBytes()),
                "Text/intro.html"
            )
        )
        return String(appCtx.assets.open("epub/chapter.html").readBytes())
    }

    private fun setCover(book: Book, epubBook: EpubBook) {
        kotlin.runCatching {
            val request = Glide.with(this)
                .asFile()
                .load(book.getDisplayCover())
            book.getCoverSourceOrigin()?.let { sourceOrigin ->
                request.apply(
                    RequestOptions().set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
                )
            }
            val file = request
                .submit()
                .get()
            val provider = LazyResourceProvider { _ ->
                file.inputStream()
            }
            epubBook.coverImage = LazyResource(provider, "Images/cover.jpg")
        }.onFailure {
            AppLog.put("获取书籍封面出错\n${it.localizedMessage}", it)
        }
    }

    private suspend fun setEpubContent(
        contentModel: String,
        book: Book,
        epubBook: EpubBook
    ) = coroutineScope {
        //正文
        val useReplace = AppConfig.exportUseReplace && book.getUseReplaceRule()
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val replaceBook = book.toReplaceBook()
        val threads = if (AppConfig.parallelExportBook) {
            AppConst.MAX_THREAD
        } else {
            1
        }
        var parentSection: TOCReference? = null
        flow {
            appDb.bookChapterDao.getChapterList(book.bookUrl).forEach { chapter ->
                emit(chapter)
            }
        }.mapAsyncIndexed(threads) { index, chapter ->
            val content = getChapterContentForExport(book, chapter)
                ?: return@mapAsyncIndexed null
            val (contentFix, resources) = fixPic(
                book,
                content,
                chapter
            )
            // 不导出vip标识
            chapter.isVip = false
            val content1 = sanitizeExportContent(
                contentProcessor.getContent(
                    book,
                    chapter,
                    contentFix,
                    includeTitle = false,
                    useReplace = useReplace,
                    chineseConvert = false,
                    reSegment = false
                ).toString()
            )
            val title = chapter.run {
                // 不导出vip标识
                isVip = false
                getDisplayTitle(
                    contentProcessor.getTitleReplaceRules(),
                    useReplace = useReplace,
                    replaceBook = replaceBook
                )
            }
            val chapterResource = ResourceUtil.createChapterResource(
                title.replace("\uD83D\uDD12", ""),
                content1,
                contentModel,
                "Text/chapter_${index}.html"
            )
            ExportChapter(title, chapterResource, resources, chapter)
        }.collectIndexed { index, exportChapter ->
            postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
            exportProgress[book.bookUrl] = index
            if (exportChapter == null) {
                return@collectIndexed
            }
            val (title, chapterResource, resources, chapter) = exportChapter
            epubBook.resources.addAll(resources)
            if (chapter.isVolume) {
                parentSection = epubBook.addSection(title, chapterResource)
            } else if (parentSection == null) {
                epubBook.addSection(title, chapterResource)
            } else {
                epubBook.addSection(parentSection, title, chapterResource)
            }
        }
    }

    data class ExportChapter(
        val title: String,
        val chapterResource: Resource,
        val resources: ArrayList<Resource>,
        val chapter: BookChapter
    )

    private fun fixPic(
        book: Book,
        content: String,
        chapter: BookChapter
    ): Pair<String, ArrayList<Resource>> {
        val data = StringBuilder("")
        val resources = arrayListOf<Resource>()
        content.split("\n").forEach { text ->
            var text1 = text
            val matcher = AppPattern.imgPattern.matcher(text)
            while (matcher.find()) {
                matcher.group(1)?.let {
                    val src = NetworkUtils.getAbsoluteURL(chapter.url, it)
                    val originalHref =
                        "${MD5Utils.md5Encode16(src)}.${BookHelp.getImageSuffix(src)}"
                    val href =
                        "Images/${MD5Utils.md5Encode16(src)}.${BookHelp.getImageSuffix(src)}"
                    val vFile = BookHelp.getImage(book, src)
                    val fp = FileResourceProvider(vFile.parent)
                    if (vFile.exists()) {
                        val img = LazyResource(fp, href, originalHref)
                        resources.add(img)
                    }
                    text1 = text1.replace(src, "../${href}")
                }
            }
            data.append(text1).append("\n")
        }
        return data.toString() to resources
    }

    private fun setEpubMetadata(book: Book, epubBook: EpubBook) {
        val metadata = Metadata()
        metadata.titles.add(book.name)//书籍的名称
        metadata.authors.add(Author(book.getRealAuthor()))//书籍的作者
        metadata.language = "zh"//数据的语言
        metadata.dates.add(Date())//数据的创建日期
        metadata.publishers.add("Legado")//数据的创建者
        metadata.descriptions.add(book.getDisplayIntro())//书籍的简介
        //metadata.subjects.add("")//书籍的主题，在静读天下里面有使用这个分类书籍
        epubBook.metadata = metadata
    }

    //////end of EPUB

    //////start of custom exporter
    /**
     * 自定义Exporter
     * @param scope 导出范围
     * @param size epub 文件包含最大章节数
     */
    inner class CustomExporter(scopeStr: String, private val size: Int) {

        private var scope = parseScope(scopeStr)

        /**
         * 导出Epub
         * @param path 导出的路径
         * @param book 书籍
         */
        suspend fun export(
            path: String,
            book: Book
        ) {
            exportProgress[book.bookUrl] = 0
            exportMsg.remove(book.bookUrl)
            postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
            val currentTimeMillis = System.currentTimeMillis()
            val count = appDb.bookChapterDao.getChapterCount(book.bookUrl)
            scope = scope.filter { it < count }.toHashSet()

            val fileDoc = FileDoc.fromDir(path)

            val (contentModel, epubList) = createEpubs(book, fileDoc)
            var progressBar = 0.0
            epubList.forEachIndexed { index, ep ->
                val (filename, epubBook) = ep
                //设置正文
                setEpubContent(
                    contentModel,
                    book,
                    epubBook,
                    index
                ) { _, _ ->
                    // 将章节写入内存时更新进度条
                    postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
                    progressBar += book.totalChapterNum.toDouble() / scope.size / 2
                    exportProgress[book.bookUrl] = progressBar.toInt()
                }
                save2Drive(filename, epubBook, fileDoc) { total, _ ->
                    //写入硬盘时更新进度条
                    progressBar += book.totalChapterNum.toDouble() / epubList.size / total / 2
                    postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
                    exportProgress[book.bookUrl] = progressBar.toInt()
                }
            }

            val elapsed = System.currentTimeMillis() - currentTimeMillis
            AppLog.put("分割导出书籍 ${book.name} 一共耗时 $elapsed")
        }


        /**
         * 设置epub正文
         *
         * @param contentModel 正文模板
         * @param book 书籍
         * @param epubBook 分割后的epub
         * @param epubBookIndex 分割后的epub序号
         */
        private suspend fun setEpubContent(
            contentModel: String,
            book: Book,
            epubBook: EpubBook,
            epubBookIndex: Int,
            updateProgress: (chapterList: MutableList<BookChapter>, index: Int) -> Unit
        ) {
            //正文
            val useReplace = AppConfig.exportUseReplace && book.getUseReplaceRule()
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            val replaceBook = book.toReplaceBook()
            var chapterList: MutableList<BookChapter> = ArrayList()
            appDb.bookChapterDao.getChapterList(book.bookUrl).forEachIndexed { index, chapter ->
                if (scope.contains(index)) {
                    chapterList.add(chapter)
                }
                if (scope.size == chapterList.size) {
                    return@forEachIndexed
                }
            }
            // val totalChapterNum = book.totalChapterNum / scope.size
            if (chapterList.isEmpty()) {
                throw RuntimeException("书籍<${book.name}>(${epubBookIndex + 1})未找到章节信息")
            }
            chapterList = chapterList.subList(
                epubBookIndex * size,
                min(scope.size, (epubBookIndex + 1) * size)
            )
            chapterList.forEachIndexed { index, chapter ->
                currentCoroutineContext().ensureActive()
                updateProgress(chapterList, index)
                val content = getChapterContentForExport(book, chapter)
                    ?: return@forEachIndexed
                val (contentFix, resources) = fixPic(book, content, chapter)
                epubBook.resources.addAll(resources)
                val content1 = sanitizeExportContent(
                    contentProcessor.getContent(
                        book,
                        chapter,
                        contentFix,
                        includeTitle = false,
                        useReplace = useReplace,
                        chineseConvert = false,
                        reSegment = false
                    ).toString()
                )
                val title = chapter.run {
                    // 不导出vip标识
                    isVip = false
                    getDisplayTitle(
                        contentProcessor.getTitleReplaceRules(),
                        useReplace = useReplace,
                        replaceBook = replaceBook
                    )
                }
                epubBook.addSection(
                    title,
                    ResourceUtil.createChapterResource(
                        title.replace("\uD83D\uDD12", ""),
                        content1,
                        contentModel,
                        "Text/chapter_${index}.html"
                    )
                )
            }
        }

        /**
         * 创建多个epub 对象
         *
         * 分割epub时，一个书籍需要创建多个epub对象
         * @param book 书籍
         * @param fileDoc 导出文件夹文档
         *
         * @return <内容模板字符串, <epub文件名, epub对象>>
         */
        private fun createEpubs(
            book: Book,
            fileDoc: FileDoc
        ): Pair<String, List<Pair<String, EpubBook>>> {
            val paresNumOfEpub = paresNumOfEpub(scope.size, size)
            val result: MutableList<Pair<String, EpubBook>> = ArrayList(paresNumOfEpub)
            var contentModel = ""
            for (i in 1..paresNumOfEpub) {
                val filename = book.getExportFileName("epub", i)
                fileDoc.find(filename)?.delete()

                val epubBook = EpubBook()
                epubBook.version = "2.0"
                //set metadata
                setEpubMetadata(book, epubBook)
                //set cover
                setCover(book, epubBook)
                //set css
                contentModel = setAssets(fileDoc, book, epubBook)

                // add epubBook
                result.add(Pair(filename, epubBook))
            }
            return Pair(contentModel, result)
        }

        /**
         * 保存文件到 设备
         */
        private suspend fun save2Drive(
            filename: String,
            epubBook: EpubBook,
            fileDoc: FileDoc,
            callback: (total: Int, progress: Int) -> Unit
        ) {
            val bookDoc = fileDoc.createFileIfNotExist(filename)
            bookDoc.openOutputStream().getOrThrow().buffered().use { bookOs ->
                EpubWriter()
                    .setCallback(object : EpubWriterProcessor.Callback {
                        override fun onProgressing(total: Int, progress: Int) {
                            callback(total, progress)
                        }
                    })
                    .write(epubBook, bookOs)
            }

            if (AppConfig.exportToWebDav) {
                // 导出到webdav
                AppWebDav.exportWebDav(bookDoc.uri, filename)
            }
        }

        /**
         * 解析 分割epub后的数量
         *
         * @param total 章节总数
         * @param size 每个epub文件包含多少章节
         */
        private fun paresNumOfEpub(total: Int, size: Int): Int {
            val i = total % size
            var result = total / size
            if (i > 0) {
                result++
            }
            return result
        }

        /**
         * 解析范围字符串
         *
         * @param scope 范围字符串
         * @return 范围
         *
         * @since 2023/5/22
         * @author Discut
         */
        private fun parseScope(scope: String): Set<Int> {
            val split = scope.split(",")

            val result = linkedSetOf<Int>()
            for (s in split) {
                val v = s.split("-")
                if (v.size != 2) {
                    result.add(s.toInt() - 1)
                    continue
                }
                val left = v[0].toInt()
                val right = v[1].toInt()
                if (left > right) {
                    AppLog.put("Error expression : $s; left > right")
                    continue
                }
                for (i in left..right)
                    result.add(i - 1)
            }
            return result
        }
    }
}
