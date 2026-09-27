package io.legado.app.ui.widget.image

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import io.legado.app.data.entities.BookshelfBook

class GroupCoverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val singleCover = newCoverView()
    private val grid = GridLayout(context).apply {
        rowCount = 2
        columnCount = 2
    }
    private val previewCovers = List(4) { index ->
        newCoverView().also { cover ->
            val cell = FrameLayout(context).apply {
                addView(
                    cover,
                    LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            grid.addView(
                cell,
                GridLayout.LayoutParams(
                    GridLayout.spec(index / 2, 1f),
                    GridLayout.spec(index % 2, 1f),
                ).apply {
                    width = 0
                    height = 0
                },
            )
        }
    }

    init {
        addView(singleCover, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun load(customCover: String?, books: List<BookshelfBook>) {
        if (!customCover.isNullOrBlank() || books.isEmpty()) {
            singleCover.visibility = View.VISIBLE
            grid.visibility = View.GONE
            singleCover.load(customCover)
            previewCovers.forEach { it.load() }
            return
        }
        singleCover.load()
        singleCover.visibility = View.GONE
        grid.visibility = View.VISIBLE
        previewCovers.forEachIndexed { index, cover ->
            val book = books.getOrNull(index)
            cover.visibility = if (book == null) View.INVISIBLE else View.VISIBLE
            if (book == null) {
                cover.load()
            } else {
                cover.load(
                    book.displayCover,
                    book.name,
                    book.author,
                    sourceOrigin = book.coverSourceOrigin,
                )
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(width * 4 / 3, MeasureSpec.EXACTLY),
        )
    }

    private fun newCoverView(): CoverImageView {
        return CoverImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
    }
}
