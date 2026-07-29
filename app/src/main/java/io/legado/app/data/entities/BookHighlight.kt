package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import io.legado.app.help.HighlightStyle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(
    tableName = "highlights",
    indices = [Index(value = ["bookUrl"])]
)
data class BookHighlight(
    @PrimaryKey
    val time: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "")
    var bookUrl: String = "",
    @ColumnInfo(defaultValue = "")
    var chapterUrl: String = "",
    val bookName: String = "",
    val bookAuthor: String = "",
    var chapterIndex: Int = 0,
    var chapterPos: Int = 0,
    var chapterPosEnd: Int = 0,
    @ColumnInfo(defaultValue = "-1")
    var layoutTitleLength: Int = UNKNOWN_TITLE_LENGTH,
    var chapterName: String = "",
    var bookText: String = "",
    var style: String = "",
    var note: String = ""
) : Parcelable {

    @IgnoredOnParcel
    @Ignore
    @Transient
    private var styleCache: Pair<String, HighlightStyle>? = null

    fun styleObj(): HighlightStyle {
        styleCache?.takeIf { it.first == style }?.second?.let { return it }
        return (GSON.fromJsonObject<HighlightStyle>(style).getOrNull() ?: HighlightStyle()).normalized()
            .also { styleCache = style to it }
    }

    fun applyStyle(s: HighlightStyle) {
        val normalized = s.normalized()
        style = GSON.toJson(normalized)
        styleCache = style to normalized
    }

    fun bindLegacyOwner(bookUrl: String, chapterUrl: String) {
        if (this.bookUrl.isBlank()) this.bookUrl = bookUrl
        if (this.chapterUrl.isBlank()) this.chapterUrl = chapterUrl
    }

    fun bodyStart(currentTitleLength: Int): Int {
        return bodyPosition(chapterPos, currentTitleLength)
    }

    fun bodyEnd(currentTitleLength: Int): Int {
        return bodyPosition(chapterPosEnd, currentTitleLength)
    }

    fun pinLayoutTitleLength(currentTitleLength: Int): Boolean {
        if (layoutTitleLength >= 0 || currentTitleLength < 0) return false
        layoutTitleLength = currentTitleLength
        return true
    }

    private fun bodyPosition(layoutPosition: Int, currentTitleLength: Int): Int {
        val titleLength = layoutTitleLength.takeIf { it >= 0 } ?: currentTitleLength.coerceAtLeast(0)
        return (layoutPosition - titleLength).coerceAtLeast(0)
    }

    companion object {
        const val UNKNOWN_TITLE_LENGTH = -1
    }
}
