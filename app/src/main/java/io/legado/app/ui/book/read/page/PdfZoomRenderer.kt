package io.legado.app.ui.book.read.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.model.localBook.PdfFile
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.sqrt

/** One displayed viewport and one render in flight; pending gestures replace each other. */
internal class PdfZoomRenderer(private val view: ContentTextView) {
    internal companion object {
        const val MAX_PIXELS = 4_000_000
    }

    private data class Page(val index: Int, val bounds: RectF)
    private data class Request(val bookUrl: String, val width: Int, val height: Int,
        val pages: List<Page>)
    private data class Work(val request: Request, val book: Book)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = Channel<Work>(Channel.CONFLATED)
    private var requested: Request? = null
    private var rendered: Request? = null
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    internal val renderedPixelCount: Int get() = bitmap?.let { it.width * it.height } ?: 0
    internal val renderedPages: List<Int>
        get() = rendered?.takeIf { it == requested }?.pages?.map { it.index }.orEmpty()
    internal var renderCount = 0
        private set

    init {
        scope.launch {
            for (work in requests) {
                val image = try { withContext(Dispatchers.IO) {
                    val request = work.request
                    val ratio = minOf(1.0, sqrt(MAX_PIXELS.toDouble() / request.width / request.height)).toFloat()
                    val width = (request.width * ratio).toInt().coerceAtLeast(1)
                    val height = (request.height * ratio).toInt().coerceAtLeast(1)
                    val result = createBitmap(width, height)
                    try {
                        val canvas = Canvas(result)
                        val white = Paint().apply { color = Color.WHITE }
                        for (page in request.pages) {
                            ensureActive()
                            val destination = RectF(page.bounds).apply {
                                left *= ratio; top *= ratio; right *= ratio; bottom *= ratio
                            }
                            val clip = Rect(
                                destination.left.toInt().coerceIn(0, width),
                                destination.top.toInt().coerceIn(0, height),
                                ceil(destination.right).toInt().coerceIn(0, width),
                                ceil(destination.bottom).toInt().coerceIn(0, height),
                            )
                            if (clip.isEmpty) continue
                            canvas.drawRect(destination, white)
                            if (!PdfFile.renderRegion(work.book, page.index, result, destination, clip)) {
                                throw IOException("PDF page ${page.index + 1} is unavailable")
                            }
                        }
                        result
                    } catch (error: Throwable) {
                        result.recycle()
                        throw error
                    }
                } } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    AppLog.put("PDF 区域渲染失败", error)
                    continue
                }
                if (requested == work.request) {
                    bitmap = image
                    rendered = work.request
                    renderCount++
                    view.invalidate()
                } else {
                    image.recycle()
                }
            }
        }
    }

    fun draw(canvas: Canvas, book: Book, zoom: PdfZoom, pages: List<Pair<TextPage, Float>>) {
        if (view.width <= 0 || view.height <= 0) return
        val viewport = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        val images = pages.flatMap { (page, offset) ->
            page.lines.flatMap { line ->
                line.columns.filterIsInstance<ImageColumn>().mapNotNull { image ->
                    val index = image.src.toIntOrNull() ?: return@mapNotNull null
                    val bounds = RectF(
                        image.start * zoom.scale + zoom.offsetX,
                        (line.lineTop + offset) * zoom.scale + zoom.offsetY,
                        image.end * zoom.scale + zoom.offsetX,
                        (line.lineBottom + offset) * zoom.scale + zoom.offsetY,
                    )
                    if (RectF.intersects(viewport, bounds)) Page(index, bounds) else null
                }
            }
        }
        val request = Request(book.bookUrl, view.width, view.height, images)
        if (request != requested) {
            requested = request
            requests.trySend(Work(request, book.copy()))
        }
        if (rendered == request) bitmap?.let { canvas.drawBitmap(it, null, viewport, paint) }
    }

    fun close() {
        requests.close()
        scope.cancel()
        bitmap = null
        rendered = null
        requested = null
    }
}
