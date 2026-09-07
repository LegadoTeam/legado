package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.graphics.RectF
import androidx.annotation.Keep
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.paramPattern
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * 图片列
 */
@Keep
data class ImageColumn(
    override var start: Float,
    override var end: Float,
    var src: String,
    var click: String? = null
) : BaseColumn {

    override var textLine: TextLine = emptyTextLine
    override val positionLength: Int = 1
    private val reviewColumn = parseImageReviewOption(src, click)?.let { (count, resolvedClick) ->
        click = resolvedClick
        ReviewColumn(start, end, count)
    }

    override fun draw(view: ContentTextView, canvas: Canvas) {
        reviewColumn?.takeUnless { textLine.isImage }?.let {
            val height = ChapterProvider.getReviewHeight(textLine.isTitle)
            val width = ChapterProvider.getReviewWidth(textLine.isTitle)
            if (height <= 0f || width <= 0f) return
            val scale = minOf(1f, (end - start) / width)
            val drawWidth = width * scale
            it.start = start + (end - start - drawWidth) / 2
            it.end = it.start + drawWidth
            it.drawToCanvas(
                canvas,
                textLine.lineBase - textLine.lineTop,
                height * scale,
                containerHeight = textLine.height,
            )
            return
        }
        val book = ReadBook.book ?: return

        val height = textLine.height

        val bitmap = ImageProvider.getImage(
            book,
            src,
            (end - start).toInt(),
            height.toInt()
        )

        val rectF = if (textLine.isImage) {
            RectF(start, 0f, end, height)
        } else {
            /*以宽度为基准保持图片的原始比例叠加，当div为负数时，允许高度比字符更高*/
            val h = (end - start) / bitmap.width * bitmap.height
            val div = (height - h) / 2
            RectF(start, div, end, height - div)
        }
        kotlin.runCatching {
            canvas.drawBitmap(bitmap, null, rectF, view.imagePaint)
        }.onFailure { e ->
            appCtx.toastOnUi(e.localizedMessage)
        }
    }
    override fun isTouch(x: Float): Boolean {
        return x > start && x < end + 20.dpToPx()
    }

}

internal fun parseImageReviewOption(src: String, click: String?): Pair<Int, String>? {
    val matcher = paramPattern.matcher(src)
    if (!matcher.find()) return null
    val option = GSON.fromJsonObject<Map<String, String>>(
        src.substring(matcher.end())
    ).getOrNull() ?: return null
    when (option["style"]) {
        "text", "TEXT" -> Unit
        else -> return null
    }
    val count = option["reviewCount"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val resolvedClick = click?.takeIf { it.isNotBlank() }
        ?: option["click"]?.takeIf { it.isNotBlank() }
        ?: return null
    return count to resolvedClick
}
