package io.legado.app.data.entities

import androidx.room.Entity
import androidx.annotation.Keep
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import java.util.UUID

/** Device-local metadata; deliberately separate from source import/export JSON. */
@Keep
@Entity(
    tableName = "book_source_check_states",
    foreignKeys = [ForeignKey(
        entity = BookSource::class,
        parentColumns = ["bookSourceUrl"],
        childColumns = ["bookSourceUrl"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class BookSourceCheckState(
    @PrimaryKey val bookSourceUrl: String,
    val revision: String = UUID.randomUUID().toString(),
    val sourceRevision: String = UUID.randomUUID().toString(),
    val status: String = NEEDS_CHECK,
    val checkedAt: Long = 0,
    val detail: String = "",
) {
    companion object {
        const val NEEDS_CHECK = "NEEDS_CHECK"
        const val PASSED = "PASSED"
        const val FAILED = "FAILED"
    }
}
