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
import java.util.UUID
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

@Parcelize
@Entity(
    tableName = "highlightRules",
    indices = [Index(value = ["uuid"], unique = true)]
)
data class HighlightRule(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0,
    @ColumnInfo(defaultValue = "''")
    var uuid: String = UUID.randomUUID().toString(),
    var name: String = "",
    var pattern: String = "",
    var isRegex: Boolean = false,
    var scope: String? = null,
    var isEnabled: Boolean = true,
    var style: String = "",
    @ColumnInfo(name = "sortOrder")
    var order: Int = Int.MIN_VALUE,
    var timeoutMillisecond: Long = DEFAULT_TIMEOUT_MILLISECONDS,
    @ColumnInfo(defaultValue = "0")
    var applyToTitle: Boolean = false,
    @ColumnInfo(defaultValue = "1")
    var applyToBody: Boolean = true
) : Parcelable {

    override fun equals(other: Any?): Boolean {
        if (other is HighlightRule) return other.id == id
        return super.equals(other)
    }

    override fun hashCode(): Int = id.hashCode()

    @IgnoredOnParcel
    @Ignore
    @Transient
    private var styleCache: Pair<String, HighlightStyle>? = null

    fun styleObj(): HighlightStyle {
        styleCache?.takeIf { it.first == style }?.second?.let { return it }
        return (GSON.fromJsonObject<HighlightStyle>(style).getOrNull() ?: HighlightStyle()).normalized()
            .also { styleCache = style to it }
    }

    fun applyStyle(value: HighlightStyle) {
        val normalized = value.normalized()
        style = GSON.toJson(normalized)
        styleCache = style to normalized
    }

    fun getDisplayName(): String = name.ifBlank { pattern }

    fun isValid(): Boolean {
        if (pattern.isEmpty()) return false
        if (!isRegex) return true
        try {
            Pattern.compile(pattern)
        } catch (_: PatternSyntaxException) {
            return false
        }
        return true
    }

    @Suppress("USELESS_CAST")
    fun normalizeForRestore(): HighlightRule {
        uuid = runCatching {
            UUID.fromString((uuid as String?).orEmpty()).toString()
        }.getOrElse {
            UUID.randomUUID().toString()
        }
        name = (name as String?).orEmpty()
        pattern = (pattern as String?).orEmpty()
        style = (style as String?).orEmpty()
        if (style.isBlank()) applyStyle(HighlightStyle())
        if (timeoutMillisecond <= 0L) {
            timeoutMillisecond = DEFAULT_TIMEOUT_MILLISECONDS
        }
        return this
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLISECONDS = 3000L
    }
}

data class HighlightRuleFile(
    val type: String? = null,
    val rules: List<HighlightRule?>? = null
) {
    companion object {
        const val TYPE = "highlightRule"
    }
}
