package io.legado.app.model.localBook

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.getLocalUri
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.SystemUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.printOnDebug
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.ceil


class PdfFile(var book: Book) : AutoCloseable {
    companion object : BaseLocalBookParse {
        private val cache = CloseableCache<PdfFile>()

        /**
         * pdf分页尺寸
         */
        const val PAGE_SIZE = 10

        @Synchronized
        private fun getPFile(book: Book): PdfFile {
            return cache.getOrCreate(
                matches = { it.openedBookUrl == book.bookUrl },
                create = { PdfFile(book) },
            ).also { it.book = book }
        }

        @Synchronized
        override fun upBookInfo(book: Book) {
            getPFile(book).upBookInfo()
        }

        @Synchronized
        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getPFile(book).getChapterList()
        }

        @Synchronized
        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getPFile(book).getContent(chapter)
        }

        @Synchronized
        override fun getImage(book: Book, href: String): InputStream? {
            return getPFile(book).getImage(href)
        }

        @Synchronized
        fun clear() {
            cache.clear()
        }

        @Synchronized
        fun clear(bookUrl: String) {
            cache.clearIf { it.openedBookUrl == bookUrl }
        }

    }

    private val openedBookUrl = book.bookUrl

    /**
     *持有引用，避免被回收
     */
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var openedPdfRenderer: PdfRenderer? = null
    private val pdfRenderer: PdfRenderer?
        get() {
            if (openedPdfRenderer != null && fileDescriptor != null) {
                return openedPdfRenderer
            }
            openedPdfRenderer = readPdf()
            return openedPdfRenderer
        }

    init {
        upBookCover(true)
    }

    /**
     * 读取PDF文件
     *
     * @return
     */
    private fun readPdf(): PdfRenderer? {
        closePdf()
        val uri = book.getLocalUri()
        val descriptor = if (uri.isContentScheme()) {
            appCtx.contentResolver.openFileDescriptor(uri, "r")
        } else {
            ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
        } ?: return null
        return try {
            PdfRenderer(descriptor).also {
                fileDescriptor = descriptor
            }
        } catch (error: Throwable) {
            descriptor.close()
            throw error
        }
    }

    /**
     * 关闭pdf文件
     *
     */
    private fun closePdf() {
        val renderer = openedPdfRenderer
        val descriptor = fileDescriptor
        openedPdfRenderer = null
        fileDescriptor = null
        kotlin.runCatching {
            try {
                renderer?.close()
            } finally {
                descriptor?.close()
            }
        }.onFailure {
            it.printOnDebug()
        }
    }


    /**
     * 渲染PDF页面
     * 根据index打开pdf页面,并渲染到Bitmap
     *
     * @param renderer
     * @param index
     * @return
     */
    private fun openPdfPage(renderer: PdfRenderer, index: Int): Bitmap? {
        if (index >= renderer.pageCount) {
            return null
        }
        return renderer.openPage(index).use { page ->
            createBitmap(
                SystemUtils.screenWidthPx,
                (SystemUtils.screenWidthPx.toDouble() * page.height / page.width).toInt()
            ).apply {
                this.eraseColor(Color.WHITE)
                page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }

    }

    private fun getContent(chapter: BookChapter): String? =
        if (pdfRenderer == null) {
            null
        } else {
            pdfRenderer?.let { renderer ->

                buildString {
                    val start = chapter.index * PAGE_SIZE
                    val end = ((chapter.index + 1) * PAGE_SIZE).coerceAtMost(renderer.pageCount)
                    (start until end).forEach {
                        append("<img src=").append('"').append(it).append('"').append(" >")
                            .append('\n')
                    }

                }

            }
        }


    private fun getImage(href: String): InputStream? {
        if (pdfRenderer == null) {
            return null
        }
        return try {
            val index = href.toInt()
            val bitmap = openPdfPage(pdfRenderer!!, index)
            if (bitmap != null) {
                BitmapUtils.toInputStream(bitmap)
            } else {
                null
            }

        } catch (_: Exception) {
            return null
        }
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapterList = ArrayList<BookChapter>()

        pdfRenderer?.let { renderer ->
            if (renderer.pageCount > 0) {
                val chapterCount = ceil((renderer.pageCount.toDouble() / PAGE_SIZE)).toInt()
                (0 until chapterCount).forEach {
                    val chapter = BookChapter()
                    chapter.index = it
                    chapter.bookUrl = book.bookUrl
                    chapter.title = "分段_${it}"
                    chapter.url = "pdf_${it}"
                    chapterList.add(chapter)
                }
            }
        }
        return chapterList
    }

    private fun upBookCover(fastCheck: Boolean = false) {
        try {
            pdfRenderer?.let { renderer ->
                if (book.coverUrl.isNullOrEmpty()) {
                    book.coverUrl = LocalBook.getCoverPath(book)
                }
                if (fastCheck && File(book.coverUrl!!).exists()) {
                    return
                }
                FileOutputStream(FileUtils.createFileIfNotExist(book.coverUrl!!)).use { out ->
                    openPdfPage(renderer, 0)?.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                }
            }
        } catch (e: Exception) {
            AppLog.put("加载书籍封面失败\n${e.localizedMessage}", e)
            e.printOnDebug()
        }
    }

    private fun upBookInfo() {
        if (pdfRenderer == null) {
            cache.clearIf { it === this }
            book.intro = "书籍导入异常"
        } else {
            upBookCover()
            if (book.name.isEmpty()) {
                book.name = book.originName.replace(".pdf", "")
            }
        }

    }

    override fun close() {
        closePdf()
    }
}
